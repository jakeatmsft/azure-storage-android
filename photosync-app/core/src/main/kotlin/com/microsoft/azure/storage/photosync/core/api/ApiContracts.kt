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

package com.microsoft.azure.storage.photosync.core.api

/**
 * Plain data-class contracts for the Azure API described in spec section 10.
 * Kept dependency-free (no JSON/Retrofit annotations) in the [core] module so
 * they can be reused by the Android app's Retrofit client and by any future
 * server-side implementation, and so the shapes are covered by plain JVM
 * unit tests. The app module's networking layer maps these to/from JSON.
 */

data class DeviceRegistrationRequest(
    val deviceId: String,
    val deviceName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String
)

data class DeviceRegistrationResponse(
    val deviceId: String,
    val isEnabled: Boolean,
    val uploadEnabled: Boolean,
    val downloadEnabled: Boolean,
    val downloadGroup: String?,
    val credentialVersion: Int
)

data class DeviceConfiguration(
    val uploadEnabled: Boolean,
    val downloadEnabled: Boolean,
    val downloadGroup: String?,
    val isEnabled: Boolean
)

data class CreateUploadTransferRequest(
    val deviceId: String,
    val mediaId: String,
    val fileName: String,
    val fileSize: Long,
    val contentType: String,
    val capturedUtcEpochMillis: Long?,
    val modifiedUtcEpochMillis: Long,
    val sha256: String?,
    val idempotencyKey: String
)

data class CreateUploadTransferResponse(
    val transferId: String,
    val fileId: String,
    val blobContainer: String,
    val blobName: String,
    val sasUrl: String,
    val expiresUtcEpochMillis: Long,
    val requiredHeaders: Map<String, String> = emptyMap()
)

data class CompleteUploadTransferRequest(
    val transferId: String,
    val bytesTransferred: Long,
    val sha256: String?,
    val idempotencyKey: String
)

data class DownloadManifestItem(
    val fileId: String,
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val contentType: String,
    val blobContainer: String,
    val blobName: String,
    val blobEtagOrVersion: String,
    val sha256: String?,
    val sasUrl: String,
    val expiresUtcEpochMillis: Long
)

data class DownloadManifestResponse(
    val items: List<DownloadManifestItem>
)

data class CompleteDownloadTransferRequest(
    val transferId: String,
    val bytesTransferred: Long,
    val finalFileName: String,
    val sha256: String?,
    val idempotencyKey: String
)

data class TransferCompleteResponse(
    val transferId: String,
    val state: String,
    val completedUtcEpochMillis: Long?
)

data class TransferErrorRequest(
    val errorCategory: String,
    val errorCode: String,
    val errorMessage: String
)
