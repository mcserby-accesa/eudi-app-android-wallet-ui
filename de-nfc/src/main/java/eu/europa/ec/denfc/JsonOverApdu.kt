/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Encode wire messages ([TransferOffer], [TransferAck], etc.) into
 * extended-length APDUs and parse incoming APDUs back into messages.
 *
 * Extended-length APDU shape (ISO 7816-4 §5.1):
 *   CLA INS P1 P2 00 LcHi LcLo <data … (Lc bytes)>
 * Standard APDU (≤256B):
 *   CLA INS P1 P2 Lc <data …>
 *
 * The codec emits extended-length unconditionally for OFFER + COMMIT
 * since their JSON payloads tend to exceed 255 bytes once tokens are
 * embedded. SELECT-AID stays standard-length (handled in
 * [OfflineTransferAid]).
 */

package eu.europa.ec.denfc

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Singleton-style codec — the JSON config matches the rest of de-logic
 * (encodeDefaults = false; ignoreUnknownKeys = true so future fields
 * don't break older wallets).
 */
object JsonOverApdu {

    val codec: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** Build a C-APDU: `CLA INS 00 00 00 LcHi LcLo <data>`. */
    fun buildCommandApdu(ins: Byte, data: ByteArray): ByteArray {
        require(data.size <= MAX_EXTENDED_LC) {
            "APDU data field is ${data.size} bytes; max $MAX_EXTENDED_LC"
        }
        val header = byteArrayOf(CLA_DE_OFFLINE, ins, 0x00, 0x00)
        val lcExt = byteArrayOf(
            0x00,
            ((data.size shr 8) and 0xff).toByte(),
            (data.size and 0xff).toByte(),
        )
        return header + lcExt + data
    }

    /**
     * Parse the data field out of a C-APDU produced by [buildCommandApdu].
     * Returns null if the header / length envelope is malformed. The
     * INS byte is exposed so the HCE service can dispatch.
     */
    fun parseCommandApdu(apdu: ByteArray): ParsedCommand? {
        if (apdu.size < 7) return null // CLA INS P1 P2 + 3-byte extended Lc
        if (apdu[0] != CLA_DE_OFFLINE) return null
        if (apdu[4] != 0x00.toByte()) return null // extended-length marker
        val lc = ((apdu[5].toInt() and 0xff) shl 8) or (apdu[6].toInt() and 0xff)
        if (lc <= 0 || 7 + lc > apdu.size) return null
        val data = apdu.copyOfRange(7, 7 + lc)
        return ParsedCommand(ins = apdu[1], data = data)
    }

    /** Pack [body] + [SW_OK] into an R-APDU. */
    fun successResponse(body: ByteArray): ByteArray = body + SW_OK

    /** Pack [body] + an error status word into an R-APDU. */
    fun errorResponse(body: ByteArray, sw: ByteArray): ByteArray = body + sw

    /**
     * Encode a Kotlin Serializable message into UTF-8 JSON bytes ready
     * for an APDU data field. Routes through an explicit `serializer<T>()`
     * call so the cross-module reified inference reliably picks the
     * generated KSerializer.
     */
    inline fun <reified T> encodeMessage(value: T): ByteArray =
        codec.encodeToString(serializer<T>(), value).toByteArray(Charsets.UTF_8)

    /** Decode UTF-8 JSON APDU bytes into a Kotlin object. Throws on shape mismatch. */
    inline fun <reified T> decodeMessage(bytes: ByteArray): T =
        codec.decodeFromString(serializer<T>(), bytes.toString(Charsets.UTF_8))

    private const val MAX_EXTENDED_LC = 0xFFFF
}

data class ParsedCommand(val ins: Byte, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ParsedCommand && ins == other.ins && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * ins.hashCode() + data.contentHashCode()
}
