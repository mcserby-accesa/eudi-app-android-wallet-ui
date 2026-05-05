/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Decodes the workshop QR-config payload (or its manually-typed equivalent)
 * into a validated PID issuer URL. The QR payload is the JSON object
 * `{ pidIssuerUrl: string }` per `specs/components/mobile-wallet.md` in the
 * companion repo; manual entry accepts the URL on its own.
 */

package eu.europa.ec.delogic.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

class WalletConfigDecoder(
    private val json: Json = StrictJson,
) {
    sealed interface Result {
        data class Success(val pidIssuerUrl: String) : Result
        data class Failure(val errorCode: ErrorCode) : Result
    }

    enum class ErrorCode { EMPTY, MALFORMED_JSON, INVALID_URL }

    fun decode(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Failure(ErrorCode.EMPTY)

        val candidate = parseJsonPayload(trimmed) ?: trimmed
        return when (val normalized = normalizeUrl(candidate)) {
            null -> Result.Failure(
                if (trimmed.startsWith("{")) ErrorCode.MALFORMED_JSON else ErrorCode.INVALID_URL
            )

            else -> Result.Success(normalized)
        }
    }

    private fun parseJsonPayload(input: String): String? {
        if (!input.startsWith("{")) return null
        val obj = runCatching {
            json.parseToJsonElement(input) as? JsonObject
        }.getOrNull() ?: return null
        return obj["pidIssuerUrl"]?.jsonPrimitive?.contentOrNull
    }

    /** Returns the URL with a single trailing-slash stripped, or null if invalid. */
    private fun normalizeUrl(input: String): String? = runCatching {
        val uri = URI(input.trim())
        if (uri.scheme !in HTTP_SCHEMES) return@runCatching null
        if (uri.host.isNullOrBlank()) return@runCatching null
        input.trim().trimEnd('/')
    }.getOrNull()

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
        val StrictJson = Json { ignoreUnknownKeys = true; isLenient = false }
    }
}
