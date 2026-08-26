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
import com.microsoft.azure.storage.StorageException
import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalFileDao
import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalTransferDao
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import com.microsoft.azure.storage.photosync.app.data.remote.AzureSasBlobClient
import com.microsoft.azure.storage.photosync.app.util.Sha256
import com.microsoft.azure.storage.photosync.core.AzureSasConfiguration
import com.microsoft.azure.storage.photosync.core.TransferState
import com.microsoft.azure.storage.photosync.core.TransferStateMachine

/** Uploads directly to a deterministic blob name using the scanned SAS. */
class DirectSasUploadRepository(
    private val context: Context,
    private val configuration: AzureSasConfiguration,
    private val transferDao: LocalTransferDao,
    private val localFileDao: LocalFileDao,
    private val calculateChecksum: Boolean
) {
    private val blobClient = AzureSasBlobClient(configuration)

    suspend fun process(deviceId: String, transfer: LocalTransfer): UploadOutcome {
        var current = transfer
        try {
            if (current.state != TransferState.QUEUED.name && current.state != TransferState.RETRY_PENDING.name) {
                return UploadOutcome.Skipped("Transfer not in a startable state: ${current.state}")
            }

            val localMediaId = current.localMediaId
                ?: return permanentFailure(current, "Missing localMediaId")
            val localFile = localFileDao.findByMediaId(localMediaId)
                ?: return permanentFailure(current, "Source media file no longer available")

            current = transition(current, TransferState.AUTHORIZING)
            val blobName = current.blobName ?: deterministicBlobName(deviceId, current)
            current = persist(
                current.copy(
                    fileId = blobName,
                    blobContainer = configuration.uploadContainer,
                    blobName = blobName
                )
            )
            current = transition(current, TransferState.TRANSFERRING)

            val sourceUri = Uri.parse(localFile.filePath)
            val checksum = if (calculateChecksum) {
                context.contentResolver.openInputStream(sourceUri)?.use { Sha256.of(it) }
                    ?: throw java.io.IOException("Unable to read source content: $sourceUri")
            } else {
                null
            }

            val blob = blobClient.uploadBlob(blobName)
            blob.properties.contentType = current.contentType
            checksum?.let { blob.metadata[AzureSasBlobClient.SHA256_METADATA_KEY] = it }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                blob.upload(stream, current.fileSize)
            } ?: throw java.io.IOException("Unable to open source content: $sourceUri")

            current = persist(current.copy(sha256 = checksum))
            current = transition(current, TransferState.VERIFYING)
            blob.downloadAttributes()
            if (blob.properties.length != current.fileSize) {
                return permanentFailure(
                    current,
                    "Remote size mismatch: expected ${current.fileSize}, got ${blob.properties.length}"
                )
            }

            current = transition(current, TransferState.COMPLETING)
            transition(current, TransferState.COMPLETED, System.currentTimeMillis())
            return UploadOutcome.Success
        } catch (e: java.io.IOException) {
            markRetryable(current, e.message ?: "IO error")
            return UploadOutcome.RetryableFailure(e.message ?: "IO error")
        } catch (e: StorageException) {
            val message = e.message ?: "Azure Storage error"
            return if (e.httpStatusCode in UploadRepository.RETRYABLE_HTTP_CODES) {
                markRetryable(current, message)
                UploadOutcome.RetryableFailure(message)
            } else {
                permanentFailure(current, message)
            }
        }
    }

    private fun deterministicBlobName(deviceId: String, transfer: LocalTransfer): String {
        val safeName = transfer.fileName
            .replace('/', '_')
            .replace('\\', '_')
            .takeLast(240)
            .ifBlank { "unnamed" }
        return "$deviceId/${transfer.identityKey}/$safeName"
    }

    private suspend fun permanentFailure(transfer: LocalTransfer, message: String): UploadOutcome.PermanentFailure {
        transferDao.update(
            transfer.copy(
                state = TransferState.FAILED.name,
                attemptCount = transfer.attemptCount + 1,
                lastErrorCategory = "NON_RETRYABLE",
                lastErrorMessage = message,
                isRetryable = false
            )
        )
        return UploadOutcome.PermanentFailure(message)
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

    private suspend fun transition(
        transfer: LocalTransfer,
        to: TransferState,
        completedUtcEpochMillis: Long? = null
    ): LocalTransfer {
        TransferStateMachine.requireTransition(TransferState.valueOf(transfer.state), to)
        return persist(
            transfer.copy(
                state = to.name,
                lastHeartbeatUtcEpochMillis = System.currentTimeMillis(),
                completedUtcEpochMillis = completedUtcEpochMillis ?: transfer.completedUtcEpochMillis
            )
        )
    }

    private suspend fun persist(transfer: LocalTransfer): LocalTransfer {
        transferDao.update(transfer)
        return transfer
    }
}
