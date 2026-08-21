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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStateMachineTest {

    @Test
    fun `only queued or retry-pending may enter transferring`() {
        assertTrue(TransferStateMachine.canTransition(TransferState.QUEUED, TransferState.TRANSFERRING))
        assertTrue(TransferStateMachine.canTransition(TransferState.RETRY_PENDING, TransferState.TRANSFERRING))
        assertFalse(TransferStateMachine.canTransition(TransferState.DISCOVERED, TransferState.TRANSFERRING))
        assertFalse(TransferStateMachine.canTransition(TransferState.VERIFYING, TransferState.TRANSFERRING))
        assertFalse(TransferStateMachine.canTransition(TransferState.COMPLETED, TransferState.TRANSFERRING))
    }

    @Test
    fun `verification is required before completing`() {
        assertFalse(TransferStateMachine.canTransition(TransferState.TRANSFERRING, TransferState.COMPLETING))
        assertFalse(TransferStateMachine.canTransition(TransferState.TRANSFERRING, TransferState.COMPLETED))
        assertTrue(TransferStateMachine.canTransition(TransferState.VERIFYING, TransferState.COMPLETING))
        assertTrue(TransferStateMachine.canTransition(TransferState.VERIFYING, TransferState.COMPLETED))
    }

    @Test
    fun `completed records must not be silently reset`() {
        for (state in TransferState.values()) {
            if (state == TransferState.COMPLETED) continue
            assertFalse(
                "COMPLETED -> $state must not be allowed",
                TransferStateMachine.canTransition(TransferState.COMPLETED, state)
            )
        }
        assertTrue(TransferStateMachine.isTerminal(TransferState.COMPLETED))
    }

    @Test
    fun `failed transfers may only re-enter via retry pending`() {
        assertTrue(TransferStateMachine.canTransition(TransferState.FAILED, TransferState.RETRY_PENDING))
        assertFalse(TransferStateMachine.canTransition(TransferState.FAILED, TransferState.TRANSFERRING))
        assertFalse(TransferStateMachine.canTransition(TransferState.FAILED, TransferState.COMPLETED))
    }

    @Test
    fun `requireTransition throws on illegal transitions`() {
        try {
            TransferStateMachine.requireTransition(TransferState.COMPLETED, TransferState.QUEUED)
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `a state never transitions to itself`() {
        for (state in TransferState.values()) {
            assertFalse(TransferStateMachine.canTransition(state, state))
        }
    }
}
