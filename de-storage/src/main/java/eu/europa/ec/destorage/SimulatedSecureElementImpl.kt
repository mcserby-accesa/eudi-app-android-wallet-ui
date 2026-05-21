/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Workshop-only simulated Secure Element. All persisted state lives under
 * the existing Tink-AEAD-encrypted DataStore (see PrefsController) — the
 * private key material, the per-token JWSes, and the monotonic txCounter
 * are all written through the same master key as every other wallet secret.
 *
 * This is NOT a real SE applet. A production wallet would put the private
 * keys behind StrongBox / TEE and the txCounter behind silicon-enforced
 * atomicity. Per CLAUDE.md, every UI surface that depends on this state
 * must carry the "Simulated SE — workshop demo. Not a real Secure Element."
 * disclaimer.
 */

package eu.europa.ec.destorage

import eu.europa.ec.businesslogic.controller.storage.PrefsController
import eu.europa.ec.delogic.jws.OfflineTokenVerifier
import eu.europa.ec.delogic.jws.OfflineTokenVerifyResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

class SimulatedSecureElementImpl(
    private val prefs: PrefsController,
    private val verifier: OfflineTokenVerifier,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val aliasFactory: () -> String = { UUID.randomUUID().toString() },
) : SimulatedSecureElement {

    /**
     * Coarse-grained serialisation across all SE operations. The real
     * SE applet would have its own atomic-counter primitive; for the
     * simulated SE we pay the lock cost to keep `txCounter` monotonic
     * even under concurrent withdraw + holdings-list reads.
     */
    private val mutex = Mutex()

    override suspend fun generateHolderKey(): HolderKeyHandle = mutex.withLock {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val keyPair = generator.generateKeyPair()
        val publicJwk = (keyPair.public as ECPublicKey).toJwk()
        val privateD = (keyPair.private as java.security.interfaces.ECPrivateKey).s

        val alias = aliasFactory()
        val keys = readHolderKeys().toMutableMap()
        keys[alias] = StoredHolderKey(
            privateDBase64Url = encodeUnsignedBigInt(privateD, P256_COMPONENT_LENGTH),
            publicJwk = publicJwk,
        )
        writeHolderKeys(keys)

        // Generating a key is a state-touching operation; bump the counter
        // so the next storeTokens() observes a higher floor.
        incrementTxCounter()

        HolderKeyHandle(alias = alias, publicJwk = publicJwk)
    }

    override suspend fun storeTokens(
        handle: HolderKeyHandle,
        tokens: List<OfflineTokenJws>,
    ): StoreResult = mutex.withLock {
        val keys = readHolderKeys()
        if (handle.alias !in keys) {
            return@withLock StoreResult.Rejected(reason = "unknown_holder_alias")
        }

        val existing = readStoredTokens().associateBy { it.serial }.toMutableMap()
        val now = clock.instant()
        var newCount = 0
        val rejected = mutableListOf<Pair<String, String>>()  // (serial, reason)
        var rejectionReason: String? = null

        for (token in tokens) {
            if (token.serial in existing) continue

            when (val verify = verifier.verify(token.jws, handle.publicJwk)) {
                is OfflineTokenVerifyResult.Ok -> {
                    val payload = verify.payload
                    // Defense-in-depth: the bank should not be re-shaping
                    // the token between the JWS payload and the wire row.
                    if (payload.serial != token.serial ||
                        payload.amount != token.amount ||
                        payload.currency != token.currency
                    ) {
                        rejected += token.serial to "envelope_mismatch"
                        rejectionReason = rejectionReason ?: "envelope_mismatch"
                        continue
                    }
                    existing[token.serial] = StoredTokenRecord(
                        serial = token.serial,
                        amount = token.amount,
                        currency = token.currency,
                        ncbBic = payload.ncbBic,
                        holderKeyAlias = handle.alias,
                        jws = token.jws,
                        issuedAt = payload.issuedAt.toString(),
                        expiry = payload.expiry.toString(),
                        storedAt = now.toString(),
                    )
                    newCount += 1
                }

                is OfflineTokenVerifyResult.Failure.HolderPubMismatch -> {
                    rejected += token.serial to "holder_pub_mismatch"
                    rejectionReason = rejectionReason ?: "holder_pub_mismatch"
                }

                is OfflineTokenVerifyResult.Failure.Expired -> {
                    rejected += token.serial to "expired"
                    rejectionReason = rejectionReason ?: "expired"
                }

                is OfflineTokenVerifyResult.Failure.SignatureInvalid,
                is OfflineTokenVerifyResult.Failure.Malformed -> {
                    rejected += token.serial to "signature_invalid"
                    rejectionReason = rejectionReason ?: "token_signature_invalid"
                }
            }
        }

        if (newCount == 0 && rejected.isNotEmpty()) {
            // All-fail — bubble up as Rejected so the caller maps to a
            // single bank-app callback error. Leave persisted state untouched.
            return@withLock StoreResult.Rejected(
                reason = rejectionReason ?: "token_signature_invalid",
            )
        }

        writeStoredTokens(existing.values.toList())
        val newCounter = incrementTxCounter()
        val newBalance = existing.values.sumOf { it.amount }

        if (rejected.isNotEmpty()) {
            return@withLock StoreResult.PartialOk(
                storedCount = newCount,
                rejectedSerials = rejected.map { it.first },
                reason = rejectionReason ?: "partial",
            )
        }
        StoreResult.Ok(
            storedCount = newCount,
            newBalance = newBalance,
            newTxCounter = newCounter,
        )
    }

    override suspend fun offlineBalance(): Long =
        readStoredTokens().sumOf { it.amount }

    override suspend fun listHeldTokens(): List<HeldToken> =
        readStoredTokens().map { rec ->
            HeldToken(
                serial = rec.serial,
                amount = rec.amount,
                currency = rec.currency,
                ncbBic = rec.ncbBic,
                issuedAt = parseInstantOrEpoch(rec.issuedAt),
                expiry = parseInstantOrEpoch(rec.expiry),
            )
        }

    private fun parseInstantOrEpoch(raw: String): Instant =
        runCatching { Instant.parse(raw) }.getOrDefault(Instant.EPOCH)

    override suspend fun txCounter(): Long =
        prefs.getLong(KEY_TX_COUNTER, 0L)

    override suspend fun reset() = mutex.withLock {
        prefs.clear(KEY_TX_COUNTER)
        prefs.clear(KEY_HOLDER_KEYS)
        prefs.clear(KEY_TOKENS)
    }

    // ---- Internal storage helpers -----------------------------------------------

    /** Increments and persists [KEY_TX_COUNTER]. Caller must hold [mutex]. */
    private suspend fun incrementTxCounter(): Long {
        val next = prefs.getLong(KEY_TX_COUNTER, 0L) + 1
        prefs.setLong(KEY_TX_COUNTER, next)
        return next
    }

    private suspend fun readHolderKeys(): Map<String, StoredHolderKey> {
        val raw = prefs.getString(KEY_HOLDER_KEYS, "").ifBlank { return emptyMap() }
        return runCatching {
            Json.decodeFromString(
                MapSerializer(String.serializer(), StoredHolderKey.serializer()),
                raw,
            )
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeHolderKeys(keys: Map<String, StoredHolderKey>) {
        val raw = Json.encodeToString(
            MapSerializer(String.serializer(), StoredHolderKey.serializer()),
            keys,
        )
        prefs.setString(KEY_HOLDER_KEYS, raw)
    }

    private suspend fun readStoredTokens(): List<StoredTokenRecord> {
        val raw = prefs.getString(KEY_TOKENS, "").ifBlank { return emptyList() }
        return runCatching {
            Json.decodeFromString(ListSerializer(StoredTokenRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun writeStoredTokens(tokens: List<StoredTokenRecord>) {
        val raw = Json.encodeToString(ListSerializer(StoredTokenRecord.serializer()), tokens)
        prefs.setString(KEY_TOKENS, raw)
    }

    private companion object {
        const val KEY_TX_COUNTER = "de.se.tx_counter"
        const val KEY_HOLDER_KEYS = "de.se.holder_keys"
        const val KEY_TOKENS = "de.se.tokens"
        const val P256_COMPONENT_LENGTH = 32
    }
}

/** Encrypted prefs row for a single holder keypair. */
@Serializable
private data class StoredHolderKey(
    @SerialName("d") val privateDBase64Url: String,
    @SerialName("jwk") val publicJwk: JsonObject,
)

/**
 * Encrypted prefs row for a single offline DE token. Fields beyond what
 * `OfflineTokenJws` carries (ncbBic / issuedAt / expiry) are populated
 * from the verified token payload at storage time — see
 * `SimulatedSecureElementImpl.storeTokens`.
 */
@Serializable
private data class StoredTokenRecord(
    val serial: String,
    val amount: Long,
    val currency: String,
    val ncbBic: String,
    val holderKeyAlias: String,
    val jws: String,
    val issuedAt: String,
    val expiry: String,
    val storedAt: String,
)

/**
 * EC public key → JWK per RFC 7518 §6.2. P-256 only (the curve every other
 * EC primitive in the wallet uses); throws for any other curve.
 */
private fun ECPublicKey.toJwk(): JsonObject {
    val w = this.w
    val x = encodeUnsignedBigInt(w.affineX, /* width = */ 32)
    val y = encodeUnsignedBigInt(w.affineY, /* width = */ 32)
    return buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("x", x)
        put("y", y)
    }
}

/**
 * Encode an unsigned [BigInteger] as fixed-width big-endian, then
 * base64url-encode without padding. Trims the leading 0x00 sign byte
 * `BigInteger.toByteArray()` adds for positive values whose MSB is set,
 * and left-pads with zeros for shorter values — matching the RFC 7518
 * §6.2.1.2 / §6.2.2.1 layout for EC JWK coordinates and `d` values.
 */
private fun encodeUnsignedBigInt(value: BigInteger, width: Int): String {
    val raw = value.toByteArray()
    val trimmed = if (raw.size == width + 1 && raw[0] == 0.toByte()) {
        raw.copyOfRange(1, raw.size)
    } else {
        raw
    }
    require(trimmed.size <= width) {
        "BigInteger is ${trimmed.size} bytes wide; expected at most $width"
    }
    val out = ByteArray(width)
    trimmed.copyInto(out, destinationOffset = width - trimmed.size)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(out)
}
