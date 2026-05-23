/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * ContentProvider that exposes the wallet's INCOMING_PENDING count to
 * sibling apps on the same device. The bank-app queries this from its
 * account screen so it can show a "Sync N received tokens" CTA after
 * an NFC tap, without firing the eudi-de-authorize:// intent each time
 * (which would flash the wallet UI on every poll).
 *
 * URI: `content://eu.europa.ec.euidi.pending/incoming`
 * Returns: one row with columns (count, amount_cents, expires_at_ms,
 * serials_csv). count is the number of INCOMING_PENDING tokens whose
 * transferExpiry is still in the future. amount_cents is their sum.
 * expires_at_ms is the earliest expiry of the lot (or null if none).
 * serials_csv is the comma-separated serial list — the bank-app pins
 * it back into the eudi-de-authorize://?envelope=… `serials` field so
 * the wallet redeems exactly the tokens the bank-app saw.
 *
 * Workshop security stance: exported, no permission. Reveals only "this
 * device has N tokens awaiting sync"; no PII. Production hardening
 * would gate behind a wallet-defined signature-level permission.
 */

package eu.europa.ec.defeature.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import eu.europa.ec.destorage.SimulatedSecureElement
import eu.europa.ec.destorage.TokenState
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

class PendingIncomingProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx: Context = context ?: return emptyCursor()
        val se: SimulatedSecureElement = runCatching {
            GlobalContext.get().get<SimulatedSecureElement>()
        }.getOrElse { return emptyCursor() }

        val rows = runBlocking {
            se.listHeldTokens().filter { it.state == TokenState.INCOMING_PENDING }
        }
        val now = System.currentTimeMillis()
        val pending = rows.filter { tok ->
            val exp = tok.transferExpiry?.toEpochMilli() ?: return@filter false
            exp > now
        }

        val cursor = MatrixCursor(COLUMNS)
        cursor.addRow(
            arrayOf<Any?>(
                pending.size,
                pending.sumOf { it.amount },
                pending.mapNotNull { it.transferExpiry?.toEpochMilli() }.minOrNull(),
                pending.joinToString(",") { it.serial },
            ),
        )
        return cursor
    }

    private fun emptyCursor(): Cursor =
        MatrixCursor(COLUMNS).apply { addRow(arrayOf<Any?>(0, 0L, null, "")) }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.eu.europa.ec.euidi.pending.incoming"

    // Read-only provider; mutating methods are no-ops by design.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "eu.europa.ec.euidi.pending"
        const val PATH = "incoming"
        const val COLUMN_COUNT = "count"
        const val COLUMN_AMOUNT_CENTS = "amount_cents"
        const val COLUMN_EXPIRES_AT_MS = "expires_at_ms"
        const val COLUMN_SERIALS_CSV = "serials_csv"

        private val COLUMNS = arrayOf(
            COLUMN_COUNT,
            COLUMN_AMOUNT_CENTS,
            COLUMN_EXPIRES_AT_MS,
            COLUMN_SERIALS_CSV,
        )
    }
}
