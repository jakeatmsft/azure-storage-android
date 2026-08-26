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

package com.microsoft.azure.storage.photosync.app.data.local.entity

import androidx.room.Entity

/** Simple key/value application setting store (see spec section 4.5, 14). */
@Entity(tableName = "app_setting", primaryKeys = ["key"])
data class AppSetting(
    val key: String,
    val value: String
)

/** Well-known setting keys used by this app. */
object AppSettingKeys {
    const val DEVICE_ID = "device_id"
    const val DEVICE_NAME = "device_name"
    const val APP_MODE = "app_mode" // AppMode.name: UPLOAD_ONLY, DOWNLOAD_ONLY, UPLOAD_AND_DOWNLOAD, PAUSED
    const val LAST_ACTIVE_APP_MODE = "last_active_app_mode"
    const val UPLOAD_WIFI_ONLY = "upload_wifi_only"
    const val UPLOAD_CHARGING_ONLY = "upload_charging_only"
    const val UPLOAD_INCLUDE_VIDEOS = "upload_include_videos"
    const val UPLOAD_CALCULATE_CHECKSUM = "upload_calculate_checksum"
    const val UPLOAD_FOLDER_URIS = "upload_folder_uris" // newline-separated persisted SAF tree URIs
    const val DOWNLOAD_DESTINATION_URI = "download_destination_uri"
    const val DOWNLOAD_WIFI_ONLY = "download_wifi_only"
    const val DOWNLOAD_OVERWRITE_ON_CONFLICT = "download_overwrite_on_conflict"
    const val DOWNLOAD_MIN_FREE_STORAGE_PERCENT = "download_min_free_storage_percent"
    const val LAST_SUCCESSFUL_SYNC_UTC = "last_successful_sync_utc"
    const val API_BASE_URL = "api_base_url"
}

/** Operating mode, per spec section 3.1. */
enum class AppMode {
    UPLOAD_ONLY,
    DOWNLOAD_ONLY,
    UPLOAD_AND_DOWNLOAD,
    PAUSED
}
