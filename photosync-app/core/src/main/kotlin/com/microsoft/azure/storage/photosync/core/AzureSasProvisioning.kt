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

import java.net.URI
import java.net.URLDecoder

/** A validated storage configuration. [toString] deliberately redacts the SAS. */
class AzureSasConfiguration(
    val blobServiceUrl: String,
    val uploadContainer: String,
    val downloadContainer: String,
    val sasToken: String,
    val expiresUtc: String
) {
    val accountHost: String
        get() = URI.create(blobServiceUrl).host

    override fun toString(): String =
        "AzureSasConfiguration(blobServiceUrl=$blobServiceUrl, " +
            "uploadContainer=$uploadContainer, downloadContainer=$downloadContainer, " +
            "expiresUtc=$expiresUtc, sasToken=<redacted>)"
}

sealed class AzureSasProvisioningResult {
    data class Success(val configuration: AzureSasConfiguration) : AzureSasProvisioningResult()
    data class Failure(val message: String) : AzureSasProvisioningResult()
}

/**
 * Parser for the QR payload produced by `tools/generate_sas_qr.py`.
 *
 * Payload format:
 * `photosync://azure-sas/v1?accountUrl=...&uploadContainer=...&downloadContainer=...&sas=...`
 */
object AzureSasProvisioning {

    private const val MAX_PAYLOAD_LENGTH = 8_192
    private val containerPattern = Regex("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?$")

    fun parse(payload: String): AzureSasProvisioningResult {
        if (payload.length > MAX_PAYLOAD_LENGTH) return failure("QR payload is too large")

        return try {
            val uri = URI(payload.trim())
            if (uri.scheme != "photosync" || uri.host != "azure-sas" || uri.path != "/v1") {
                return failure("This is not a PhotoSync Azure SAS provisioning code")
            }

            val values = parseQuery(uri.rawQuery ?: return failure("Provisioning code has no settings"))
            val accountUrl = validateAccountUrl(values.required("accountUrl"))
            val uploadContainer = validateContainer(values.required("uploadContainer"), "upload")
            val downloadContainer = validateContainer(values.required("downloadContainer"), "download")
            val sasToken = values.required("sas").removePrefix("?")
            val sasValues = parseQuery(sasToken)
            sasValues.required("sig")
            val expiry = sasValues.required("se")
            val permissions = sasValues.required("sp")

            if ('r' !in permissions || 'l' !in permissions) {
                return failure("SAS must grant read and list permissions for downloads")
            }
            if ('w' !in permissions) {
                return failure("SAS must grant write permission for resumable uploads")
            }

            AzureSasProvisioningResult.Success(
                AzureSasConfiguration(
                    blobServiceUrl = accountUrl,
                    uploadContainer = uploadContainer,
                    downloadContainer = downloadContainer,
                    sasToken = sasToken,
                    expiresUtc = expiry
                )
            )
        } catch (e: IllegalArgumentException) {
            failure(e.message ?: "Provisioning code is invalid")
        } catch (e: Exception) {
            failure("Provisioning code is invalid")
        }
    }

    private fun validateAccountUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Blob service URL must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Blob service URL must include a host" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Blob service URL cannot contain credentials, a query, or a fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "Blob service URL must not include a container or blob path"
        }
        return value.trimEnd('/')
    }

    private fun validateContainer(value: String, label: String): String {
        require(value.length in 3..63 && containerPattern.matches(value) && "--" !in value) {
            "The $label container name is not valid for Azure Blob Storage"
        }
        return value
    }

    private fun Map<String, String>.required(key: String): String =
        this[key]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Provisioning code is missing $key")

    private fun parseQuery(rawQuery: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isBlank()) continue
            val pieces = part.split('=', limit = 2)
            val key = decode(pieces[0])
            val value = decode(pieces.getOrElse(1) { "" })
            require(key !in values) { "Provisioning code contains duplicate $key" }
            values[key] = value
        }
        return values
    }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun failure(message: String) = AzureSasProvisioningResult.Failure(message)
}
