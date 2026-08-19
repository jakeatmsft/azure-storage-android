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

package com.microsoft.azure.storage.photosync.app.data.local

import com.microsoft.azure.storage.photosync.app.data.local.dao.AppSettingDao
import com.microsoft.azure.storage.photosync.app.data.local.entity.AppMode
import com.microsoft.azure.storage.photosync.app.data.local.entity.AppSetting
import com.microsoft.azure.storage.photosync.app.data.local.entity.AppSettingKeys
import java.util.UUID

/**
 * Typed wrapper around [AppSettingDao] for the settings surfaced in spec
 * section 14 (Dashboard/Settings screens) plus the stable, generated device
 * identifier used throughout the transfer-identity scheme (section 7).
 */
class SettingsRepository(private val dao: AppSettingDao) {

    suspend fun getOrCreateDeviceId(): String {
        dao.get(AppSettingKeys.DEVICE_ID)?.let { return it }
        val newId = UUID.randomUUID().toString()
        dao.upsert(AppSetting(AppSettingKeys.DEVICE_ID, newId))
        return newId
    }

    suspend fun getDeviceName(): String = dao.get(AppSettingKeys.DEVICE_NAME) ?: android.os.Build.MODEL

    suspend fun setDeviceName(name: String) = dao.upsert(AppSetting(AppSettingKeys.DEVICE_NAME, name))

    suspend fun getAppMode(): AppMode =
        dao.get(AppSettingKeys.APP_MODE)?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }
            ?: AppMode.UPLOAD_AND_DOWNLOAD

    suspend fun setAppMode(mode: AppMode) = dao.upsert(AppSetting(AppSettingKeys.APP_MODE, mode.name))

    suspend fun isUploadWifiOnly(): Boolean = getBoolean(AppSettingKeys.UPLOAD_WIFI_ONLY, default = false)
    suspend fun setUploadWifiOnly(value: Boolean) = setBoolean(AppSettingKeys.UPLOAD_WIFI_ONLY, value)

    suspend fun isUploadChargingOnly(): Boolean = getBoolean(AppSettingKeys.UPLOAD_CHARGING_ONLY, default = false)
    suspend fun setUploadChargingOnly(value: Boolean) = setBoolean(AppSettingKeys.UPLOAD_CHARGING_ONLY, value)

    suspend fun isUploadIncludeVideos(): Boolean = getBoolean(AppSettingKeys.UPLOAD_INCLUDE_VIDEOS, default = false)
    suspend fun setUploadIncludeVideos(value: Boolean) = setBoolean(AppSettingKeys.UPLOAD_INCLUDE_VIDEOS, value)

    suspend fun isUploadChecksumEnabled(): Boolean = getBoolean(AppSettingKeys.UPLOAD_CALCULATE_CHECKSUM, default = true)
    suspend fun setUploadChecksumEnabled(value: Boolean) = setBoolean(AppSettingKeys.UPLOAD_CALCULATE_CHECKSUM, value)

    suspend fun getUploadAdditionalFolders(): List<String> =
        (dao.get(AppSettingKeys.UPLOAD_ADDITIONAL_FOLDERS) ?: "").split(",").filter { it.isNotBlank() }

    suspend fun setUploadAdditionalFolders(folders: List<String>) =
        dao.upsert(AppSetting(AppSettingKeys.UPLOAD_ADDITIONAL_FOLDERS, folders.joinToString(",")))

    suspend fun getDownloadDestinationUri(): String? = dao.get(AppSettingKeys.DOWNLOAD_DESTINATION_URI)

    suspend fun setDownloadDestinationUri(uri: String) =
        dao.upsert(AppSetting(AppSettingKeys.DOWNLOAD_DESTINATION_URI, uri))

    suspend fun isDownloadWifiOnly(): Boolean = getBoolean(AppSettingKeys.DOWNLOAD_WIFI_ONLY, default = false)
    suspend fun setDownloadWifiOnly(value: Boolean) = setBoolean(AppSettingKeys.DOWNLOAD_WIFI_ONLY, value)

    suspend fun isDownloadOverwriteOnConflict(): Boolean =
        getBoolean(AppSettingKeys.DOWNLOAD_OVERWRITE_ON_CONFLICT, default = false)

    suspend fun setDownloadOverwriteOnConflict(value: Boolean) =
        setBoolean(AppSettingKeys.DOWNLOAD_OVERWRITE_ON_CONFLICT, value)

    suspend fun getApiBaseUrl(): String = dao.get(AppSettingKeys.API_BASE_URL) ?: ""
    suspend fun setApiBaseUrl(url: String) = dao.upsert(AppSetting(AppSettingKeys.API_BASE_URL, url))

    suspend fun getLastSuccessfulSyncUtcEpochMillis(): Long? =
        dao.get(AppSettingKeys.LAST_SUCCESSFUL_SYNC_UTC)?.toLongOrNull()

    suspend fun setLastSuccessfulSyncUtcEpochMillis(value: Long) =
        dao.upsert(AppSetting(AppSettingKeys.LAST_SUCCESSFUL_SYNC_UTC, value.toString()))

    private suspend fun getBoolean(key: String, default: Boolean): Boolean =
        dao.get(key)?.toBooleanStrictOrNull() ?: default

    private suspend fun setBoolean(key: String, value: Boolean) = dao.upsert(AppSetting(key, value.toString()))
}
