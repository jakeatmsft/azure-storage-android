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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import android.content.Context
import androidx.work.WorkerParameters
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.R
import com.microsoft.azure.storage.photosync.app.data.remote.AzureSasBlobClient
import com.microsoft.azure.storage.photosync.app.security.CredentialStore
import com.microsoft.azure.storage.photosync.app.util.DestinationStorage
import com.microsoft.azure.storage.photosync.core.DownloadStoragePolicy
import com.microsoft.azure.storage.photosync.core.TransferDirection
import com.microsoft.azure.storage.photosync.core.TransferIdentity

/**
 * Processes queued/retry-pending download transfers while enforcing the
 * configured free-space threshold before every file.
 */
class DownloadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.getInstance(appContext)
        val settings = container.settingsRepository
        val deviceId = settings.getOrCreateDeviceId()
        val baseUrl = settings.getApiBaseUrl()
        val destinationUriString = settings.getDownloadDestinationUri()
        if (destinationUriString == null) return Result.failure()

        val destinationUri = Uri.parse(destinationUriString)
        val minimumFreePercent = settings.getDownloadMinFreeStoragePercent()
        val destinationStorage = DestinationStorage(appContext)
        val credentialStore = CredentialStore(appContext)
        val directConfiguration = credentialStore.getAzureSasConfiguration()
        if (directConfiguration == null && baseUrl.isBlank()) return Result.failure()
        val api = if (directConfiguration == null) {
            container.apiService(baseUrl) { credentialStore.getToken() }
        } else {
            null
        }
        val transferDao = container.database.localTransferDao()

        val repository = DownloadRepository(
            context = appContext,
            api = api,
            transferDao = transferDao,
            pendingStateUpdateDao = if (api != null) container.database.pendingStateUpdateDao() else null,
            allowOverwrite = settings.isDownloadOverwriteOnConflict()
        )

        setForegroundSafely()

        val batch = transferDao.nextQueuedOrRetryable(
            direction = TransferDirection.DOWNLOAD.name,
            nowUtcEpochMillis = System.currentTimeMillis(),
            limit = DOWNLOAD_BATCH_SIZE
        )
        if (batch.isEmpty()) return Result.success()

        var sasUrlByTransferId = emptyMap<String, String>()
        var sasUrlByIdentityKey = emptyMap<String, String>()
        if (api != null) {
            // Re-fetch the API manifest immediately before processing so each
            // transfer uses a fresh SAS URL rather than a persisted credential.
            val manifestResponse = api.getDownloadManifest(deviceId)
            if (!manifestResponse.isSuccessful || manifestResponse.body() == null) {
                return if (manifestResponse.code() in DownloadManifestWorker.RETRYABLE_HTTP_CODES) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            val manifestItems = manifestResponse.body()!!.items
            sasUrlByTransferId = manifestItems.associate { it.transferId to it.sasUrl }
            sasUrlByIdentityKey = manifestItems.associate { item ->
                TransferIdentity.DownloadIdentity(
                    deviceId = deviceId,
                    blobContainer = item.blobContainer,
                    blobName = item.blobName,
                    blobEtagOrVersion = item.blobEtagOrVersion,
                    expectedFileSize = item.fileSize
                ).key() to item.sasUrl
            }
        }
        val directBlobClient = directConfiguration?.let(::AzureSasBlobClient)

        var anyRetryable = false
        var stoppedForStorage = false
        var processedAny = false
        for (transfer in batch) {
            val storageSnapshot = try {
                destinationStorage.snapshot(destinationUri)
            } catch (e: RuntimeException) {
                return Result.retry()
            }
            if (!DownloadStoragePolicy.canDownload(
                    snapshot = storageSnapshot,
                    nextFileSizeBytes = transfer.fileSize,
                    minimumFreePercent = minimumFreePercent
                )
            ) {
                // Keep this transfer queued. The DAO's stable oldest-first
                // ordering makes it the first item retried after space is freed.
                stoppedForStorage = true
                break
            }

            val sasUrl = if (directBlobClient != null) {
                transfer.blobName?.let(directBlobClient::downloadBlobSasUrl)
            } else {
                sasUrlByTransferId[transfer.transferId] ?: sasUrlByIdentityKey[transfer.identityKey]
            }
            if (sasUrl == null) {
                anyRetryable = true
                continue
            }
            processedAny = true
            when (repository.process(destinationUri, transfer, sasUrl)) {
                is DownloadOutcome.RetryableFailure -> anyRetryable = true
                else -> Unit
            }
        }

        if (processedAny && !anyRetryable && !stoppedForStorage) {
            settings.setLastSuccessfulSyncUtcEpochMillis(System.currentTimeMillis())
        }

        return if (anyRetryable || stoppedForStorage) Result.retry() else Result.success()
    }

    private suspend fun setForegroundSafely() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.notification_channel_transfers),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.notification_downloading_title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        setForeground(ForegroundInfo(NOTIFICATION_ID, notification))
    }

    companion object {
        const val UNIQUE_WORK_NAME = "download-processing"
        const val DOWNLOAD_BATCH_SIZE = 10
        private const val CHANNEL_ID = "photosync_transfers"
        private const val NOTIFICATION_ID = 1002
    }
}
