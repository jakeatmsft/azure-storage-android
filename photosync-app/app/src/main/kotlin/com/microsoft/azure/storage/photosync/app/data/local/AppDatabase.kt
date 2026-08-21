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

package com.microsoft.azure.storage.photosync.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.microsoft.azure.storage.photosync.app.data.local.dao.AppSettingDao
import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalFileDao
import com.microsoft.azure.storage.photosync.app.data.local.dao.LocalTransferDao
import com.microsoft.azure.storage.photosync.app.data.local.dao.PendingStateUpdateDao
import com.microsoft.azure.storage.photosync.app.data.local.entity.AppSetting
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalFile
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import com.microsoft.azure.storage.photosync.app.data.local.entity.PendingStateUpdate

/**
 * The local Room database (spec section 4.5). Allows the app to recover
 * queued/interrupted transfers after loss of connectivity, process
 * termination, or device restart.
 */
@Database(
    entities = [LocalFile::class, LocalTransfer::class, PendingStateUpdate::class, AppSetting::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun localFileDao(): LocalFileDao
    abstract fun localTransferDao(): LocalTransferDao
    abstract fun pendingStateUpdateDao(): PendingStateUpdateDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        private const val DATABASE_NAME = "photosync.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
    }
}
