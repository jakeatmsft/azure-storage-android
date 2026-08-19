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

import com.microsoft.azure.storage.photosync.core.api.CompleteDownloadTransferRequest
import com.microsoft.azure.storage.photosync.core.api.CompleteUploadTransferRequest
import com.microsoft.azure.storage.photosync.core.api.CreateUploadTransferRequest
import com.microsoft.azure.storage.photosync.core.api.CreateUploadTransferResponse
import com.microsoft.azure.storage.photosync.core.api.DeviceConfiguration
import com.microsoft.azure.storage.photosync.core.api.DeviceRegistrationRequest
import com.microsoft.azure.storage.photosync.core.api.DeviceRegistrationResponse
import com.microsoft.azure.storage.photosync.core.api.DownloadManifestResponse
import com.microsoft.azure.storage.photosync.core.api.TransferCompleteResponse
import com.microsoft.azure.storage.photosync.core.api.TransferErrorRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit contract for the Azure-hosted API described in spec section 10.
 * The app never talks to Azure Storage account keys directly: this
 * interface is the only bridge to the backend, which authenticates the
 * device and returns short-lived, single-blob/operation SAS URLs.
 *
 * IMPORTANT: this interface describes the *client-side* contract only. The
 * actual Azure Functions/App Service/Container Apps implementation is
 * out of scope for this Android app module and must be built/deployed
 * separately (see photosync-app/README.md).
 */
interface PhotoSyncApiService {

    @POST("api/devices/register")
    suspend fun registerDevice(@Body request: DeviceRegistrationRequest): Response<DeviceRegistrationResponse>

    @GET("api/devices/{deviceId}/configuration")
    suspend fun getConfiguration(@Path("deviceId") deviceId: String): Response<DeviceConfiguration>

    @POST("api/transfers/uploads")
    suspend fun createUploadTransfer(
        @Body request: CreateUploadTransferRequest,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<CreateUploadTransferResponse>

    @POST("api/transfers/uploads/{transferId}/complete")
    suspend fun completeUploadTransfer(
        @Path("transferId") transferId: String,
        @Body request: CompleteUploadTransferRequest,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<TransferCompleteResponse>

    @GET("api/devices/{deviceId}/downloads")
    suspend fun getDownloadManifest(@Path("deviceId") deviceId: String): Response<DownloadManifestResponse>

    @POST("api/transfers/downloads/{transferId}/complete")
    suspend fun completeDownloadTransfer(
        @Path("transferId") transferId: String,
        @Body request: CompleteDownloadTransferRequest,
        @Header("Idempotency-Key") idempotencyKey: String
    ): Response<TransferCompleteResponse>

    @GET("api/transfers/{transferId}")
    suspend fun getTransfer(@Path("transferId") transferId: String): Response<TransferCompleteResponse>

    @POST("api/transfers/{transferId}/heartbeat")
    suspend fun heartbeat(@Path("transferId") transferId: String): Response<Unit>

    @POST("api/transfers/{transferId}/fail")
    suspend fun failTransfer(
        @Path("transferId") transferId: String,
        @Body request: TransferErrorRequest
    ): Response<Unit>

    @POST("api/transfers/{transferId}/cancel")
    suspend fun cancelTransfer(@Path("transferId") transferId: String): Response<Unit>

    @POST("api/transfers/{transferId}/retry")
    suspend fun retryTransfer(@Path("transferId") transferId: String): Response<Unit>
}
