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

package com.microsoft.azure.storage.photosync.core

import kotlin.math.ceil

/**
 * Decides whether a download can start without taking the destination volume
 * below the user's minimum-free-space percentage.
 *
 * The projected free space is checked before every file. This means the file
 * at the head of the persistent queue stays queued when the threshold is hit,
 * and is the first file reconsidered after the user frees storage.
 */
object DownloadStoragePolicy {

    const val DEFAULT_MIN_FREE_PERCENT = 10
    const val MIN_FREE_PERCENT = 1
    const val MAX_FREE_PERCENT = 99

    data class Snapshot(
        val totalBytes: Long,
        val availableBytes: Long
    ) {
        init {
            require(totalBytes > 0) { "totalBytes must be positive" }
            require(availableBytes >= 0) { "availableBytes cannot be negative" }
        }

        val availablePercent: Double
            get() = availableBytes.toDouble() * 100.0 / totalBytes.toDouble()
    }

    fun canDownload(
        snapshot: Snapshot,
        nextFileSizeBytes: Long,
        minimumFreePercent: Int
    ): Boolean {
        require(nextFileSizeBytes >= 0) { "nextFileSizeBytes cannot be negative" }
        require(minimumFreePercent in MIN_FREE_PERCENT..MAX_FREE_PERCENT) {
            "minimumFreePercent must be between $MIN_FREE_PERCENT and $MAX_FREE_PERCENT"
        }

        if (nextFileSizeBytes > snapshot.availableBytes) return false

        val requiredFreeBytes = ceil(
            snapshot.totalBytes.toDouble() * minimumFreePercent.toDouble() / 100.0
        ).toLong()
        val projectedFreeBytes = snapshot.availableBytes - nextFileSizeBytes
        return projectedFreeBytes >= requiredFreeBytes
    }
}
