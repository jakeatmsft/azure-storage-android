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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.R
import com.microsoft.azure.storage.photosync.app.security.CredentialStore
import com.microsoft.azure.storage.photosync.core.TransferDirection

/**
 * Processes queued/retry-pending upload transfers one at a time (spec
 * section 15 default concurrency = 1 on the original Pixel), showing an
 * ongoing notification with filename and progress for long transfers
 * (spec section 6.3).
 */
class UploadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.getInstance(appContext)
        val settings = container.settingsRepository
        val deviceId = settings.getOrCreateDeviceId()
        val credentialStore = CredentialStore(appContext)
        val directConfiguration = credentialStore.getAzureSasConfiguration()
        val baseUrl = settings.getApiBaseUrl()
        if (directConfiguration == null && baseUrl.isBlank()) return Result.failure()
        val transferDao = container.database.localTransferDao()
        val calculateChecksum = settings.isUploadChecksumEnabled()
        val directRepository = directConfiguration?.let {
            DirectSasUploadRepository(
                context = appContext,
                configuration = it,
                transferDao = transferDao,
                localFileDao = container.database.localFileDao(),
                calculateChecksum = calculateChecksum
            )
        }
        val apiRepository = if (directConfiguration == null) {
            UploadRepository(
                context = appContext,
                api = container.apiService(baseUrl) { credentialStore.getToken() },
                transferDao = transferDao,
                localFileDao = container.database.localFileDao(),
                pendingStateUpdateDao = container.database.pendingStateUpdateDao(),
                calculateChecksum = calculateChecksum
            )
        } else {
            null
        }

        setForegroundSafely()

        val batch = transferDao.nextQueuedOrRetryable(
            direction = TransferDirection.UPLOAD.name,
            nowUtcEpochMillis = System.currentTimeMillis(),
            limit = UPLOAD_BATCH_SIZE
        )

        if (batch.isEmpty()) return Result.success()

        var anyRetryable = false
        for (transfer in batch) {
            val outcome = directRepository?.process(deviceId, transfer)
                ?: requireNotNull(apiRepository).process(deviceId, transfer)
            when (outcome) {
                is UploadOutcome.RetryableFailure -> anyRetryable = true
                else -> Unit
            }
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
            .setContentTitle(appContext.getString(R.string.notification_uploading_title))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        setForeground(ForegroundInfo(NOTIFICATION_ID, notification))
    }

    companion object {
        const val UNIQUE_WORK_NAME = "upload-processing"
        const val UPLOAD_BATCH_SIZE = 25
        private const val CHANNEL_ID = "photosync_transfers"
        private const val NOTIFICATION_ID = 1001
    }
}
