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

package com.microsoft.azure.storage.photosync.app.data.remote

import com.microsoft.azure.storage.StorageCredentialsSharedAccessSignature
import com.microsoft.azure.storage.blob.BlobListingDetails
import com.microsoft.azure.storage.blob.CloudBlob
import com.microsoft.azure.storage.blob.CloudBlobContainer
import com.microsoft.azure.storage.blob.CloudBlockBlob
import com.microsoft.azure.storage.photosync.core.AzureSasConfiguration
import java.net.URI
import java.util.EnumSet

data class DirectBlobItem(
    val blobName: String,
    val fileName: String,
    val fileSize: Long,
    val contentType: String,
    val etag: String,
    val sha256: String?
)

/** Azure Blob SDK adapter that never logs or persists a SAS URL. */
class AzureSasBlobClient(private val configuration: AzureSasConfiguration) {

    private val credentials = StorageCredentialsSharedAccessSignature(configuration.sasToken)

    fun uploadBlob(blobName: String): CloudBlockBlob =
        container(configuration.uploadContainer).getBlockBlobReference(blobName)

    fun downloadBlob(blobName: String): CloudBlockBlob =
        container(configuration.downloadContainer).getBlockBlobReference(blobName)

    fun downloadBlobSasUrl(blobName: String): String =
        downloadBlob(blobName).uri.toASCIIString() + "?" + configuration.sasToken

    fun listDownloadBlobs(): List<DirectBlobItem> =
        container(configuration.downloadContainer)
            .listBlobs(null, true, EnumSet.of(BlobListingDetails.METADATA), null, null)
            .filterIsInstance<CloudBlob>()
            .filterNot { it.name.endsWith('/') }
            .map { blob ->
                val properties = blob.properties
                DirectBlobItem(
                    blobName = blob.name,
                    fileName = blob.name.substringAfterLast('/'),
                    fileSize = properties.length,
                    contentType = properties.contentType ?: "application/octet-stream",
                    etag = properties.etag
                        ?: "length:${properties.length}:modified:${properties.lastModified?.time ?: 0L}",
                    sha256 = blob.metadata[SHA256_METADATA_KEY]
                )
            }
            .sortedBy { it.blobName }

    private fun container(name: String): CloudBlobContainer =
        CloudBlobContainer(URI.create("${configuration.blobServiceUrl}/$name"), credentials)

    companion object {
        const val SHA256_METADATA_KEY = "photosync_sha256"
    }
}
