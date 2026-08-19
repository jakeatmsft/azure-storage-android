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

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter, per spec section 12.3. Defaults match the
 * recommended values: initial retry 30s, maximum delay 6h, maximum
 * immediate attempts 5.
 */
class RetryPolicy(
    private val initialDelayMillis: Long = 30_000L,
    private val maxDelayMillis: Long = 6L * 60 * 60 * 1000,
    private val maxImmediateAttempts: Int = 5,
    private val random: Random = Random.Default
) {

    /** True while automatic (short-interval) retries are still permitted. */
    fun isImmediateRetryAllowed(attemptCount: Int): Boolean = attemptCount < maxImmediateAttempts

    /**
     * Computes the delay, in milliseconds, before [attemptCount] (1-based,
     * the attempt that just failed) may be retried. Honors a server-supplied
     * `Retry-After` value in seconds when present.
     */
    fun nextDelayMillis(attemptCount: Int, retryAfterSeconds: Long? = null): Long {
        if (retryAfterSeconds != null) {
            return min(retryAfterSeconds * 1000, maxDelayMillis)
        }
        val exponential = initialDelayMillis * 2.0.pow((attemptCount - 1).coerceAtLeast(0))
        val capped = min(exponential, maxDelayMillis.toDouble())
        // +/- 20% jitter to avoid thundering-herd retries across devices.
        val jitterFactor = 0.8 + random.nextDouble() * 0.4
        return (capped * jitterFactor).toLong().coerceIn(0, maxDelayMillis)
    }
}
