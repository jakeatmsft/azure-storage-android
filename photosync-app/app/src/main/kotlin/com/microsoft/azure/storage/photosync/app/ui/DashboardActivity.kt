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

package com.microsoft.azure.storage.photosync.app.ui

import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.R
import com.microsoft.azure.storage.photosync.app.WorkScheduler
import com.microsoft.azure.storage.photosync.app.data.local.entity.AppMode
import com.microsoft.azure.storage.photosync.app.databinding.ActivityDashboardBinding
import com.microsoft.azure.storage.photosync.app.download.DownloadManifestWorker
import com.microsoft.azure.storage.photosync.app.download.DownloadWorker
import com.microsoft.azure.storage.photosync.app.upload.PhotoDiscoveryWorker
import com.microsoft.azure.storage.photosync.app.upload.UploadWorker
import com.microsoft.azure.storage.photosync.app.util.DestinationStorage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Dashboard screen (spec section 14): current mode, device identity,
 * charging/network status, pending/failed counts, last sync, storage, and
 * pause/resume/sync-now actions.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val container = AppContainer.getInstance(this)

        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.buttonHistory.setOnClickListener {
            startActivity(Intent(this, TransferHistoryActivity::class.java))
        }
        binding.buttonSyncNow.setOnClickListener {
            lifecycleScope.launch { enqueueManualSync() }
        }
        binding.buttonPauseResume.setOnClickListener {
            lifecycleScope.launch {
                val settings = container.settingsRepository
                val current = settings.getAppMode()
                val next = if (current == AppMode.PAUSED) settings.getLastActiveAppMode() else AppMode.PAUSED
                settings.setAppMode(next)
                WorkScheduler.reschedule(
                    applicationContext,
                    next,
                    settings.isUploadWifiOnly(),
                    settings.isUploadChargingOnly(),
                    settings.isDownloadWifiOnly()
                )
                render()
            }
        }

        lifecycleScope.launch { render() }

        container.database.localTransferDao().observePendingCount().let { flow ->
            lifecycleScope.launch { flow.collect { renderCounts(pending = it) } }
        }
        container.database.localTransferDao().observeFailedCount().let { flow ->
            lifecycleScope.launch { flow.collect { renderCounts(failed = it) } }
        }
    }

    private var lastPending = 0
    private var lastFailed = 0

    private fun renderCounts(pending: Int? = null, failed: Int? = null) {
        pending?.let { lastPending = it }
        failed?.let { lastFailed = it }
        binding.textPendingFailed.text = getString(
            R.string.dashboard_pending_label
        ) + ": $lastPending  " + getString(R.string.dashboard_failed_label) + ": $lastFailed"
    }

    private suspend fun render() {
        val container = AppContainer.getInstance(this)
        val settings = container.settingsRepository

        binding.textMode.text = getString(R.string.dashboard_mode_label) + ": ${settings.getAppMode()}"
        binding.textDevice.text =
            getString(R.string.dashboard_device_label) + ": ${settings.getDeviceName()} (${settings.getOrCreateDeviceId()})"

        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val isCharging = batteryManager.isCharging
        val connectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val hasNetwork = activeNetwork != null
        binding.textChargingNetwork.text =
            getString(R.string.dashboard_charging_label) + ": $isCharging   " +
                getString(R.string.dashboard_network_label) + ": $hasNetwork"

        val lastSync = settings.getLastSuccessfulSyncUtcEpochMillis()
        binding.textLastSync.text = getString(R.string.dashboard_last_sync_label) + ": " +
            (lastSync?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never")

        val destinationUri = settings.getDownloadDestinationUri()?.let(Uri::parse) ?: Uri.fromFile(filesDir)
        val storage = runCatching { DestinationStorage(this).snapshot(destinationUri) }.getOrNull()
        val minimumFreePercent = settings.getDownloadMinFreeStoragePercent()
        binding.textStorage.text = if (storage == null) {
            getString(R.string.dashboard_storage_unavailable)
        } else {
            getString(
                R.string.dashboard_storage_value,
                storage.availableBytes / (1024 * 1024),
                storage.availablePercent,
                minimumFreePercent
            )
        }

        binding.buttonPauseResume.setText(
            if (settings.getAppMode() == AppMode.PAUSED) R.string.action_resume else R.string.action_pause
        )
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { render() }
    }

    private suspend fun enqueueManualSync() {
        val settings = AppContainer.getInstance(this).settingsRepository
        val mode = settings.getAppMode()
        if (mode == AppMode.PAUSED) return

        val workManager = WorkManager.getInstance(this)
        if (mode == AppMode.UPLOAD_ONLY || mode == AppMode.UPLOAD_AND_DOWNLOAD) {
            val uploadNetwork = if (settings.isUploadWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
            val discovery = OneTimeWorkRequestBuilder<PhotoDiscoveryWorker>().build()
            val upload = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(uploadNetwork)
                        .setRequiresCharging(settings.isUploadChargingOnly())
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            workManager.beginUniqueWork(MANUAL_UPLOAD_WORK, ExistingWorkPolicy.REPLACE, discovery)
                .then(upload)
                .enqueue()
        }

        if (mode == AppMode.DOWNLOAD_ONLY || mode == AppMode.UPLOAD_AND_DOWNLOAD) {
            val downloadNetwork = if (settings.isDownloadWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
            val manifest = OneTimeWorkRequestBuilder<DownloadManifestWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(downloadNetwork).build())
                .build()
            val download = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(downloadNetwork)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()
            workManager.beginUniqueWork(MANUAL_DOWNLOAD_WORK, ExistingWorkPolicy.REPLACE, manifest)
                .then(download)
                .enqueue()
        }
    }

    companion object {
        private const val MANUAL_UPLOAD_WORK = "manual-upload-sync"
        private const val MANUAL_DOWNLOAD_WORK = "manual-download-sync"
    }
}
