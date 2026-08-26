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
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.R
import com.microsoft.azure.storage.photosync.app.WorkScheduler
import com.microsoft.azure.storage.photosync.app.data.local.entity.AppMode
import com.microsoft.azure.storage.photosync.app.databinding.ActivitySettingsBinding
import com.microsoft.azure.storage.photosync.app.security.CredentialStore
import com.microsoft.azure.storage.photosync.core.AzureSasProvisioning
import com.microsoft.azure.storage.photosync.core.AzureSasProvisioningResult
import com.microsoft.azure.storage.photosync.core.DownloadStoragePolicy
import com.microsoft.azure.storage.photosync.core.TransferDirection
import kotlinx.coroutines.launch

/**
 * Settings screen (spec section 14): enabled folders, Wi-Fi/charging
 * toggles, checksum calculation, and the SAF-selected download destination.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var isLoadingSettings = true

    private val scanSasQr = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            Toast.makeText(this, R.string.settings_sas_scan_cancelled, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        when (val parsed = AzureSasProvisioning.parse(contents)) {
            is AzureSasProvisioningResult.Failure ->
                Toast.makeText(this, parsed.message, Toast.LENGTH_LONG).show()
            is AzureSasProvisioningResult.Success -> lifecycleScope.launch {
                val container = AppContainer.getInstance(this@SettingsActivity)
                CredentialStore(this@SettingsActivity).setAzureSasConfiguration(parsed.configuration)
                resetDiscoveryForNewStorage(container)
                renderStorageCredentialStatus()
                rescheduleCurrentSettings()
                Toast.makeText(this@SettingsActivity, R.string.settings_sas_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val chooseDestination = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        lifecycleScope.launch {
            AppContainer.getInstance(this@SettingsActivity).settingsRepository
                .setDownloadDestinationUri(uri.toString())
            binding.textDestination.text = uri.toString()
        }
    }

    private val chooseUploadFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        lifecycleScope.launch {
            val container = AppContainer.getInstance(this@SettingsActivity)
            val settings = container.settingsRepository
            settings.setUploadFolderUris(settings.getUploadFolderUris() + uri.toString())
            resetUploadDiscovery(container)
            renderUploadFolders()
            rescheduleCurrentSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val settings = AppContainer.getInstance(this).settingsRepository

        lifecycleScope.launch {
            val currentMode = settings.getAppMode()
            val displayedMode = if (currentMode == AppMode.PAUSED) {
                settings.getLastActiveAppMode()
            } else {
                currentMode
            }
            binding.radioMode.check(
                when (displayedMode) {
                    AppMode.UPLOAD_ONLY -> binding.radioModeUpload.id
                    AppMode.DOWNLOAD_ONLY -> binding.radioModeDownload.id
                    AppMode.UPLOAD_AND_DOWNLOAD, AppMode.PAUSED -> binding.radioModeBoth.id
                }
            )
            binding.checkUploadWifiOnly.isChecked = settings.isUploadWifiOnly()
            binding.checkUploadChargingOnly.isChecked = settings.isUploadChargingOnly()
            binding.checkUploadIncludeVideos.isChecked = settings.isUploadIncludeVideos()
            binding.checkUploadChecksum.isChecked = settings.isUploadChecksumEnabled()
            binding.checkDownloadWifiOnly.isChecked = settings.isDownloadWifiOnly()
            binding.checkDownloadOverwrite.isChecked = settings.isDownloadOverwriteOnConflict()
            binding.textDestination.text = settings.getDownloadDestinationUri() ?: "Not selected"
            val minimumFreePercent = settings.getDownloadMinFreeStoragePercent()
            binding.seekDownloadMinFreePercent.progress =
                minimumFreePercent - DownloadStoragePolicy.MIN_FREE_PERCENT
            renderMinimumFreePercent(minimumFreePercent)
            renderStorageCredentialStatus()
            renderUploadFolders()
            isLoadingSettings = false
        }

        binding.buttonChooseDestination.setOnClickListener { chooseDestination.launch(null) }
        binding.buttonAddUploadFolder.setOnClickListener { chooseUploadFolder.launch(null) }
        binding.buttonClearUploadFolders.setOnClickListener {
            lifecycleScope.launch {
                val container = AppContainer.getInstance(this@SettingsActivity)
                container.settingsRepository.setUploadFolderUris(emptyList())
                resetUploadDiscovery(container)
                renderUploadFolders()
                rescheduleCurrentSettings()
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.settings_upload_folders_cleared,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.buttonScanSasQr.setOnClickListener {
            scanSasQr.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.settings_sas_scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }
        binding.buttonClearSas.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear_sas_title)
                .setMessage(R.string.settings_clear_sas_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_clear) { _, _ ->
                    lifecycleScope.launch {
                        val container = AppContainer.getInstance(this@SettingsActivity)
                        CredentialStore(this@SettingsActivity).clearAzureSasConfiguration()
                        resetDiscoveryForNewStorage(container)
                        renderStorageCredentialStatus()
                        rescheduleCurrentSettings()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.settings_sas_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .show()
        }

        binding.radioMode.setOnCheckedChangeListener { _, checkedId ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                binding.radioModeUpload.id -> AppMode.UPLOAD_ONLY
                binding.radioModeDownload.id -> AppMode.DOWNLOAD_ONLY
                else -> AppMode.UPLOAD_AND_DOWNLOAD
            }
            persistAndReschedule { settings.setAppMode(mode) }
        }

        binding.checkUploadWifiOnly.setOnCheckedChangeListener { _, checked ->
            persistAndReschedule { settings.setUploadWifiOnly(checked) }
        }
        binding.checkUploadChargingOnly.setOnCheckedChangeListener { _, checked ->
            persistAndReschedule { settings.setUploadChargingOnly(checked) }
        }
        binding.checkUploadIncludeVideos.setOnCheckedChangeListener { _, checked ->
            persistAndReschedule { settings.setUploadIncludeVideos(checked) }
        }
        binding.checkUploadChecksum.setOnCheckedChangeListener { _, checked ->
            persistAndReschedule { settings.setUploadChecksumEnabled(checked) }
        }
        binding.checkDownloadWifiOnly.setOnCheckedChangeListener { _, checked ->
            persistAndReschedule { settings.setDownloadWifiOnly(checked) }
        }
        binding.checkDownloadOverwrite.setOnCheckedChangeListener { _, checked ->
            persistAndReschedule { settings.setDownloadOverwriteOnConflict(checked) }
        }
        binding.seekDownloadMinFreePercent.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    renderMinimumFreePercent(progress + DownloadStoragePolicy.MIN_FREE_PERCENT)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val value = seekBar.progress + DownloadStoragePolicy.MIN_FREE_PERCENT
                    lifecycleScope.launch { settings.setDownloadMinFreeStoragePercent(value) }
                }
            }
        )
    }

    private fun renderMinimumFreePercent(value: Int) {
        binding.textDownloadMinFreePercent.text =
            getString(R.string.settings_download_min_free_space, value)
    }

    private fun renderStorageCredentialStatus() {
        val configuration = CredentialStore(this).getAzureSasConfiguration()
        binding.textStorageCredentialStatus.text = if (configuration == null) {
            getString(R.string.settings_sas_not_configured)
        } else {
            getString(
                R.string.settings_sas_configured,
                configuration.accountHost,
                configuration.uploadContainer,
                configuration.downloadContainer,
                configuration.expiresUtc
            )
        }
        binding.buttonClearSas.isEnabled = configuration != null
    }

    private suspend fun renderUploadFolders() {
        val folderUris = AppContainer.getInstance(this).settingsRepository.getUploadFolderUris()
        val labels = folderUris.map { uriString ->
            runCatching {
                DocumentFile.fromTreeUri(this, android.net.Uri.parse(uriString))?.name
            }.getOrNull() ?: uriString
        }
        binding.textUploadFolders.text = if (labels.isEmpty()) {
            getString(R.string.settings_upload_folders_empty)
        } else {
            getString(R.string.settings_upload_folders_value, labels.joinToString("\n") { "• $it" })
        }
        binding.buttonClearUploadFolders.isEnabled = labels.isNotEmpty()
    }

    private suspend fun resetDiscoveryForNewStorage(container: AppContainer) {
        container.database.localTransferDao().deleteUnfinished()
        container.database.localFileDao().deleteAll()
    }

    private suspend fun resetUploadDiscovery(container: AppContainer) {
        container.database.localTransferDao().deleteUnfinished(TransferDirection.UPLOAD.name)
        container.database.localFileDao().deleteAll()
    }

    private suspend fun rescheduleCurrentSettings() {
        val settings = AppContainer.getInstance(this).settingsRepository
        WorkScheduler.reschedule(
            applicationContext,
            settings.getAppMode(),
            settings.isUploadWifiOnly(),
            settings.isUploadChargingOnly(),
            settings.isDownloadWifiOnly()
        )
    }

    private fun persistAndReschedule(update: suspend () -> Unit) {
        lifecycleScope.launch {
            update()
            val settings = AppContainer.getInstance(this@SettingsActivity).settingsRepository
            WorkScheduler.reschedule(
                applicationContext,
                settings.getAppMode(),
                settings.isUploadWifiOnly(),
                settings.isUploadChargingOnly(),
                settings.isDownloadWifiOnly()
            )
        }
    }
}
