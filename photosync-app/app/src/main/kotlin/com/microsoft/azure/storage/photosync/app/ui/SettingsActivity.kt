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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.WorkScheduler
import com.microsoft.azure.storage.photosync.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * Settings screen (spec section 14): enabled folders, Wi-Fi/charging
 * toggles, checksum calculation, and the SAF-selected download destination.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val chooseDestination = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val settings = AppContainer.getInstance(this).settingsRepository

        lifecycleScope.launch {
            binding.checkUploadWifiOnly.isChecked = settings.isUploadWifiOnly()
            binding.checkUploadChargingOnly.isChecked = settings.isUploadChargingOnly()
            binding.checkUploadIncludeVideos.isChecked = settings.isUploadIncludeVideos()
            binding.checkUploadChecksum.isChecked = settings.isUploadChecksumEnabled()
            binding.checkDownloadWifiOnly.isChecked = settings.isDownloadWifiOnly()
            binding.checkDownloadOverwrite.isChecked = settings.isDownloadOverwriteOnConflict()
            binding.textDestination.text = settings.getDownloadDestinationUri() ?: "Not selected"
        }

        binding.buttonChooseDestination.setOnClickListener { chooseDestination.launch(null) }

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
    }

    private fun persistAndReschedule(update: suspend () -> Unit) {
        lifecycleScope.launch {
            update()
            val settings = AppContainer.getInstance(this@SettingsActivity).settingsRepository
            WorkScheduler.reschedule(
                applicationContext,
                settings.getAppMode(),
                settings.isUploadWifiOnly(),
                settings.isDownloadWifiOnly()
            )
        }
    }
}
