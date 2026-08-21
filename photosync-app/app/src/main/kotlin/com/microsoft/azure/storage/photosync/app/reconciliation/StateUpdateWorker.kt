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

package com.microsoft.azure.storage.photosync.app.reconciliation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.security.CredentialStore
import com.microsoft.azure.storage.photosync.core.TransferState
import com.microsoft.azure.storage.photosync.core.api.CompleteDownloadTransferRequest
import com.microsoft.azure.storage.photosync.core.api.CompleteUploadTransferRequest

/**
 * Retries completion (or other) state-update requests that were accepted
 * locally but not yet confirmed by the API, per spec section 11.3:
 * "Store failed completion requests locally. Retry the state update before
 * retransferring the content." This worker never re-transfers file content;
 * it only replays the previously built completion request.
 */
class StateUpdateWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val gson = Gson()

    override suspend fun doWork(): Result {
        val container = AppContainer.getInstance(appContext)
        val settings = container.settingsRepository
        val baseUrl = settings.getApiBaseUrl()
        if (baseUrl.isBlank()) return Result.success()

        val credentialStore = CredentialStore(appContext)
        val api = container.apiService(baseUrl) { credentialStore.getToken() }
        val pendingDao = container.database.pendingStateUpdateDao()
        val transferDao = container.database.localTransferDao()

        val dueUpdates = pendingDao.dueUpdates(System.currentTimeMillis())
        if (dueUpdates.isEmpty()) return Result.success()

        var anyRetryable = false
        for (update in dueUpdates) {
            try {
                val response = when (update.updateType) {
                    "complete_upload" -> {
                        val request = gson.fromJson(update.payloadJson, CompleteUploadTransferRequest::class.java)
                        api.completeUploadTransfer(update.transferId, request, update.idempotencyKey)
                    }
                    "complete_download" -> {
                        val request = gson.fromJson(update.payloadJson, CompleteDownloadTransferRequest::class.java)
                        api.completeDownloadTransfer(update.transferId, request, update.idempotencyKey)
                    }
                    else -> null
                }

                if (response == null) {
                    pendingDao.delete(update)
                    continue
                }

                if (response.isSuccessful) {
                    pendingDao.delete(update)
                    transferDao.findById(update.transferId)?.let { transfer ->
                        if (transfer.state != TransferState.COMPLETED.name) {
                            transferDao.update(
                                transfer.copy(
                                    state = TransferState.COMPLETED.name,
                                    completedUtcEpochMillis = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                } else if (response.code() in RETRYABLE_HTTP_CODES) {
                    anyRetryable = true
                    pendingDao.update(
                        update.copy(
                            attemptCount = update.attemptCount + 1,
                            lastErrorMessage = "HTTP ${response.code()}"
                        )
                    )
                } else {
                    // Non-retryable: drop the pending update; the transfer keeps
                    // its current (COMPLETING) state for manual/administrative review.
                    pendingDao.delete(update)
                }
            } catch (e: java.io.IOException) {
                anyRetryable = true
                pendingDao.update(update.copy(attemptCount = update.attemptCount + 1, lastErrorMessage = e.message))
            }
        }

        return if (anyRetryable) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "state-update"
        val RETRYABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}
