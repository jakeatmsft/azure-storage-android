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
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of a TransferState record (spec section 8.2), scoped to
 * transfers initiated by this device. Drives WorkManager processing and
 * survives process death / device reboot so queued work can be restored.
 */
@Entity(
    tableName = "local_transfer",
    indices = [
        Index(value = ["identityKey"], unique = true),
        Index(value = ["direction", "state"])
    ]
)
data class LocalTransfer(
    @PrimaryKey
    val transferId: String,
    /** Upload identity key or download identity key from :core TransferIdentity. */
    val identityKey: String,
    val direction: String, // TransferDirection.name
    val fileId: String?,
    val localMediaId: String?,
    val fileName: String,
    val fileSize: Long,
    val contentType: String,
    val blobContainer: String?,
    val blobName: String?,
    val blobEtagOrVersion: String?,
    val sha256: String?,
    val destinationUri: String?,
    val state: String, // TransferState.name
    val bytesTransferred: Long = 0,
    val attemptCount: Int = 0,
    val createdUtcEpochMillis: Long = System.currentTimeMillis(),
    val startedUtcEpochMillis: Long? = null,
    val lastHeartbeatUtcEpochMillis: Long? = null,
    val completedUtcEpochMillis: Long? = null,
    val nextRetryUtcEpochMillis: Long? = null,
    val lastErrorCategory: String? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val correlationId: String? = null,
    val isRetryable: Boolean = true
)
