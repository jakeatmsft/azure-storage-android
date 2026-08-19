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

import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalFileDao
import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalTransferDao
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalFile
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import com.microsoft.azure.storage.photosync.core.TransferDirection
import com.microsoft.azure.storage.photosync.core.TransferIdentity
import com.microsoft.azure.storage.photosync.core.TransferState
import java.util.UUID

/**
 * Turns MediaStore discoveries into local `LocalFile` / `LocalTransfer`
 * (queued upload) rows, applying the duplicate-prevention rule of spec
 * section 7.1: if a completed upload transfer already exists for a
 * candidate's identity key, the candidate is skipped rather than queued
 * again.
 */
class DiscoveryRepository(
    private val localFileDao: LocalFileDao,
    private val localTransferDao: LocalTransferDao
) {

    /** Returns the number of new transfers queued. */
    suspend fun recordDiscoveries(deviceId: String, candidates: List<MediaCandidate>): Int {
        if (candidates.isEmpty()) return 0

        val completedKeys = localTransferDao.completedIdentityKeys(TransferDirection.UPLOAD.name).toSet()
        var queuedCount = 0

        for (candidate in candidates) {
            val identityKey = TransferIdentity.uploadKey(
                deviceId = deviceId,
                mediaId = candidate.mediaId,
                fileSize = candidate.fileSize,
                modifiedUtcEpochMillis = candidate.dateModifiedUtcEpochMillis
            )

            localFileDao.insert(
                LocalFile(
                    mediaId = candidate.mediaId,
                    displayName = candidate.displayName,
                    filePath = candidate.contentUri,
                    mimeType = candidate.mimeType,
                    fileSize = candidate.fileSize,
                    capturedUtcEpochMillis = candidate.dateTakenUtcEpochMillis,
                    modifiedUtcEpochMillis = candidate.dateModifiedUtcEpochMillis,
                    uploadIdentityKey = identityKey
                )
            )

            if (TransferIdentity.isDuplicateUpload(completedKeys, identityKey)) {
                continue // already uploaded successfully; do not re-queue.
            }
            if (localTransferDao.findByIdentityKey(identityKey) != null) {
                continue // already discovered/queued in a previous scan.
            }

            localTransferDao.insert(
                LocalTransfer(
                    transferId = UUID.randomUUID().toString(),
                    identityKey = identityKey,
                    direction = TransferDirection.UPLOAD.name,
                    fileId = null,
                    localMediaId = candidate.mediaId,
                    fileName = candidate.displayName,
                    fileSize = candidate.fileSize,
                    contentType = candidate.mimeType,
                    blobContainer = null,
                    blobName = null,
                    blobEtagOrVersion = null,
                    sha256 = null,
                    destinationUri = null,
                    state = TransferState.QUEUED.name
                )
            )
            queuedCount++
        }
        return queuedCount
    }
}
