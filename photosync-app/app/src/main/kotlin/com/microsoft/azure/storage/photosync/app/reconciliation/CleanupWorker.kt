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
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.microsoft.azure.storage.photosync.app.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Removes abandoned temporary ("*.part") download files from the
 * destination folder after the retention period. Completed upload and
 * download records are retained as compact identity receipts so an unchanged
 * source or remote blob is never transferred twice.
 */
class CleanupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.getInstance(appContext)
        val settings = container.settingsRepository
        val now = System.currentTimeMillis()

        val destinationUriString = settings.getDownloadDestinationUri()
        if (destinationUriString != null) {
            runCatching {
                val destinationDir = DocumentFile.fromTreeUri(appContext, Uri.parse(destinationUriString))
                destinationDir?.listFiles()?.forEach { file ->
                    val name = file.name ?: return@forEach
                    if (name.endsWith(".part") &&
                        now - file.lastModified() > TimeUnit.HOURS.toMillis(TEMP_FILE_RETENTION_HOURS)
                    ) {
                        file.delete()
                    }
                }
            }
        }

        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "cleanup"
        const val TEMP_FILE_RETENTION_HOURS = 24L
    }
}
