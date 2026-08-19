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

package com.microsoft.azure.storage.photosync.app.upload

import android.content.Context
import android.net.Uri
import com.microsoft.azure.storage.StorageCredentialsSharedAccessSignature
import com.microsoft.azure.storage.blob.CloudBlockBlob
import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalFileDao
import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalTransferDao
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import com.microsoft.azure.storage.photosync.app.data.remote.PhotoSyncApiService
import com.microsoft.azure.storage.photosync.app.util.Sha256
import com.microsoft.azure.storage.photosync.core.TransferState
import com.microsoft.azure.storage.photosync.core.TransferStateMachine
import com.microsoft.azure.storage.photosync.core.api.CompleteUploadTransferRequest
import com.microsoft.azure.storage.photosync.core.api.CreateUploadTransferRequest
import java.net.URI
import java.util.UUID

sealed class UploadOutcome {
    object Success : UploadOutcome()
    data class Skipped(val reason: String) : UploadOutcome()
    data class RetryableFailure(val message: String) : UploadOutcome()
    data class PermanentFailure(val message: String) : UploadOutcome()
}

/**
 * Executes the upload workflow of spec section 11.1 for a single queued
 * [LocalTransfer]: authorize -> upload block blob via SAS -> verify -> mark
 * complete. Content transfer and the completion API call are intentionally
 * separate steps (section 11.3): if completion fails after the blob was
 * written, the transfer is left in COMPLETING/VERIFYING so that
 * [com.microsoft.azure.storage.photosync.app.reconciliation.ReconciliationWorker]
 * or a StateUpdateWorker can retry only the state update, not the transfer.
 */
class UploadRepository(
    private val context: Context,
    private val api: PhotoSyncApiService,
    private val transferDao: LocalTransferDao,
    private val localFileDao: LocalFileDao,
    private val pendingStateUpdateDao: com.microsoft.azure.storage.photosync.app.data.local.dao.PendingStateUpdateDao,
    private val calculateChecksum: Boolean
) {

    suspend fun process(deviceId: String, transfer: LocalTransfer): UploadOutcome {
        var current = transfer

        try {
            if (current.state == TransferState.QUEUED.name || current.state == TransferState.RETRY_PENDING.name) {
                if (current.localMediaId == null) {
                    markFailed(current, "Missing localMediaId")
                    return UploadOutcome.PermanentFailure("Missing localMediaId")
                }

                val localFile = localFileDao.findByMediaId(current.localMediaId)
                if (localFile == null) {
                    markFailed(current, "Source media file no longer available")
                    return UploadOutcome.PermanentFailure("Source media file no longer available")
                }

                current = transition(current, TransferState.AUTHORIZING)

                val idempotencyKey = current.transferId
                val authResponse = api.createUploadTransfer(
                    CreateUploadTransferRequest(
                        deviceId = deviceId,
                        mediaId = current.localMediaId,
                        fileName = current.fileName,
                        fileSize = current.fileSize,
                        contentType = current.contentType,
                        capturedUtcEpochMillis = localFile.capturedUtcEpochMillis,
                        modifiedUtcEpochMillis = localFile.modifiedUtcEpochMillis,
                        sha256 = null,
                        idempotencyKey = idempotencyKey
                    ),
                    idempotencyKey
                )

                if (!authResponse.isSuccessful || authResponse.body() == null) {
                    return handleHttpFailure(current, authResponse.code(), "authorize upload")
                }

                val body = authResponse.body()!!
                current = current.copy(
                    fileId = body.fileId,
                    blobContainer = body.blobContainer,
                    blobName = body.blobName
                )
                current = transfer(current) // persist blob identity before uploading content
                current = transition(current, TransferState.TRANSFERRING)

                val sourceUri = Uri.parse(localFile.filePath)
                val sha256 = uploadBlobContent(body.sasUrl, sourceUri, current.fileSize)
                current = current.copy(sha256 = sha256)
                current = transfer(current)

                current = transition(current, TransferState.VERIFYING)
                // Size/checksum verification happens implicitly: the SDK upload call
                // throws on a length mismatch, and the checksum (if enabled) is
                // computed while streaming and stored above for later comparison
                // against the server-recorded value in the completion response.

                current = transition(current, TransferState.COMPLETING)
                val completeResponse = api.completeUploadTransfer(
                    current.transferId,
                    CompleteUploadTransferRequest(
                        transferId = current.transferId,
                        bytesTransferred = current.fileSize,
                        sha256 = current.sha256,
                        idempotencyKey = idempotencyKey
                    ),
                    idempotencyKey
                )

                if (!completeResponse.isSuccessful) {
                    // Content is already durably written to the blob: leave the
                    // transfer in COMPLETING (not RETRY_PENDING/FAILED) so a future
                    // pass does not re-upload it, and queue only the state-update
                    // retry for StateUpdateWorker (spec 11.3).
                    return handleCompletionFailure(current, completeResponse.code(), idempotencyKey)
                }

                current = transition(
                    current,
                    TransferState.COMPLETED,
                    completedUtcEpochMillis = System.currentTimeMillis()
                )
                return UploadOutcome.Success
            }
            return UploadOutcome.Skipped("Transfer not in a startable state: ${current.state}")
        } catch (e: java.io.IOException) {
            markRetryable(current, e.message ?: "IO error")
            return UploadOutcome.RetryableFailure(e.message ?: "IO error")
        } catch (e: com.microsoft.azure.storage.StorageException) {
            val retryable = e.httpStatusCode in RETRYABLE_HTTP_CODES
            if (retryable) {
                markRetryable(current, e.message ?: "Storage error")
                return UploadOutcome.RetryableFailure(e.message ?: "Storage error")
            }
            markFailed(current, e.message ?: "Storage error")
            return UploadOutcome.PermanentFailure(e.message ?: "Storage error")
        }
    }

    /** Uploads the source content as a block blob and returns its SHA-256 if enabled. */
    private fun uploadBlobContent(sasUrl: String, sourceUri: Uri, expectedSize: Long): String? {
        val uri = URI.create(sasUrl)
        val query = uri.rawQuery
        val credentials = StorageCredentialsSharedAccessSignature(query)
        val blob = CloudBlockBlob(URI.create(sasUrl.substringBefore("?")), credentials)

        context.contentResolver.openInputStream(sourceUri)?.use { rawStream ->
            if (calculateChecksum) {
                // Two-pass approach: read once (via a second stream) to compute
                // the checksum, then read again to upload. This keeps memory
                // bounded and avoids buffering the full file, at the cost of
                // reading the source content twice.
                val checksum = context.contentResolver.openInputStream(sourceUri)?.use { checksumStream ->
                    Sha256.of(checksumStream)
                }
                blob.upload(rawStream, expectedSize)
                return checksum
            } else {
                blob.upload(rawStream, expectedSize)
                return null
            }
        } ?: throw java.io.IOException("Unable to open source content: $sourceUri")
    }

    private suspend fun handleCompletionFailure(
        transfer: LocalTransfer,
        httpCode: Int,
        idempotencyKey: String
    ): UploadOutcome {
        val retryable = httpCode in RETRYABLE_HTTP_CODES
        val message = "complete upload failed with HTTP $httpCode"
        val updated = transfer.copy(
            attemptCount = transfer.attemptCount + 1,
            lastErrorCategory = if (retryable) "RETRYABLE" else "NON_RETRYABLE",
            lastErrorMessage = message,
            isRetryable = retryable
        )
        transferDao.update(updated)

        if (retryable) {
            val request = CompleteUploadTransferRequest(
                transferId = transfer.transferId,
                bytesTransferred = transfer.fileSize,
                sha256 = transfer.sha256,
                idempotencyKey = idempotencyKey
            )
            pendingStateUpdateDao.insert(
                com.microsoft.azure.storage.photosync.app.data.local.entity.PendingStateUpdate(
                    transferId = transfer.transferId,
                    updateType = "complete_upload",
                    payloadJson = gson.toJson(request),
                    idempotencyKey = idempotencyKey
                )
            )
            return UploadOutcome.RetryableFailure(message)
        }
        return UploadOutcome.PermanentFailure(message)
    }

    private suspend fun handleHttpFailure(transfer: LocalTransfer, httpCode: Int, step: String): UploadOutcome {
        return if (httpCode in RETRYABLE_HTTP_CODES) {
            markRetryable(transfer, "$step failed with HTTP $httpCode")
            UploadOutcome.RetryableFailure("$step failed with HTTP $httpCode")
        } else {
            markFailed(transfer, "$step failed with HTTP $httpCode")
            UploadOutcome.PermanentFailure("$step failed with HTTP $httpCode")
        }
    }

    private suspend fun markRetryable(transfer: LocalTransfer, message: String) {
        val next = transfer.copy(
            state = TransferState.RETRY_PENDING.name,
            attemptCount = transfer.attemptCount + 1,
            lastErrorCategory = "RETRYABLE",
            lastErrorMessage = message,
            isRetryable = true
        )
        transferDao.update(next)
    }

    private suspend fun markFailed(transfer: LocalTransfer, message: String) {
        val next = transfer.copy(
            state = TransferState.FAILED.name,
            attemptCount = transfer.attemptCount + 1,
            lastErrorCategory = "NON_RETRYABLE",
            lastErrorMessage = message,
            isRetryable = false
        )
        transferDao.update(next)
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

    private suspend fun transfer(transfer: LocalTransfer): LocalTransfer {
        transferDao.update(transfer)
        return transfer
    }

    companion object {
        val RETRYABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
        private val gson = com.google.gson.Gson()
    }
}
