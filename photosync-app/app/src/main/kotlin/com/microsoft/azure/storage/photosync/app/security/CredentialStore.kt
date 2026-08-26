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

package com.microsoft.azure.storage.photosync.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.microsoft.azure.storage.photosync.core.AzureSasConfiguration

/**
 * Stores the device's revocable API credential using Android
 * Keystore-backed encrypted storage (spec section 5.2: "Store tokens using
 * Android Keystore-backed encrypted storage", "Never store credentials in
 * plain-text preferences").
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    fun setToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    fun getAzureSasConfiguration(): AzureSasConfiguration? {
        val blobServiceUrl = prefs.getString(KEY_BLOB_SERVICE_URL, null) ?: return null
        val uploadContainer = prefs.getString(KEY_UPLOAD_CONTAINER, null) ?: return null
        val downloadContainer = prefs.getString(KEY_DOWNLOAD_CONTAINER, null) ?: return null
        val sasToken = prefs.getString(KEY_AZURE_SAS_TOKEN, null) ?: return null
        val expiresUtc = prefs.getString(KEY_AZURE_SAS_EXPIRY, null) ?: return null
        return AzureSasConfiguration(
            blobServiceUrl = blobServiceUrl,
            uploadContainer = uploadContainer,
            downloadContainer = downloadContainer,
            sasToken = sasToken,
            expiresUtc = expiresUtc
        )
    }

    fun setAzureSasConfiguration(configuration: AzureSasConfiguration) {
        prefs.edit()
            .putString(KEY_BLOB_SERVICE_URL, configuration.blobServiceUrl)
            .putString(KEY_UPLOAD_CONTAINER, configuration.uploadContainer)
            .putString(KEY_DOWNLOAD_CONTAINER, configuration.downloadContainer)
            .putString(KEY_AZURE_SAS_TOKEN, configuration.sasToken)
            .putString(KEY_AZURE_SAS_EXPIRY, configuration.expiresUtc)
            .apply()
    }

    fun clearAzureSasConfiguration() {
        prefs.edit()
            .remove(KEY_BLOB_SERVICE_URL)
            .remove(KEY_UPLOAD_CONTAINER)
            .remove(KEY_DOWNLOAD_CONTAINER)
            .remove(KEY_AZURE_SAS_TOKEN)
            .remove(KEY_AZURE_SAS_EXPIRY)
            .apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "photosync_secure_prefs"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_BLOB_SERVICE_URL = "blob_service_url"
        private const val KEY_UPLOAD_CONTAINER = "upload_container"
        private const val KEY_DOWNLOAD_CONTAINER = "download_container"
        private const val KEY_AZURE_SAS_TOKEN = "azure_sas_token"
        private const val KEY_AZURE_SAS_EXPIRY = "azure_sas_expiry"
    }
}
