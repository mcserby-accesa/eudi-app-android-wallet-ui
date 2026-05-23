/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * AID + APDU instruction constants for the offline-token transfer
 * protocol (ADR 0011 §6). One file so the sender's reader-mode side
 * and the recipient's HCE service share a single source of truth.
 */

package eu.europa.ec.denfc

/**
 * AID = ASCII "F0EUDE4B" (8 bytes). 0xF0 prefix makes it a
 * proprietary AID per ISO 7816-4 §8.2.1.2. Must match
 * `de_offline_transfer_apdu_service.xml`'s `<aid-filter>`.
 */
val OFFLINE_TRANSFER_AID: ByteArray = byteArrayOf(
    0xF0.toByte(), 0x45, 0x55, 0x44, 0x45, 0x34, 0x42,
)

/**
 * SELECT-AID command: `00 A4 04 00 LL <AID>`. Returned by the HCE
 * service with `9000` to confirm we're ready to receive the next
 * INS-coded payload.
 */
fun buildSelectAidApdu(aid: ByteArray = OFFLINE_TRANSFER_AID): ByteArray = byteArrayOf(
    0x00, // CLA
    0xA4.toByte(), // INS — SELECT
    0x04, // P1 — by AID
    0x00, // P2
    aid.size.toByte(),
) + aid

/** Class byte for our proprietary INS commands. */
const val CLA_DE_OFFLINE: Byte = 0x80.toByte()

/** transferOffer — sender → recipient, payload describes the tokens to be sent. */
const val INS_TRANSFER_OFFER: Byte = 0x01

/** transferCommit — sender → recipient, payload carries the token JWSes + TransferProofs. */
const val INS_TRANSFER_COMMIT: Byte = 0x02

/** R-APDU status words. */
val SW_OK: ByteArray = byteArrayOf(0x90.toByte(), 0x00)
val SW_FILE_NOT_FOUND: ByteArray = byteArrayOf(0x6A, 0x82.toByte())
val SW_WRONG_LENGTH: ByteArray = byteArrayOf(0x67, 0x00)
val SW_CONDITIONS_NOT_SATISFIED: ByteArray = byteArrayOf(0x69, 0x85.toByte())
val SW_INCORRECT_DATA: ByteArray = byteArrayOf(0x6A, 0x80.toByte())
val SW_INTERNAL_ERROR: ByteArray = byteArrayOf(0x6F, 0x00)

/** True iff [apdu] is a SELECT-AID that names our AID. */
fun isSelectAid(apdu: ByteArray, aid: ByteArray = OFFLINE_TRANSFER_AID): Boolean {
    if (apdu.size < 5) return false
    if (apdu[0] != 0x00.toByte() || apdu[1] != 0xA4.toByte()) return false
    if (apdu[2] != 0x04.toByte() || apdu[3] != 0x00.toByte()) return false
    val lc = apdu[4].toInt() and 0xff
    if (5 + lc > apdu.size) return false
    val target = apdu.copyOfRange(5, 5 + lc)
    return target.contentEquals(aid)
}
