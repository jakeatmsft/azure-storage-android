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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.microsoft.azure.storage.photosync.app.data.local.entity.LocalTransfer
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalTransferDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transfer: LocalTransfer): Long

    @Update
    suspend fun update(transfer: LocalTransfer)

    @Query("SELECT * FROM local_transfer WHERE transferId = :transferId")
    suspend fun findById(transferId: String): LocalTransfer?

    @Query("SELECT * FROM local_transfer WHERE identityKey = :identityKey LIMIT 1")
    suspend fun findByIdentityKey(identityKey: String): LocalTransfer?

    @Query("SELECT identityKey FROM local_transfer WHERE direction = :direction AND state = 'COMPLETED'")
    suspend fun completedIdentityKeys(direction: String): List<String>

    @Query(
        "SELECT * FROM local_transfer WHERE direction = :direction " +
            "AND state IN ('QUEUED', 'RETRY_PENDING') " +
            "AND (nextRetryUtcEpochMillis IS NULL OR nextRetryUtcEpochMillis <= :nowUtcEpochMillis) " +
            "ORDER BY createdUtcEpochMillis ASC, transferId ASC LIMIT :limit"
    )
    suspend fun nextQueuedOrRetryable(direction: String, nowUtcEpochMillis: Long, limit: Int): List<LocalTransfer>

    @Query(
        "SELECT * FROM local_transfer WHERE direction = :direction " +
            "AND state IN ('AUTHORIZING', 'TRANSFERRING', 'VERIFYING', 'COMPLETING')"
    )
    suspend fun findInterrupted(direction: String): List<LocalTransfer>

    @Query("SELECT COUNT(*) FROM local_transfer WHERE state IN ('QUEUED', 'RETRY_PENDING', 'AUTHORIZING', 'TRANSFERRING', 'VERIFYING', 'COMPLETING')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM local_transfer WHERE state = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM local_transfer ORDER BY createdUtcEpochMillis DESC")
    fun observeAll(): Flow<List<LocalTransfer>>

    @Query(
        "SELECT * FROM local_transfer WHERE " +
            "(:direction IS NULL OR direction = :direction) AND " +
            "(:state IS NULL OR state = :state) " +
            "ORDER BY createdUtcEpochMillis DESC"
    )
    fun observeFiltered(direction: String?, state: String?): Flow<List<LocalTransfer>>

    @Query(
        "DELETE FROM local_transfer WHERE state = 'COMPLETED' " +
            "AND direction != :retainedDirection " +
            "AND completedUtcEpochMillis < :olderThanUtcEpochMillis"
    )
    suspend fun deleteCompletedOlderThan(
        olderThanUtcEpochMillis: Long,
        retainedDirection: String
    ): Int

    @Query("DELETE FROM local_transfer WHERE state NOT IN ('COMPLETED', 'SKIPPED')")
    suspend fun deleteUnfinished(): Int

    @Query("DELETE FROM local_transfer WHERE direction = :direction AND state NOT IN ('COMPLETED', 'SKIPPED')")
    suspend fun deleteUnfinished(direction: String): Int
}
