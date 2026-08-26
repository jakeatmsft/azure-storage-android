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

package com.microsoft.azure.storage.photosync.app.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.microsoft.azure.storage.photosync.core.DownloadStoragePolicy
import java.io.File

/** Reports capacity for the physical volume that owns a SAF destination. */
class DestinationStorage(private val context: Context) {

    fun snapshot(destinationTreeUri: Uri): DownloadStoragePolicy.Snapshot {
        val volumePath = resolveVolumePath(destinationTreeUri) ?: context.filesDir
        val statFs = StatFs(nearestExistingPath(volumePath).absolutePath)
        return DownloadStoragePolicy.Snapshot(
            totalBytes = statFs.totalBytes,
            availableBytes = statFs.availableBytes
        )
    }

    private fun resolveVolumePath(destinationTreeUri: Uri): File? {
        if (destinationTreeUri.scheme == "file") {
            return destinationTreeUri.path?.let(::File)
        }

        if (destinationTreeUri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null

        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(destinationTreeUri)
        }.getOrNull() ?: return null
        val volumeId = documentId.substringBefore(':')

        if (volumeId.equals(PRIMARY_VOLUME_ID, ignoreCase = true)) {
            return Environment.getExternalStorageDirectory()
        }

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return context.getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { directory ->
                storageManager.getStorageVolume(directory)?.uuid.equals(volumeId, ignoreCase = true)
            }
    }

    private fun nearestExistingPath(file: File): File =
        generateSequence(file) { it.parentFile }.firstOrNull { it.exists() } ?: context.filesDir

    companion object {
        private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
            "com.android.externalstorage.documents"
        private const val PRIMARY_VOLUME_ID = "primary"
    }
}
