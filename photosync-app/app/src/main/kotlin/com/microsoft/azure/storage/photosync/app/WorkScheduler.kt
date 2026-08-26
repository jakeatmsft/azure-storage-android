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

package com.microsoft.azure.storage.photosync.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.microsoft.azure.storage.photosync.app.data.local.entity.AppMode
import com.microsoft.azure.storage.photosync.app.download.DownloadManifestWorker
import com.microsoft.azure.storage.photosync.app.download.DownloadWorker
import com.microsoft.azure.storage.photosync.app.reconciliation.CleanupWorker
import com.microsoft.azure.storage.photosync.app.reconciliation.ReconciliationWorker
import com.microsoft.azure.storage.photosync.app.reconciliation.StateUpdateWorker
import com.microsoft.azure.storage.photosync.app.upload.PhotoDiscoveryWorker
import com.microsoft.azure.storage.photosync.app.upload.UploadWorker
import java.util.concurrent.TimeUnit

/**
 * Schedules the WorkManager workers (spec section 6) with the constraints
 * required by each direction. Upload Wi-Fi/charging and download Wi-Fi are
 * configurable; Android may still defer background work for battery health.
 */
object WorkScheduler {

    private const val DISCOVERY_INTERVAL_MINUTES = 15L
    private const val UPLOAD_INTERVAL_MINUTES = 15L
    private const val DOWNLOAD_MANIFEST_INTERVAL_MINUTES = 15L
    private const val DOWNLOAD_INTERVAL_MINUTES = 15L
    private const val RECONCILIATION_INTERVAL_MINUTES = 60L
    private const val STATE_UPDATE_INTERVAL_MINUTES = 15L
    private const val CLEANUP_INTERVAL_MINUTES = 24 * 60L

    fun reschedule(
        context: Context,
        mode: AppMode,
        uploadWifiOnly: Boolean,
        uploadChargingOnly: Boolean,
        downloadWifiOnly: Boolean
    ) {
        val workManager = WorkManager.getInstance(context)

        val uploadEnabled = mode == AppMode.UPLOAD_ONLY || mode == AppMode.UPLOAD_AND_DOWNLOAD
        val downloadEnabled = mode == AppMode.DOWNLOAD_ONLY || mode == AppMode.UPLOAD_AND_DOWNLOAD

        if (uploadEnabled) {
            val uploadNetworkType = if (uploadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val discoveryConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            workManager.enqueueUniquePeriodicWork(
                PhotoDiscoveryWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PhotoDiscoveryWorker>(DISCOVERY_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(discoveryConstraints)
                    .build()
            )

            val uploadConstraints = Constraints.Builder()
                .setRequiredNetworkType(uploadNetworkType)
                .setRequiresCharging(uploadChargingOnly)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UploadWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UploadWorker>(UPLOAD_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(uploadConstraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        } else {
            workManager.cancelUniqueWork(PhotoDiscoveryWorker.UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(UploadWorker.UNIQUE_WORK_NAME)
        }

        if (downloadEnabled) {
            val manifestConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            workManager.enqueueUniquePeriodicWork(
                DownloadManifestWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DownloadManifestWorker>(
                    DOWNLOAD_MANIFEST_INTERVAL_MINUTES,
                    TimeUnit.MINUTES
                ).setConstraints(manifestConstraints).build()
            )

            val downloadNetworkType = if (downloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val downloadConstraints = Constraints.Builder()
                .setRequiredNetworkType(downloadNetworkType)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            workManager.enqueueUniquePeriodicWork(
                DownloadWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DownloadWorker>(DOWNLOAD_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(downloadConstraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        } else {
            workManager.cancelUniqueWork(DownloadManifestWorker.UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK_NAME)
        }

        // Reconciliation, state-update retries, and temp-file cleanup run
        // regardless of mode (even when paused) so interrupted transfers and
        // orphaned files are still cleaned up; they perform no new transfers.
        workManager.enqueueUniquePeriodicWork(
            ReconciliationWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ReconciliationWorker>(RECONCILIATION_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
        workManager.enqueueUniquePeriodicWork(
            StateUpdateWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<StateUpdateWorker>(STATE_UPDATE_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
        workManager.enqueueUniquePeriodicWork(
            CleanupWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CleanupWorker>(CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES).build()
        )
    }
}
