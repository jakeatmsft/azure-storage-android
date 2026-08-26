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

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.microsoft.azure.storage.photosync.app.AppContainer
import com.microsoft.azure.storage.photosync.app.security.CredentialStore

/**
 * Finds new or changed files in the selected SAF folders and queues them for upload.
 */
class PhotoDiscoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.getInstance(applicationContext)
        val settings = container.settingsRepository
        val deviceId = settings.getOrCreateDeviceId()
        val directConfiguration = CredentialStore(applicationContext).getAzureSasConfiguration()
        val identityScope = directConfiguration?.let {
            "$deviceId@${it.accountHost}/${it.uploadContainer}"
        } ?: deviceId

        val scanner = MediaStoreScanner(applicationContext)
        val discoveryRepository = DiscoveryRepository(
            container.database.localFileDao(),
            container.database.localTransferDao()
        )

        val uploadFolderUris = settings.getUploadFolderUris()
        if (uploadFolderUris.isEmpty()) return Result.success()
        val includeVideos = settings.isUploadIncludeVideos()

        return try {
            val candidates = scanner.scan(uploadFolderUris, includeVideos)
            discoveryRepository.recordDiscoveries(identityScope, candidates)
            Result.success()
        } catch (e: SecurityException) {
            // Missing media read permission; do not retry until the user grants it.
            Result.failure()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "photo-discovery"
    }
}
