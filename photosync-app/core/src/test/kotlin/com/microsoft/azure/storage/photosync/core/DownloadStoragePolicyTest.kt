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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStoragePolicyTest {

    private val oneHundredGb = 100L * 1024 * 1024 * 1024

    @Test
    fun `download is allowed when projected free space remains above threshold`() {
        val snapshot = DownloadStoragePolicy.Snapshot(
            totalBytes = oneHundredGb,
            availableBytes = 20L * 1024 * 1024 * 1024
        )

        assertTrue(
            DownloadStoragePolicy.canDownload(
                snapshot = snapshot,
                nextFileSizeBytes = 5L * 1024 * 1024 * 1024,
                minimumFreePercent = 10
            )
        )
    }

    @Test
    fun `download is stopped before it would cross threshold`() {
        val snapshot = DownloadStoragePolicy.Snapshot(
            totalBytes = oneHundredGb,
            availableBytes = 12L * 1024 * 1024 * 1024
        )

        assertFalse(
            DownloadStoragePolicy.canDownload(
                snapshot = snapshot,
                nextFileSizeBytes = 3L * 1024 * 1024 * 1024,
                minimumFreePercent = 10
            )
        )
    }

    @Test
    fun `same queued file becomes eligible after storage is freed`() {
        val fileSize = 5L * 1024 * 1024 * 1024
        val beforeDeletion = DownloadStoragePolicy.Snapshot(
            totalBytes = oneHundredGb,
            availableBytes = 14L * 1024 * 1024 * 1024
        )
        val afterDeletion = beforeDeletion.copy(
            availableBytes = 18L * 1024 * 1024 * 1024
        )

        assertFalse(DownloadStoragePolicy.canDownload(beforeDeletion, fileSize, 10))
        assertTrue(DownloadStoragePolicy.canDownload(afterDeletion, fileSize, 10))
    }

    @Test
    fun `download is allowed when projected free space equals threshold`() {
        val snapshot = DownloadStoragePolicy.Snapshot(
            totalBytes = 1_000,
            availableBytes = 150
        )

        assertTrue(DownloadStoragePolicy.canDownload(snapshot, nextFileSizeBytes = 50, minimumFreePercent = 10))
    }
}
