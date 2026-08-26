/**
 * Copyright Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.azure.storage.photosync.app.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.microsoft.azure.storage.StorageException
import com.microsoft.azure.storage.blob.CloudBlockBlob
import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalTransferDao
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import com.microsoft.azure.storage.photosync.app.data.remote.PhotoSyncApiService
import com.microsoft.azure.storage.photosync.app.util.Sha256
import com.microsoft.azure.storage.photosync.core.FilenameConflictResolver
import com.microsoft.azure.storage.photosync.core.TransferState
import com.microsoft.azure.storage.photosync.core.TransferStateMachine
import com.microsoft.azure.storage.photosync.core.api.CompleteDownloadTransferRequest
import java.net.URI

sealed class DownloadOutcome {
    object Success : DownloadOutcome()
    data class Skipped(val reason: String) : DownloadOutcome()
    data class RetryableFailure(val message: String) : DownloadOutcome()
    data class PermanentFailure(val message: String) : DownloadOutcome()
}

/**
 * Executes the download workflow of spec sections 3.3 and 11.2: stream the
 * blob to a temporary file, verify size and checksum, then rename to the
 * final (conflict-resolved) filename only after verification succeeds.
 */
class DownloadRepository(
    private val context: Context,
    private val api: PhotoSyncApiService?,
    private val transferDao: LocalTransferDao,
    private val pendingStateUpdateDao: com.microsoft.azure.storage.photosync.app.data.local.dao.PendingStateUpdateDao?,
    private val allowOverwrite: Boolean
) {

    suspend fun process(destinationTreeUri: Uri, transfer: LocalTransfer, sasUrl: String): DownloadOutcome {
        var current = transfer
        val destinationDir = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: return DownloadOutcome.PermanentFailure("Destination folder is no longer accessible")

        if (!destinationDir.canWrite()) {
            return DownloadOutcome.PermanentFailure("Lost write access to the destination folder")
        }

        try {
            if (current.state != TransferState.QUEUED.name && current.state != TransferState.RETRY_PENDING.name) {
                return DownloadOutcome.Skipped("Transfer not in a startable state: ${current.state}")
            }

            val resolution = FilenameConflictResolver.resolve(
                desiredName = current.fileName,
                existingFileExists = { destinationDir.findFile(it) != null },
                existingFileMatches = { name ->
                    val existing = destinationDir.findFile(name)
                    existing != null && existing.length() == current.fileSize
                },
                allowOverwrite = allowOverwrite
            )

            val finalName: String = when (resolution) {
                is FilenameConflictResolver.Resolution.SkipIdenticalExisting -> {
                    current = transition(current, TransferState.SKIPPED)
                    return DownloadOutcome.Skipped("Identical file already present")
                }
                is FilenameConflictResolver.Resolution.UseOriginal -> resolution.name
                is FilenameConflictResolver.Resolution.UseAlternateName -> resolution.name
                is FilenameConflictResolver.Resolution.Overwrite -> resolution.name
            }

            current = transition(current, TransferState.AUTHORIZING)
            current = transition(current, TransferState.TRANSFERRING)

            val tempFile = destinationDir.createFile("application/octet-stream", "$finalName.part")
            if (tempFile == null) {
                val message = "Unable to create temporary file"
                markRetryable(current, message)
                return DownloadOutcome.RetryableFailure(message)
            }

            downloadToFile(sasUrl, tempFile.uri)

            current = transition(current, TransferState.VERIFYING)
            val (actualSize, actualSha256) = verify(tempFile.uri)
            if (actualSize != current.fileSize) {
                tempFile.delete()
                markFailed(current, "Size mismatch: expected ${current.fileSize}, got $actualSize")
                return DownloadOutcome.PermanentFailure("Size mismatch")
            }
            if (current.sha256 != null && !current.sha256.equals(actualSha256, ignoreCase = true)) {
                tempFile.delete()
                markFailed(current, "Checksum mismatch")
                return DownloadOutcome.PermanentFailure("Checksum mismatch")
            }

            current = transition(current, TransferState.COMPLETING)
            if (!tempFile.renameTo(finalName)) {
                val message = "Unable to rename temporary file to final name"
                markRetryable(current, message)
                return DownloadOutcome.RetryableFailure(message)
            }

            val apiClient = api
            if (apiClient == null) {
                transition(current, TransferState.COMPLETED, completedUtcEpochMillis = System.currentTimeMillis())
                return DownloadOutcome.Success
            }

            val completeResponse = apiClient.completeDownloadTransfer(
                current.transferId,
                CompleteDownloadTransferRequest(
                    transferId = current.transferId,
                    bytesTransferred = actualSize,
                    finalFileName = finalName,
                    sha256 = actualSha256,
                    idempotencyKey = current.transferId
                ),
                current.transferId
            )
            if (!completeResponse.isSuccessful) {
                // The file is already verified and renamed to its final name on
                // disk: leave the transfer in COMPLETING (not RETRY_PENDING) so a
                // future pass does not re-download it, and queue only the
                // state-update retry for StateUpdateWorker (spec 11.3).
                return handleCompletionFailure(current, completeResponse.code(), actualSize, finalName, actualSha256)
            }

            current = transition(current, TransferState.COMPLETED, completedUtcEpochMillis = System.currentTimeMillis())
            return DownloadOutcome.Success
        } catch (e: java.io.IOException) {
            markRetryable(current, e.message ?: "IO error")
            return DownloadOutcome.RetryableFailure(e.message ?: "IO error")
        } catch (e: StorageException) {
            val message = e.message ?: "Azure Storage error"
            return if (e.httpStatusCode in DownloadManifestWorker.RETRYABLE_HTTP_CODES) {
                markRetryable(current, message)
                DownloadOutcome.RetryableFailure(message)
            } else {
                markFailed(current, message)
                DownloadOutcome.PermanentFailure(message)
            }
        }
    }

    private suspend fun handleCompletionFailure(
        transfer: LocalTransfer,
        httpCode: Int,
        bytesTransferred: Long,
        finalFileName: String,
        sha256: String
    ): DownloadOutcome {
        val retryable = httpCode in DownloadManifestWorker.RETRYABLE_HTTP_CODES
        val message = "complete download failed with HTTP $httpCode"
        transferDao.update(
            transfer.copy(
                attemptCount = transfer.attemptCount + 1,
                lastErrorCategory = if (retryable) "RETRYABLE" else "NON_RETRYABLE",
                lastErrorMessage = message,
                isRetryable = retryable
            )
        )
        if (retryable) {
            val request = CompleteDownloadTransferRequest(
                transferId = transfer.transferId,
                bytesTransferred = bytesTransferred,
                finalFileName = finalFileName,
                sha256 = sha256,
                idempotencyKey = transfer.transferId
            )
            requireNotNull(pendingStateUpdateDao).insert(
                com.microsoft.azure.storage.photosync.app.data.local.entity.PendingStateUpdate(
                    transferId = transfer.transferId,
                    updateType = "complete_download",
                    payloadJson = gson.toJson(request),
                    idempotencyKey = transfer.transferId
                )
            )
            return DownloadOutcome.RetryableFailure(message)
        }
        return DownloadOutcome.PermanentFailure(message)
    }

    private fun downloadToFile(sasUrl: String, destination: Uri) {
        val uri = URI.create(sasUrl)
        val credentials = com.microsoft.azure.storage.StorageCredentialsSharedAccessSignature(uri.rawQuery)
        val blob = CloudBlockBlob(URI.create(sasUrl.substringBefore("?")), credentials)

        context.contentResolver.openOutputStream(destination)?.use { output ->
            blob.download(output)
        } ?: throw java.io.IOException("Unable to open temporary destination file: $destination")
    }

    private fun verify(uri: Uri): Pair<Long, String> {
        var size = 0L
        val sha256 = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                size += read
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } ?: throw java.io.IOException("Unable to reopen downloaded file for verification: $uri")
        return size to sha256
    }

    private suspend fun markRetryable(transfer: LocalTransfer, message: String) {
        transferDao.update(
            transfer.copy(
                state = TransferState.RETRY_PENDING.name,
                attemptCount = transfer.attemptCount + 1,
                lastErrorCategory = "RETRYABLE",
                lastErrorMessage = message,
                isRetryable = true
            )
        )
    }

    private suspend fun markFailed(transfer: LocalTransfer, message: String) {
        transferDao.update(
            transfer.copy(
                state = TransferState.FAILED.name,
                attemptCount = transfer.attemptCount + 1,
                lastErrorCategory = "NON_RETRYABLE",
                lastErrorMessage = message,
                isRetryable = false
            )
        )
    }

    private suspend fun transition(
        transfer: LocalTransfer,
        to: TransferState,
        completedUtcEpochMillis: Long? = null
    ): LocalTransfer {
        TransferStateMachine.requireTransition(TransferState.valueOf(transfer.state), to)
        val next = transfer.copy(
            state = to.name,
            lastHeartbeatUtcEpochMillis = System.currentTimeMillis(),
            completedUtcEpochMillis = completedUtcEpochMillis ?: transfer.completedUtcEpochMillis
        )
        transferDao.update(next)
        return next
    }

    companion object {
        private val gson = com.google.gson.Gson()
    }
}
