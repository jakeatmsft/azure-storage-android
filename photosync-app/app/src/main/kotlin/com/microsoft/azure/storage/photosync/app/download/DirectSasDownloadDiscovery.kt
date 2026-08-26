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

package com.microsoft.azure.storage.photosync.app.download

import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalTransferDao
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import com.microsoft.azure.storage.photosync.app.data.remote.AzureSasBlobClient
import com.microsoft.azure.storage.photosync.core.AzureSasConfiguration
import com.microsoft.azure.storage.photosync.core.TransferDirection
import com.microsoft.azure.storage.photosync.core.TransferIdentity
import com.microsoft.azure.storage.photosync.core.TransferState
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Lists the download container and adds previously unseen blob versions to Room. */
class DirectSasDownloadDiscovery(
    private val configuration: AzureSasConfiguration,
    private val transferDao: LocalTransferDao
) {
    private val blobClient = AzureSasBlobClient(configuration)

    suspend fun discover(deviceId: String): Int {
        val completedKeys = transferDao.completedIdentityKeys(TransferDirection.DOWNLOAD.name).toSet()
        val discoveredAt = System.currentTimeMillis()
        var queued = 0

        for ((position, item) in blobClient.listDownloadBlobs().withIndex()) {
            val storageScope = "${configuration.accountHost}/${configuration.downloadContainer}"
            val identity = TransferIdentity.DownloadIdentity(
                deviceId = deviceId,
                blobContainer = storageScope,
                blobName = item.blobName,
                blobEtagOrVersion = item.etag,
                expectedFileSize = item.fileSize
            )
            val identityKey = identity.key()
            if (TransferIdentity.isDuplicateDownload(completedKeys, identity)) continue
            if (transferDao.findByIdentityKey(identityKey) != null) continue

            val transferId = UUID.nameUUIDFromBytes(
                "download|$identityKey".toByteArray(StandardCharsets.UTF_8)
            ).toString()
            transferDao.insert(
                LocalTransfer(
                    transferId = transferId,
                    identityKey = identityKey,
                    direction = TransferDirection.DOWNLOAD.name,
                    fileId = item.blobName,
                    localMediaId = null,
                    fileName = item.fileName,
                    fileSize = item.fileSize,
                    contentType = item.contentType,
                    blobContainer = storageScope,
                    blobName = item.blobName,
                    blobEtagOrVersion = item.etag,
                    sha256 = item.sha256,
                    destinationUri = null,
                    state = TransferState.QUEUED.name,
                    createdUtcEpochMillis = discoveredAt + position
                )
            )
            queued++
        }
        return queued
    }
}
