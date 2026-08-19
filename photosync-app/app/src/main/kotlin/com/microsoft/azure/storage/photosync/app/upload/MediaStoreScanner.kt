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

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

/** A single candidate discovered on-device via MediaStore. */
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
 * Discovers photos (and, optionally, videos) via [MediaStore.Images] /
 * [MediaStore.Video], per spec sections 3.2 and 15 ("incremental
 * MediaStore queries", "avoid full-device filesystem scans").
 *
 * Default supported image types are JPEG, PNG, WebP and HEIC; videos are
 * opt-in and disabled by default.
 */
class MediaStoreScanner(private val context: Context) {

    /**
     * Returns media items with `date_modified` strictly after
     * [sinceUtcEpochSeconds] (MediaStore stores this column in seconds),
     * restricted to the camera folder and any [additionalRelativePaths].
     * Supports incremental scanning: pass the last known max modified time
     * to avoid re-scanning the whole device.
     */
    fun scan(
        sinceUtcEpochSeconds: Long,
        additionalRelativePaths: List<String>,
        includeVideos: Boolean
    ): List<MediaCandidate> {
        val results = mutableListOf<MediaCandidate>()
        results += queryCollection(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            sinceUtcEpochSeconds,
            SUPPORTED_IMAGE_MIME_TYPES
        )
        if (includeVideos) {
            results += queryCollection(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                sinceUtcEpochSeconds,
                SUPPORTED_VIDEO_MIME_TYPES
            )
        }

        val allowedPaths = (listOf(CAMERA_RELATIVE_PATH) + additionalRelativePaths)
            .map { it.trimEnd('/') + "/" }

        return results.filter { candidate ->
            allowedPaths.any { allowed -> candidate.relativePath.startsWith(allowed) }
        }
    }

    private fun queryCollection(
        collection: Uri,
        sinceUtcEpochSeconds: Long,
        mimeTypes: List<String>
    ): List<MediaCandidate> {
        if (mimeTypes.isEmpty()) return emptyList()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN
        )
        val mimePlaceholders = mimeTypes.joinToString(",") { "?" }
        val selection = "${MediaStore.MediaColumns.DATE_MODIFIED} > ? AND " +
            "${MediaStore.MediaColumns.MIME_TYPE} IN ($mimePlaceholders)"
        val selectionArgs = arrayOf(sinceUtcEpochSeconds.toString(), *mimeTypes.toTypedArray())
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} ASC"

        val candidates = mutableListOf<MediaCandidate>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                candidates += cursor.toMediaCandidate(collection)
            }
        }
        return candidates
    }

    private fun Cursor.toMediaCandidate(collection: Uri): MediaCandidate {
        val id = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        val dateModifiedSeconds = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED))
        val dateAddedSeconds = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
        val dateTakenIndex = getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val dateTakenMillis = if (dateTakenIndex >= 0 && !isNull(dateTakenIndex)) getLong(dateTakenIndex) else null
        return MediaCandidate(
            mediaId = id.toString(),
            displayName = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
            relativePath = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) ?: "",
            mimeType = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: "application/octet-stream",
            fileSize = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
            dateAddedUtcEpochMillis = dateAddedSeconds * 1000,
            dateModifiedUtcEpochMillis = dateModifiedSeconds * 1000,
            dateTakenUtcEpochMillis = dateTakenMillis,
            contentUri = ContentUris.withAppendedId(collection, id).toString()
        )
    }

    companion object {
        const val CAMERA_RELATIVE_PATH = "DCIM/Camera"

        val SUPPORTED_IMAGE_MIME_TYPES: List<String> = listOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif"
        )

        // Videos are an optional feature and disabled by default (spec 3.2).
        val SUPPORTED_VIDEO_MIME_TYPES: List<String> = listOf(
            "video/mp4",
            "video/3gpp"
        )
    }
}
