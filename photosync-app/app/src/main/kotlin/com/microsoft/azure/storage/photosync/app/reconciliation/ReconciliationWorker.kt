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
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.security.CredentialStore
import com.microsoft.azure.storage.photosync.core.TransferDirection
import com.microsoft.azure.storage.photosync.core.TransferState

/**
 * Repairs transfers left in an in-progress state (AUTHORIZING, TRANSFERRING,
 * VERIFYING, COMPLETING) by a worker that was killed or interrupted before
 * finishing, per spec sections 6 and 13:
 *  - Confirm with the API whether the transfer already completed server-side
 *    (idempotent lookup via GET /api/transfers/{transferId}).
 *  - If completed remotely, mark the local record COMPLETED without
 *    re-transferring content.
 *  - Otherwise, if the interruption is old enough to be considered
 *    abandoned, return the transfer to RETRY_PENDING so UploadWorker /
 *    DownloadWorker will safely restart it from the beginning.
 *
 * Completed records are never reset: this worker only ever moves records
 * *toward* COMPLETED or RETRY_PENDING, never away from COMPLETED.
 */
class ReconciliationWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.getInstance(appContext)
        val settings = container.settingsRepository
        val baseUrl = settings.getApiBaseUrl()
        if (baseUrl.isBlank()) return Result.success()

        val credentialStore = CredentialStore(appContext)
        val api = container.apiService(baseUrl) { credentialStore.getToken() }
        val transferDao = container.database.localTransferDao()

        val now = System.currentTimeMillis()
        val interrupted = transferDao.findInterrupted(TransferDirection.UPLOAD.name) +
            transferDao.findInterrupted(TransferDirection.DOWNLOAD.name)

        for (transfer in interrupted) {
            val heartbeat = transfer.lastHeartbeatUtcEpochMillis ?: transfer.createdUtcEpochMillis
            if (now - heartbeat < INTERRUPTION_GRACE_PERIOD_MILLIS) continue

            try {
                val response = api.getTransfer(transfer.transferId)
                if (response.isSuccessful && response.body()?.state == TransferState.COMPLETED.name) {
                    transferDao.update(
                        transfer.copy(
                            state = TransferState.COMPLETED.name,
                            completedUtcEpochMillis = System.currentTimeMillis()
                        )
                    )
                } else {
                    transferDao.update(
                        transfer.copy(
                            state = TransferState.RETRY_PENDING.name,
                            attemptCount = transfer.attemptCount + 1,
                            lastErrorCategory = "RETRYABLE",
                            lastErrorMessage = "Interrupted transfer restarted by reconciliation",
                            isRetryable = true
                        )
                    )
                }
            } catch (e: java.io.IOException) {
                // Network unavailable; leave as-is and try again on the next run.
            }
        }

        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "reconciliation"
        const val INTERRUPTION_GRACE_PERIOD_MILLIS = 15 * 60 * 1000L
    }
}
