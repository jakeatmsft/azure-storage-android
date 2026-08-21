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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferIdentityTest {

    @Test
    fun `upload key is deterministic for identical inputs`() {
        val key1 = TransferIdentity.uploadKey("device-1", "media-1", 1024L, 1_700_000_000_000L)
        val key2 = TransferIdentity.uploadKey("device-1", "media-1", 1024L, 1_700_000_000_000L)
        assertEquals(key1, key2)
    }

    @Test
    fun `upload key changes when any input changes`() {
        val base = TransferIdentity.uploadKey("device-1", "media-1", 1024L, 1_700_000_000_000L)
        assertNotEquals(base, TransferIdentity.uploadKey("device-2", "media-1", 1024L, 1_700_000_000_000L))
        assertNotEquals(base, TransferIdentity.uploadKey("device-1", "media-2", 1024L, 1_700_000_000_000L))
        assertNotEquals(base, TransferIdentity.uploadKey("device-1", "media-1", 2048L, 1_700_000_000_000L))
        assertNotEquals(base, TransferIdentity.uploadKey("device-1", "media-1", 1024L, 1_700_000_000_001L))
    }

    @Test
    fun `duplicate upload is detected via completed key set`() {
        val key = TransferIdentity.uploadKey("device-1", "media-1", 1024L, 1_700_000_000_000L)
        assertTrue(TransferIdentity.isDuplicateUpload(setOf(key), key))
        assertFalse(TransferIdentity.isDuplicateUpload(emptySet(), key))
    }

    @Test
    fun `download identity treats changed etag as new content`() {
        val v1 = TransferIdentity.DownloadIdentity("device-1", "device-downloads", "photo.jpg", "etag-v1", 2048L)
        val v2 = v1.copy(blobEtagOrVersion = "etag-v2")

        val completed = setOf(v1.key())

        assertTrue(TransferIdentity.isDuplicateDownload(completed, v1))
        assertFalse(TransferIdentity.isDuplicateDownload(completed, v2))
    }

    @Test
    fun `download identity requires exact match on all fields, not just name and size`() {
        val original = TransferIdentity.DownloadIdentity("device-1", "device-downloads", "photo.jpg", "etag-v1", 2048L)
        val differentContainer = original.copy(blobContainer = "other-container")
        val differentDevice = original.copy(deviceId = "device-2")

        val completed = setOf(original.key())

        assertFalse(TransferIdentity.isDuplicateDownload(completed, differentContainer))
        assertFalse(TransferIdentity.isDuplicateDownload(completed, differentDevice))
    }
}
