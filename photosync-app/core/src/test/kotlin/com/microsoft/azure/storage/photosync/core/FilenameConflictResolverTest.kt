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
import org.junit.Test

class FilenameConflictResolverTest {

    @Test
    fun `uses original name when nothing exists`() {
        val result = FilenameConflictResolver.resolve(
            desiredName = "photo.jpg",
            existingFileExists = { false },
            existingFileMatches = { false }
        )
        assertEquals(FilenameConflictResolver.Resolution.UseOriginal("photo.jpg"), result)
    }

    @Test
    fun `skips when an identical completed file already exists`() {
        val result = FilenameConflictResolver.resolve(
            desiredName = "photo.jpg",
            existingFileExists = { it == "photo.jpg" },
            existingFileMatches = { it == "photo.jpg" }
        )
        assertEquals(FilenameConflictResolver.Resolution.SkipIdenticalExisting, result)
    }

    @Test
    fun `generates alternate name when content differs and overwrite is disabled`() {
        val existing = mutableSetOf("photo.jpg")
        val result = FilenameConflictResolver.resolve(
            desiredName = "photo.jpg",
            existingFileExists = { existing.contains(it) },
            existingFileMatches = { false }
        )
        assertEquals(FilenameConflictResolver.Resolution.UseAlternateName("photo (1).jpg"), result)
    }

    @Test
    fun `skips subsequent numbered name if it matches instead`() {
        val existing = setOf("photo.jpg", "photo (1).jpg")
        val result = FilenameConflictResolver.resolve(
            desiredName = "photo.jpg",
            existingFileExists = { existing.contains(it) },
            existingFileMatches = { it == "photo (1).jpg" }
        )
        assertEquals(FilenameConflictResolver.Resolution.SkipIdenticalExisting, result)
    }

    @Test
    fun `overwrite resolution used only when explicitly enabled`() {
        val result = FilenameConflictResolver.resolve(
            desiredName = "photo.jpg",
            existingFileExists = { it == "photo.jpg" },
            existingFileMatches = { false },
            allowOverwrite = true
        )
        assertEquals(FilenameConflictResolver.Resolution.Overwrite("photo.jpg"), result)
    }

    @Test
    fun `handles filenames without an extension`() {
        val result = FilenameConflictResolver.resolve(
            desiredName = "IMG_001",
            existingFileExists = { it == "IMG_001" },
            existingFileMatches = { false }
        )
        assertEquals(FilenameConflictResolver.Resolution.UseAlternateName("IMG_001 (1)"), result)
    }
}
