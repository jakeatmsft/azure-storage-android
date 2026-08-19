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
 * A completion (or error/heartbeat) request that was accepted locally but
 * has not yet been confirmed as durably applied by the Azure API. Retried by
 * StateUpdateWorker independent of re-transferring content (spec 11.3).
 */
@Entity(tableName = "pending_state_update")
data class PendingStateUpdate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val transferId: String,
    /** "complete", "fail", "heartbeat", or "cancel". */
    val updateType: String,
    /** JSON-encoded request body appropriate for [updateType]. */
    val payloadJson: String,
    val idempotencyKey: String,
    val attemptCount: Int = 0,
    val createdUtcEpochMillis: Long = System.currentTimeMillis(),
    val nextRetryUtcEpochMillis: Long? = null,
    val lastErrorMessage: String? = null
)
