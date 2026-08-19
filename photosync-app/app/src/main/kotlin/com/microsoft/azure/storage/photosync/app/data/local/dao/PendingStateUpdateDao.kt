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

package com.microsoft.azure.storage.photosync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.microsoft.azure.storage.photosync.app.data.local.entity.PendingStateUpdate

@Dao
interface PendingStateUpdateDao {

    @Insert
    suspend fun insert(update: PendingStateUpdate): Long

    @Update
    suspend fun update(update: PendingStateUpdate)

    @Delete
    suspend fun delete(update: PendingStateUpdate)

    @Query("SELECT * FROM pending_state_update WHERE nextRetryUtcEpochMillis IS NULL OR nextRetryUtcEpochMillis <= :nowUtcEpochMillis ORDER BY createdUtcEpochMillis ASC")
    suspend fun dueUpdates(nowUtcEpochMillis: Long): List<PendingStateUpdate>

    @Query("SELECT COUNT(*) FROM pending_state_update")
    suspend fun count(): Int
}
