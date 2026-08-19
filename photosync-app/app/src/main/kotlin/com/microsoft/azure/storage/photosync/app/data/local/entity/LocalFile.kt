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
import androidx.room.PrimaryKey

/**
 * A photo/video discovered on the device via MediaStore. One row per
 * MediaStore media item that is a candidate for upload.
 */
@Entity(tableName = "local_file")
data class LocalFile(
    @PrimaryKey
    val mediaId: String,
    val displayName: String,
    val filePath: String,
    val mimeType: String,
    val fileSize: Long,
    val capturedUtcEpochMillis: Long?,
    val modifiedUtcEpochMillis: Long,
    /** SHA256(deviceId + mediaId + fileSize + modifiedUtc); see TransferIdentity in :core. */
    val uploadIdentityKey: String,
    val sha256: String? = null,
    val discoveredUtcEpochMillis: Long = System.currentTimeMillis()
)
