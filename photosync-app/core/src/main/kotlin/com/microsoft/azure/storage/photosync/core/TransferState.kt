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

package com.microsoft.azure.storage.photosync.core

/** Direction of a logical transfer, per FileCatalog/TransferState.Direction. */
enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

/**
 * Transfer state machine states, per spec section 9. Instances of this app's
 * local `LocalTransfer` Room entity and the server-side `TransferState` table
 * both use these values.
 */
enum class TransferState {
    DISCOVERED,
    QUEUED,
    AUTHORIZING,
    TRANSFERRING,
    VERIFYING,
    COMPLETING,
    COMPLETED,
    RETRY_PENDING,
    FAILED,
    INTERRUPTED,
    SKIPPED,
    CANCELLED,
    CONFLICT
}

/** Whether an error should be retried automatically. */
enum class ErrorCategory {
    RETRYABLE,
    NON_RETRYABLE
}

/**
 * Validates transitions of the transfer state machine described in spec
 * section 9, so that both the upload and download workers share a single
 * source of truth for legal transitions.
 *
 * Rules enforced:
 *  - Only [TransferState.QUEUED] or [TransferState.RETRY_PENDING] records may
 *    enter [TransferState.TRANSFERRING].
 *  - [TransferState.VERIFYING] is required before [TransferState.COMPLETING]
 *    or [TransferState.COMPLETED].
 *  - [TransferState.COMPLETED] records must never be silently reset to any
 *    other state (completion is terminal).
 */
object TransferStateMachine {

    private val terminal = setOf(
        TransferState.COMPLETED,
        TransferState.FAILED,
        TransferState.SKIPPED,
        TransferState.CANCELLED
    )

    private val allowedTransitions: Map<TransferState, Set<TransferState>> = mapOf(
        TransferState.DISCOVERED to setOf(TransferState.QUEUED, TransferState.SKIPPED, TransferState.CANCELLED),
        TransferState.QUEUED to setOf(
            TransferState.AUTHORIZING,
            TransferState.TRANSFERRING,
            TransferState.CANCELLED,
            TransferState.SKIPPED,
            TransferState.INTERRUPTED
        ),
        TransferState.AUTHORIZING to setOf(
            TransferState.TRANSFERRING,
            TransferState.RETRY_PENDING,
            TransferState.FAILED,
            TransferState.CANCELLED,
            TransferState.INTERRUPTED
        ),
        TransferState.TRANSFERRING to setOf(
            TransferState.VERIFYING,
            TransferState.RETRY_PENDING,
            TransferState.FAILED,
            TransferState.INTERRUPTED,
            TransferState.CANCELLED
        ),
        TransferState.VERIFYING to setOf(
            TransferState.COMPLETING,
            TransferState.COMPLETED,
            TransferState.CONFLICT,
            TransferState.RETRY_PENDING,
            TransferState.FAILED,
            TransferState.INTERRUPTED
        ),
        TransferState.COMPLETING to setOf(
            TransferState.COMPLETED,
            TransferState.RETRY_PENDING,
            TransferState.FAILED,
            TransferState.INTERRUPTED
        ),
        TransferState.RETRY_PENDING to setOf(
            TransferState.QUEUED,
            TransferState.TRANSFERRING,
            TransferState.AUTHORIZING,
            TransferState.FAILED,
            TransferState.SKIPPED,
            TransferState.CANCELLED
        ),
        TransferState.INTERRUPTED to setOf(
            TransferState.QUEUED,
            TransferState.RETRY_PENDING,
            TransferState.TRANSFERRING,
            TransferState.VERIFYING,
            TransferState.COMPLETED,
            TransferState.FAILED,
            TransferState.CANCELLED
        ),
        TransferState.CONFLICT to setOf(TransferState.CANCELLED, TransferState.FAILED, TransferState.RETRY_PENDING),
        TransferState.COMPLETED to emptySet(),
        TransferState.FAILED to setOf(TransferState.RETRY_PENDING),
        TransferState.SKIPPED to emptySet(),
        TransferState.CANCELLED to emptySet()
    )

    /** True if [from] -> [to] is a legal transition of the transfer state machine. */
    fun canTransition(from: TransferState, to: TransferState): Boolean {
        if (from == to) return false
        // Completed records must not be silently reset; enforced explicitly here
        // even though `allowedTransitions[COMPLETED]` is already empty, so the
        // invariant holds even if the map is edited in the future.
        if (from == TransferState.COMPLETED) return false
        return allowedTransitions[from]?.contains(to) == true
    }

    /** True once a transfer has reached a state that will not change again automatically. */
    fun isTerminal(state: TransferState): Boolean = state in terminal

    /**
     * Applies [to] on top of [from], throwing [IllegalStateException] if the
     * transition is not permitted by the state machine.
     */
    fun requireTransition(from: TransferState, to: TransferState) {
        check(canTransition(from, to)) { "Illegal transfer state transition: $from -> $to" }
    }
}
