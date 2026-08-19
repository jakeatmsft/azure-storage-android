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
import com.microsoft.azure.storage.photosync.app.security.CredentialStore
import com.microsoft.azure.storage.photosync.core.TransferDirection

/**
 * Processes queued/retry-pending download transfers. Per spec sections 3.3
 * and 6.2, automatic downloads only run while the device reports it is
 * charging; that constraint is enforced by the WorkManager constraints
 * configured in WorkScheduler, not by this worker itself.
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
        if (baseUrl.isBlank() || destinationUriString == null) return Result.failure()

        val destinationUri = Uri.parse(destinationUriString)
        val credentialStore = CredentialStore(appContext)
        val api = container.apiService(baseUrl) { credentialStore.getToken() }
        val transferDao = container.database.localTransferDao()

        val repository = DownloadRepository(
            context = appContext,
            api = api,
            transferDao = transferDao,
            pendingStateUpdateDao = container.database.pendingStateUpdateDao(),
            allowOverwrite = settings.isDownloadOverwriteOnConflict()
        )

        setForegroundSafely()

        val batch = transferDao.nextQueuedOrRetryable(
            direction = TransferDirection.DOWNLOAD.name,
            nowUtcEpochMillis = System.currentTimeMillis(),
            limit = DOWNLOAD_BATCH_SIZE
        )
        if (batch.isEmpty()) return Result.success()

        // Re-fetch the manifest immediately before processing so each transfer
        // uses a fresh, still-valid SAS URL rather than one persisted earlier.
        val manifestResponse = api.getDownloadManifest(deviceId)
        if (!manifestResponse.isSuccessful || manifestResponse.body() == null) {
            return if (manifestResponse.code() in DownloadManifestWorker.RETRYABLE_HTTP_CODES) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
        val sasUrlByTransferId = manifestResponse.body()!!.items.associate { it.transferId to it.sasUrl }

        var anyRetryable = false
        for (transfer in batch) {
            val sasUrl = sasUrlByTransferId[transfer.transferId] ?: continue
            when (repository.process(destinationUri, transfer, sasUrl)) {
                is DownloadOutcome.RetryableFailure -> anyRetryable = true
                else -> Unit
            }
        }

        if (batch.isNotEmpty() && !anyRetryable) {
            settings.setLastSuccessfulSyncUtcEpochMillis(System.currentTimeMillis())
        }

        return if (anyRetryable) Result.retry() else Result.success()
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
