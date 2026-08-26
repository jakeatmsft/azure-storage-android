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

package com.microsoft.azure.storage.photosync.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Wires WorkManager scheduling on process start so queued work is restored
 * after process death, app upgrade, or device reboot (spec 16, Recovery:
 * "Queued work is restored after device reboot").
 */
class PhotoSyncApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleWork()
    }

    fun scheduleWork() {
        val container = AppContainer.getInstance(this)
        CoroutineScope(Dispatchers.Default).launch {
            val settings = container.settingsRepository
            WorkScheduler.reschedule(
                context = applicationContext,
                mode = settings.getAppMode(),
                uploadWifiOnly = settings.isUploadWifiOnly(),
                uploadChargingOnly = settings.isUploadChargingOnly(),
                downloadWifiOnly = settings.isDownloadWifiOnly()
            )
        }
    }
}
