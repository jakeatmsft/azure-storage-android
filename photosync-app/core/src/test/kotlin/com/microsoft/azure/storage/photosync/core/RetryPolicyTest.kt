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

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `initial delay is approximately 30 seconds`() {
        val policy = RetryPolicy(random = Random(0))
        val delay = policy.nextDelayMillis(attemptCount = 1)
        assertTrue("delay was $delay", delay in 24_000..36_000)
    }

    @Test
    fun `delay grows exponentially but never exceeds the max`() {
        val policy = RetryPolicy(random = Random(0))
        val maxDelayMillis = 6L * 60 * 60 * 1000
        val delay = policy.nextDelayMillis(attemptCount = 20)
        assertTrue("delay was $delay", delay <= maxDelayMillis)
        assertTrue("delay was $delay", delay >= (maxDelayMillis * 0.75).toLong())
    }

    @Test
    fun `retry-after header overrides computed backoff`() {
        val policy = RetryPolicy(random = Random(0))
        val delay = policy.nextDelayMillis(attemptCount = 1, retryAfterSeconds = 5)
        assertEquals(5_000L, delay)
    }

    @Test
    fun `immediate retries are limited to the configured maximum`() {
        val policy = RetryPolicy(maxImmediateAttempts = 5)
        assertTrue(policy.isImmediateRetryAllowed(4))
        assertFalse(policy.isImmediateRetryAllowed(5))
    }
}
