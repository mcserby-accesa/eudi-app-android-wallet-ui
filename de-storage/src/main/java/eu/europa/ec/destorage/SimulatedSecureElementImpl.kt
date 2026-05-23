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
import eu.europa.ec.delogic.delivery.SerialStatus
import eu.europa.ec.delogic.delivery.SerialStatusLookup
import eu.europa.ec.delogic.jws.DerSignatureEncoding
import eu.europa.ec.delogic.jws.OfflineTokenVerifier
import eu.europa.ec.delogic.jws.OfflineTokenVerifyResult
import eu.europa.ec.delogic.jws.TransferProofVerifier
import eu.europa.ec.delogic.jws.TransferProofVerifyResult
import eu.europa.ec.delogic.jwt.NonceProvider
import eu.europa.ec.delogic.jwt.SecureRandomNonceProvider
import eu.europa.ec.delogic.jwt.Signer
import eu.europa.ec.delogic.jwt.TransferProofBuilder
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
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

class SimulatedSecureElementImpl(
    private val prefs: PrefsController,
    private val verifier: OfflineTokenVerifier,
    private val transferProofVerifier: TransferProofVerifier,
    private val serialStatusClient: SerialStatusLookup,
    private val clock: Clock = Clock.systemUTC(),
    private val aliasFactory: () -> String = { UUID.randomUUID().toString() },
    private val nonceProvider: NonceProvider = SecureRandomNonceProvider(),
) : SimulatedSecureElement {

    /**
     * Coarse-grained serialisation across all SE operations. The real
     * SE applet would have its own atomic-counter primitive; for the
     * simulated SE we pay the lock cost to keep `txCounter` monotonic
     * even under concurrent withdraw + holdings-list reads.
     */
    private val mutex = Mutex()

    private val transferProofBuilder = TransferProofBuilder(
        clock = clock,
        nonceProvider = nonceProvider,
    )

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

        incrementTxCounter()
        HolderKeyHandle(alias = alias, publicJwk = publicJwk)
    }

    override suspend fun storeTokens(
        handle: HolderKeyHandle,
        tokens: List<OfflineTokenJws>,
        reconciliationUrl: String?,
    ): StoreResult = mutex.withLock {
        val keys = readHolderKeys()
        if (handle.alias !in keys) {
            return@withLock StoreResult.Rejected(reason = "unknown_holder_alias")
        }

        val existing = readStoredTokens().associateBy { it.serial }.toMutableMap()
        val now = clock.instant()
        var newCount = 0
        val rejected = mutableListOf<Pair<String, String>>()
        var rejectionReason: String? = null

        for (token in tokens) {
            if (token.serial in existing) continue

            when (val verify = verifier.verify(token.jws, handle.publicJwk)) {
                is OfflineTokenVerifyResult.Ok -> {
                    val payload = verify.payload
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
                        state = TokenState.LIVE,
                        transferExpiry = null,
                        transferProofJws = null,
                        reconciliationUrl = reconciliationUrl,
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
            return@withLock StoreResult.Rejected(
                reason = rejectionReason ?: "token_signature_invalid",
            )
        }

        writeStoredTokens(existing.values.toList())
        val newCounter = incrementTxCounter()
        val newBalance = liveBalance(existing.values)

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
        liveBalance(readStoredTokens())

    override suspend fun listHeldTokens(): List<HeldToken> =
        readStoredTokens()
            .filter { it.state != TokenState.CONSUMED }
            .map { rec ->
                HeldToken(
                    serial = rec.serial,
                    amount = rec.amount,
                    currency = rec.currency,
                    ncbBic = rec.ncbBic,
                    issuedAt = parseInstantOrEpoch(rec.issuedAt),
                    expiry = parseInstantOrEpoch(rec.expiry),
                    state = rec.state,
                    transferExpiry = rec.transferExpiry?.let { parseInstantOrEpoch(it) },
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

    // ─── M4b/c surface ────────────────────────────────────────────────────────

    override suspend fun signTransferProofs(
        serials: List<String>,
        recipientHolderPub: JsonObject,
        currency: String,
    ): List<TransferProofJws> = mutex.withLock {
        if (serials.isEmpty()) return@withLock emptyList()

        val existing = readStoredTokens().associateBy { it.serial }.toMutableMap()
        val keys = readHolderKeys()

        val records = serials.map { serial ->
            val rec = existing[serial]
                ?: throw IllegalStateException("unknown_serial:$serial")
            check(rec.state == TokenState.LIVE) {
                "token_not_live:$serial:${rec.state}"
            }
            check(rec.currency == currency) {
                "currency_mismatch:$serial:${rec.currency}"
            }
            rec
        }

        val proofs = mutableListOf<TransferProofJws>()
        for (rec in records) {
            val holderKey = keys[rec.holderKeyAlias]
                ?: throw IllegalStateException("holder_key_missing:${rec.holderKeyAlias}")

            val counter = incrementTxCounter()
            val built = transferProofBuilder.build(
                tokenSerial = rec.serial,
                fromHolderPub = holderKey.publicJwk,
                toHolderPub = recipientHolderPub,
                amount = rec.amount,
                currency = rec.currency,
                senderTxCounter = counter,
            )
            val rawSig = signWithHolderPrivateKey(holderKey, built.signingInput)
            val jws = transferProofBuilder.assemble(built, rawSig)

            existing[rec.serial] = rec.copy(
                state = TokenState.OUTGOING_PENDING,
                transferExpiry = built.expiry.toString(),
                transferProofJws = jws,
            )
            proofs += TransferProofJws(tokenSerial = rec.serial, jws = jws)
        }

        writeStoredTokens(existing.values.toList())
        proofs
    }

    override suspend fun acceptIncoming(
        ourHolderPub: HolderKeyHandle,
        items: List<IncomingItem>,
    ): AcceptResult = mutex.withLock {
        val keys = readHolderKeys()
        if (ourHolderPub.alias !in keys) {
            return@withLock AcceptResult.AllRejected(
                items.map { Rejection(it.token.serial, RejectionReason.TO_HOLDER_PUB_MISMATCH) },
            )
        }
        val existing = readStoredTokens().associateBy { it.serial }.toMutableMap()

        val rejected = mutableListOf<Rejection>()
        var accepted = 0
        val now = clock.instant()

        for (item in items) {
            val serial = item.token.serial
            if (serial in existing) {
                rejected += Rejection(serial, RejectionReason.DUPLICATE_SERIAL)
                continue
            }
            if (item.transferProof.tokenSerial != serial) {
                rejected += Rejection(serial, RejectionReason.SERIAL_MISMATCH)
                continue
            }

            val tokenVerify = verifier.verify(item.token.jws, expectedHolderPub = ourHolderPub.publicJwk)
            // The token was minted for the SENDER's holderPub, not ours. The
            // OfflineTokenVerifier returns HolderPubMismatch for that case
            // which is fine for the M4a path (we generated the key) but
            // wrong for M4b incoming — we need to verify against whatever
            // the token's payload says, then check the proof binds the
            // token to US separately. Re-verify with the token's own
            // payload key as the expected JWK.
            val tokenOk = when (tokenVerify) {
                is OfflineTokenVerifyResult.Ok -> tokenVerify.payload
                is OfflineTokenVerifyResult.Failure.HolderPubMismatch -> {
                    // Recover by verifying with the JWS-embedded holderPub.
                    extractTokenHolderPubAndReverify(item.token.jws)
                }
                is OfflineTokenVerifyResult.Failure.SignatureInvalid -> {
                    rejected += Rejection(serial, RejectionReason.TOKEN_SIGNATURE_INVALID)
                    continue
                }
                is OfflineTokenVerifyResult.Failure.Malformed -> {
                    rejected += Rejection(serial, RejectionReason.TOKEN_SIGNATURE_INVALID)
                    continue
                }
                is OfflineTokenVerifyResult.Failure.Expired -> {
                    rejected += Rejection(serial, RejectionReason.TOKEN_EXPIRED)
                    continue
                }
            }
            if (tokenOk == null) {
                rejected += Rejection(serial, RejectionReason.TOKEN_SIGNATURE_INVALID)
                continue
            }

            val proofVerify = transferProofVerifier.verify(item.transferProof.jws)
            when (proofVerify) {
                is TransferProofVerifyResult.Ok -> {
                    val proof = proofVerify.payload
                    if (proof.tokenSerial != serial) {
                        rejected += Rejection(serial, RejectionReason.SERIAL_MISMATCH); continue
                    }
                    if (!ecJwkEquals(proof.fromHolderPub, tokenOk.holderPub)) {
                        rejected += Rejection(serial, RejectionReason.TRANSFER_PROOF_SIGNATURE_INVALID)
                        continue
                    }
                    if (!ecJwkEquals(proof.toHolderPub, ourHolderPub.publicJwk)) {
                        rejected += Rejection(serial, RejectionReason.TO_HOLDER_PUB_MISMATCH)
                        continue
                    }
                }
                is TransferProofVerifyResult.Failure.Expired -> {
                    rejected += Rejection(serial, RejectionReason.TRANSFER_PROOF_EXPIRED)
                    continue
                }
                is TransferProofVerifyResult.Failure.SignatureInvalid,
                is TransferProofVerifyResult.Failure.Malformed -> {
                    rejected += Rejection(serial, RejectionReason.TRANSFER_PROOF_SIGNATURE_INVALID)
                    continue
                }
            }

            existing[serial] = StoredTokenRecord(
                serial = serial,
                amount = tokenOk.amount,
                currency = tokenOk.currency,
                ncbBic = tokenOk.ncbBic,
                holderKeyAlias = ourHolderPub.alias,
                jws = item.token.jws,
                issuedAt = tokenOk.issuedAt.toString(),
                expiry = tokenOk.expiry.toString(),
                storedAt = now.toString(),
                state = TokenState.INCOMING_PENDING,
                transferExpiry = (proofVerify as TransferProofVerifyResult.Ok)
                    .payload.expiry.toString(),
                transferProofJws = item.transferProof.jws,
                reconciliationUrl = item.reconciliationUrl,
            )
            accepted += 1
        }

        if (accepted > 0) {
            writeStoredTokens(existing.values.toList())
            incrementTxCounter()
        }

        when {
            rejected.isEmpty() -> AcceptResult.Ok(accepted)
            accepted == 0 -> AcceptResult.AllRejected(rejected)
            else -> AcceptResult.PartialOk(accepted, rejected)
        }
    }

    override suspend fun serialStatus(serial: String, reconciliationUrl: String): SerialStatus =
        serialStatusClient.status(reconciliationUrl, serial)

    override suspend fun reconcilePending(): ReconcileResult {
        // Snapshot under lock; HTTP calls happen outside the lock to
        // avoid blocking concurrent reads of unrelated SE state.
        val (snapshot, now) = mutex.withLock {
            readStoredTokens() to clock.instant()
        }
        val ready = snapshot.filter {
            it.transferExpiry != null &&
                runCatching { Instant.parse(it.transferExpiry) }.getOrNull()
                    ?.isBefore(now) == true &&
                it.state in setOf(TokenState.OUTGOING_PENDING, TokenState.INCOMING_PENDING)
        }
        if (ready.isEmpty()) return ReconcileResult(emptyList(), emptyList(), emptyList(), emptyList())

        val restoredOutgoing = mutableListOf<String>()
        val finalisedOutgoing = mutableListOf<String>()
        val finalisedIncoming = mutableListOf<String>()
        val droppedIncoming = mutableListOf<String>()

        val transitions = mutableMapOf<String, TokenState>()  // serial → next state (LIVE or CONSUMED)
        for (rec in ready) {
            val url = rec.reconciliationUrl ?: continue
            val status = serialStatusClient.status(url, rec.serial)
            when (rec.state) {
                TokenState.OUTGOING_PENDING -> when (status) {
                    SerialStatus.SPENT -> {
                        transitions[rec.serial] = TokenState.CONSUMED
                        finalisedOutgoing += rec.serial
                    }
                    SerialStatus.UNSPENT -> {
                        transitions[rec.serial] = TokenState.LIVE
                        restoredOutgoing += rec.serial
                    }
                    SerialStatus.UNKNOWN -> {} // leave as-is; retry next pass
                }
                TokenState.INCOMING_PENDING -> when (status) {
                    SerialStatus.SPENT -> {
                        // Recipient already synced (or sender's tap won the race).
                        transitions[rec.serial] = TokenState.CONSUMED
                        finalisedIncoming += rec.serial
                    }
                    SerialStatus.UNSPENT -> {
                        transitions[rec.serial] = TokenState.CONSUMED
                        droppedIncoming += rec.serial
                    }
                    SerialStatus.UNKNOWN -> {}
                }
                else -> {} // not reachable due to filter above
            }
        }

        if (transitions.isNotEmpty()) {
            mutex.withLock {
                val current = readStoredTokens().toMutableList()
                for (i in current.indices) {
                    val s = current[i].serial
                    val next = transitions[s] ?: continue
                    current[i] = current[i].copy(
                        state = next,
                        transferExpiry = if (next == TokenState.LIVE) null else current[i].transferExpiry,
                        transferProofJws = if (next == TokenState.LIVE) null else current[i].transferProofJws,
                    )
                }
                writeStoredTokens(current)
                incrementTxCounter()
            }
        }
        return ReconcileResult(
            restoredOutgoing = restoredOutgoing,
            finalisedOutgoing = finalisedOutgoing,
            finalisedIncoming = finalisedIncoming,
            droppedIncoming = droppedIncoming,
        )
    }

    override suspend fun buildSelfRedeem(amount: Long, currency: String): SelfRedeemBundle? = mutex.withLock {
        val keys = readHolderKeys()
        val existing = readStoredTokens().associateBy { it.serial }.toMutableMap()
        val live = existing.values
            .filter { it.state == TokenState.LIVE && it.currency == currency }
            .sortedByDescending { it.storedAt }

        // LIFO greedy selection — only attempt if an exact sum is achievable.
        val picked = mutableListOf<StoredTokenRecord>()
        var remaining = amount
        for (rec in live) {
            if (remaining == 0L) break
            if (rec.amount <= remaining) {
                picked += rec
                remaining -= rec.amount
            }
        }
        if (remaining != 0L) return@withLock null

        val tokens = mutableListOf<OfflineTokenJws>()
        val proofs = mutableListOf<TransferProofJws>()
        for (rec in picked) {
            val holderKey = keys[rec.holderKeyAlias]
                ?: throw IllegalStateException("holder_key_missing:${rec.holderKeyAlias}")

            val counter = incrementTxCounter()
            val built = transferProofBuilder.build(
                tokenSerial = rec.serial,
                fromHolderPub = holderKey.publicJwk,
                toHolderPub = holderKey.publicJwk, // self-loopback per ADR 0011 §4
                amount = rec.amount,
                currency = rec.currency,
                senderTxCounter = counter,
            )
            val rawSig = signWithHolderPrivateKey(holderKey, built.signingInput)
            val jws = transferProofBuilder.assemble(built, rawSig)

            tokens += OfflineTokenJws(rec.serial, rec.amount, rec.currency, rec.jws)
            proofs += TransferProofJws(rec.serial, jws)
        }

        SelfRedeemBundle(tokens = tokens, transferProofs = proofs, totalAmount = amount)
    }

    override suspend fun commitRedeemed(serials: List<String>) = mutex.withLock {
        if (serials.isEmpty()) return@withLock
        val drop = serials.toSet()
        val remaining = readStoredTokens().filterNot { it.serial in drop }
        writeStoredTokens(remaining)
        incrementTxCounter()
    }

    // ─── Internal storage helpers ─────────────────────────────────────────────

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
            // ignoreUnknownKeys lets us evolve the schema; default values on
            // new fields (state / transferExpiry / transferProofJws /
            // reconciliationUrl) keep pre-M4b/c-stored rows readable.
            LenientJson.decodeFromString(ListSerializer(StoredTokenRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private suspend fun writeStoredTokens(tokens: List<StoredTokenRecord>) {
        val raw = Json.encodeToString(ListSerializer(StoredTokenRecord.serializer()), tokens)
        prefs.setString(KEY_TOKENS, raw)
    }

    private fun liveBalance(records: Iterable<StoredTokenRecord>): Long =
        records.filter { it.state == TokenState.LIVE }.sumOf { it.amount }

    /**
     * Sign [data] with the EC P-256 private key referenced by [key]. Returns
     * the JWS-form `r || s` 64-byte raw signature ready for the third
     * compact-JWS segment.
     */
    private fun signWithHolderPrivateKey(key: StoredHolderKey, data: ByteArray): ByteArray {
        val privateKey = decodeEcPrivateKey(key.privateDBase64Url)
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
        return DerSignatureEncoding.decodeP256(der)
    }

    private fun decodeEcPrivateKey(privateDBase64Url: String): java.security.interfaces.ECPrivateKey {
        val dBytes = Base64.getUrlDecoder().decode(privateDBase64Url)
        val d = BigInteger(1, dBytes)
        val params: ECParameterSpec = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val spec = ECPrivateKeySpec(d, params)
        return KeyFactory.getInstance("EC").generatePrivate(spec)
            as java.security.interfaces.ECPrivateKey  // KeyFactory returns PrivateKey; we know it's ECPrivateKey.
    }

    /**
     * Re-verify a token JWS by extracting the embedded `holderPub` from the
     * payload and passing it back as the expected key. Used by [acceptIncoming]:
     * the recipient doesn't own the sender's holderPub, so the first verify
     * pass returns HolderPubMismatch. The second pass with the JWS's own
     * `holderPub` proves the token is genuinely signed against the
     * sender-bound key, then the TransferProof check binds the rest.
     */
    private fun extractTokenHolderPubAndReverify(jws: String): eu.europa.ec.delogic.jws.OfflineTokenPayload? {
        val segments = jws.split(".")
        if (segments.size != 3) return null
        val payloadJson = runCatching {
            val raw = Base64.getUrlDecoder().decode(segments[1]).toString(Charsets.UTF_8)
            Json.parseToJsonElement(raw)
        }.getOrNull() ?: return null
        val embeddedHolderPub = runCatching {
            (payloadJson as JsonObject)["holderPub"] as JsonObject
        }.getOrNull() ?: return null
        return when (val v = verifier.verify(jws, embeddedHolderPub)) {
            is OfflineTokenVerifyResult.Ok -> v.payload
            else -> null
        }
    }

    private fun ecJwkEquals(a: JsonObject, b: JsonObject): Boolean {
        val members = setOf("kty", "crv", "x", "y")
        return members.all { name -> a[name] == b[name] }
    }

    private companion object {
        const val KEY_TX_COUNTER = "de.se.tx_counter"
        const val KEY_HOLDER_KEYS = "de.se.holder_keys"
        const val KEY_TOKENS = "de.se.tokens"
        const val P256_COMPONENT_LENGTH = 32

        val LenientJson = Json { ignoreUnknownKeys = true; isLenient = false }
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
 * `OfflineTokenJws` carries (ncbBic / issuedAt / expiry / state / proof
 * data) are populated from the verified token payload at storage time.
 *
 * Backward-compat: every M4b/c column has a default so pre-PR23 rows
 * (stored without these fields) deserialise as LIVE, no pending state,
 * no proof, no reconciliationUrl. The reconcile path no-ops on rows
 * with a null reconciliationUrl.
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
    val state: TokenState = TokenState.LIVE,
    val transferExpiry: String? = null,
    val transferProofJws: String? = null,
    val reconciliationUrl: String? = null,
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
