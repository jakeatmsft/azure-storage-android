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

import java.security.MessageDigest

/**
 * Computes the deterministic duplicate-prevention keys described in spec
 * section 7 ("Transfer Identity and Duplicate Prevention").
 */
object TransferIdentity {

    /**
     * Upload identity key: SHA256(deviceId + mediaId + fileSize + modifiedUtc).
     *
     * If a completed upload transfer already exists for this key, the caller
     * must skip the upload (see [isDuplicateUpload]).
     *
     * @param modifiedUtcEpochMillis last-modified timestamp of the source
     *   media file, in UTC epoch milliseconds.
     */
    fun uploadKey(
        deviceId: String,
        mediaId: String,
        fileSize: Long,
        modifiedUtcEpochMillis: Long
    ): String {
        val raw = "$deviceId|$mediaId|$fileSize|$modifiedUtcEpochMillis"
        return sha256Hex(raw)
    }

    /**
     * True if a completed upload transfer with this identity should cause the
     * current candidate to be skipped rather than re-uploaded.
     */
    fun isDuplicateUpload(existingCompletedKeys: Set<String>, candidateKey: String): Boolean =
        existingCompletedKeys.contains(candidateKey)

    /**
     * Download identity, per spec section 7.2: a download is complete only
     * when a successful record matches device, container, blob name, blob
     * ETag/version, expected size, and status. A filename and size match
     * alone is never sufficient proof of a prior download.
     */
    data class DownloadIdentity(
        val deviceId: String,
        val blobContainer: String,
        val blobName: String,
        val blobEtagOrVersion: String,
        val expectedFileSize: Long
    ) {
        fun key(): String = sha256Hex(
            "$deviceId|$blobContainer|$blobName|$blobEtagOrVersion|$expectedFileSize"
        )
    }

    /**
     * True if [candidate] has already been successfully downloaded by the
     * device, based on the set of identity keys of transfers previously
     * recorded as [TransferState.COMPLETED]. A changed blob ETag/version
     * yields a different key and is therefore treated as new content, even
     * if the filename and size are unchanged.
     */
    fun isDuplicateDownload(
        completedDownloadKeys: Set<String>,
        candidate: DownloadIdentity
    ): Boolean = completedDownloadKeys.contains(candidate.key())

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
