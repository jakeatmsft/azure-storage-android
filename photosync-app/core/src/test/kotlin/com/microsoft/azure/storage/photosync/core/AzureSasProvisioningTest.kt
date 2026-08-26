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

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSasProvisioningTest {

    @Test
    fun `valid QR payload is parsed without changing encoded SAS signature`() {
        val sas = "sv=2023-11-03&se=2026-12-31T00%3A00%3A00Z&sp=rlcw&sig=abc%2B123%3D"
        val payload = payload(sas)

        val result = AzureSasProvisioning.parse(payload)

        assertTrue(result is AzureSasProvisioningResult.Success)
        val configuration = (result as AzureSasProvisioningResult.Success).configuration
        assertEquals("https://example.blob.core.windows.net", configuration.blobServiceUrl)
        assertEquals("phone-uploads", configuration.uploadContainer)
        assertEquals("phone-downloads", configuration.downloadContainer)
        assertEquals(sas, configuration.sasToken)
        assertFalse(configuration.toString().contains("abc"))
    }

    @Test
    fun `non HTTPS account endpoint is rejected`() {
        val result = AzureSasProvisioning.parse(
            payload("se=2026-12-31&sp=rlcw&sig=test", accountUrl = "http://example.test")
        )

        assertTrue(result is AzureSasProvisioningResult.Failure)
    }

    @Test
    fun `SAS without download permissions is rejected`() {
        val result = AzureSasProvisioning.parse(payload("se=2026-12-31&sp=cw&sig=test"))

        assertTrue(result is AzureSasProvisioningResult.Failure)
    }

    @Test
    fun `SAS without upload permissions is rejected`() {
        val result = AzureSasProvisioning.parse(payload("se=2026-12-31&sp=rl&sig=test"))

        assertTrue(result is AzureSasProvisioningResult.Failure)
    }

    @Test
    fun `create-only SAS is rejected because retries may overwrite a partial blob`() {
        val result = AzureSasProvisioning.parse(payload("se=2026-12-31&sp=rlc&sig=test"))

        assertTrue(result is AzureSasProvisioningResult.Failure)
    }

    @Test
    fun `arbitrary QR content is rejected`() {
        assertTrue(AzureSasProvisioning.parse("https://example.com") is AzureSasProvisioningResult.Failure)
    }

    private fun payload(
        sas: String,
        accountUrl: String = "https://example.blob.core.windows.net"
    ): String {
        fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
        return "photosync://azure-sas/v1" +
            "?accountUrl=${encode(accountUrl)}" +
            "&uploadContainer=phone-uploads" +
            "&downloadContainer=phone-downloads" +
            "&sas=${encode(sas)}"
    }
}
