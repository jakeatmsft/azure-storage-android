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

import android.content.Context
import com.microsoft.azure.storage.photosync.app.data.local.AppDatabase
import com.microsoft.azure.storage.photosync.app.data.local.SettingsRepository
import com.microsoft.azure.storage.photosync.app.data.remote.ApiClientFactory
import com.microsoft.azure.storage.photosync.app.data.remote.PhotoSyncApiService

/**
 * Minimal service locator wiring the app's dependencies (Room database,
 * Retrofit API client, settings). Kept intentionally simple (no DI
 * framework) so it can be constructed cheaply from WorkManager workers,
 * which do not participate in Activity/ViewModel scoping.
 */
class AppContainer(context: Context) {

    val database: AppDatabase = AppDatabase.getInstance(context)
    val settingsRepository: SettingsRepository = SettingsRepository(database.appSettingDao())

    /**
     * The Retrofit client is created lazily against the configured API base
     * URL. Per spec section 5, the app never embeds a storage account key;
     * all it needs is this API endpoint plus a (device or user) auth token
     * supplied by [authTokenProvider].
     */
    fun apiService(baseUrl: String, authTokenProvider: () -> String?): PhotoSyncApiService =
        ApiClientFactory.create(baseUrl, authTokenProvider)

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun getInstance(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
