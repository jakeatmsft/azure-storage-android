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

package com.microsoft.azure.storage.photosync.app.upload

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.ArrayDeque

/** A single candidate discovered in a user-selected folder. */
data class MediaCandidate(
    val mediaId: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val fileSize: Long,
    val dateAddedUtcEpochMillis: Long,
    val dateModifiedUtcEpochMillis: Long,
    val dateTakenUtcEpochMillis: Long?,
    val contentUri: String
)

/**
 * Recursively discovers files under one or more Storage Access Framework
 * tree URIs selected by the user. Android persists read access to those
 * folders, so background scans do not need broad filesystem access.
 */
class MediaStoreScanner(private val context: Context) {

    /**
     * Returns all files visible below [folderTreeUris]. Re-enumeration is
     * intentional: the Room transfer identity ledger makes it idempotent and
     * also discovers files copied in with an old last-modified timestamp.
     */
    fun scan(
        folderTreeUris: List<String>,
        includeVideos: Boolean
    ): List<MediaCandidate> {
        val candidatesByUri = linkedMapOf<String, MediaCandidate>()
        for (treeUriString in folderTreeUris.distinct()) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: continue
            val pending = ArrayDeque<Pair<DocumentFile, String>>()
            pending.add(root to (root.name ?: ""))
            while (pending.isNotEmpty()) {
                val (document, relativePath) = pending.removeFirst()
                if (document.isDirectory) {
                    document.listFiles().forEach { child ->
                        val childPath = listOf(relativePath, child.name.orEmpty())
                            .filter(String::isNotBlank)
                            .joinToString("/")
                        pending.add(child to childPath)
                    }
                    continue
                }
                if (!document.isFile) continue

                val mimeType = document.type ?: "application/octet-stream"
                if (!includeVideos && mimeType.startsWith("video/")) continue
                val uriString = document.uri.toString()
                val modified = document.lastModified().coerceAtLeast(0L)
                candidatesByUri[uriString] = MediaCandidate(
                    mediaId = uriString,
                    displayName = document.name ?: "unnamed",
                    relativePath = relativePath,
                    mimeType = mimeType,
                    fileSize = document.length().coerceAtLeast(0L),
                    dateAddedUtcEpochMillis = modified,
                    dateModifiedUtcEpochMillis = modified,
                    dateTakenUtcEpochMillis = null,
                    contentUri = uriString
                )
            }
        }
        return candidatesByUri.values.sortedWith(
            compareBy<MediaCandidate> { it.dateModifiedUtcEpochMillis }.thenBy { it.contentUri }
        )
    }
}
