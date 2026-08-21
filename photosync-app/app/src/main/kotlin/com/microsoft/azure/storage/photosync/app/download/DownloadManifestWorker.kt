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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import com.microsoft.azure.storage.photosync.app.security.CredentialStore
import com.microsoft.azure.storage.photosync.core.TransferDirection
import com.microsoft.azure.storage.photosync.core.TransferIdentity
import com.microsoft.azure.storage.photosync.core.TransferState
import java.util.UUID

/**
 * Requests the device's download manifest from the Azure API and queues
 * local transfers for any item not already recorded as a successful
 * download for this device (spec section 3.3, 11.2 steps 1-4).
 *
 * A filename and size match alone is never treated as proof of a prior
 * download: duplicate detection uses [TransferIdentity.DownloadIdentity],
 * which also incorporates the blob container, name, and ETag/version.
 */
class DownloadManifestWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.getInstance(appContext)
        val settings = container.settingsRepository
        val deviceId = settings.getOrCreateDeviceId()
        val baseUrl = settings.getApiBaseUrl()
        if (baseUrl.isBlank() || settings.getDownloadDestinationUri() == null) {
            return Result.failure()
        }

        val credentialStore = CredentialStore(appContext)
        val api = container.apiService(baseUrl) { credentialStore.getToken() }
        val transferDao = container.database.localTransferDao()

        return try {
            val response = api.getDownloadManifest(deviceId)
            if (!response.isSuccessful || response.body() == null) {
                return if (response.code() in RETRYABLE_HTTP_CODES) Result.retry() else Result.failure()
            }

            val completedKeys = transferDao.completedIdentityKeys(TransferDirection.DOWNLOAD.name).toSet()

            for (item in response.body()!!.items) {
                val identity = TransferIdentity.DownloadIdentity(
                    deviceId = deviceId,
                    blobContainer = item.blobContainer,
                    blobName = item.blobName,
                    blobEtagOrVersion = item.blobEtagOrVersion,
                    expectedFileSize = item.fileSize
                )
                val identityKey = identity.key()

                if (TransferIdentity.isDuplicateDownload(completedKeys, identity)) continue
                if (transferDao.findByIdentityKey(identityKey) != null) continue

                transferDao.insert(
                    LocalTransfer(
                        transferId = item.transferId.ifBlank { UUID.randomUUID().toString() },
                        identityKey = identityKey,
                        direction = TransferDirection.DOWNLOAD.name,
                        fileId = item.fileId,
                        localMediaId = null,
                        fileName = item.fileName,
                        fileSize = item.fileSize,
                        contentType = item.contentType,
                        blobContainer = item.blobContainer,
                        blobName = item.blobName,
                        blobEtagOrVersion = item.blobEtagOrVersion,
                        sha256 = item.sha256,
                        destinationUri = null,
                        state = TransferState.QUEUED.name
                    )
                )
            }
            Result.success()
        } catch (e: java.io.IOException) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "download-manifest"
        val RETRYABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}
