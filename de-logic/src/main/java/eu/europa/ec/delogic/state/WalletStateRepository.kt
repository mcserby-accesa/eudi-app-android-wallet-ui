/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.state

import eu.europa.ec.businesslogic.controller.storage.PrefsController
import kotlinx.serialization.json.Json

interface WalletStateRepository {
    suspend fun current(): WalletState?
    suspend fun save(state: WalletState)
    suspend fun clear()
}

class WalletStateRepositoryImpl(
    private val prefsController: PrefsController,
    private val json: Json = DefaultJson,
) : WalletStateRepository {

    override suspend fun current(): WalletState? {
        if (!prefsController.contains(KEY)) return null
        val payload = prefsController.getString(KEY, "")
        if (payload.isBlank()) return null
        return runCatching { json.decodeFromString(WalletState.serializer(), payload) }
            .getOrNull()
    }

    override suspend fun save(state: WalletState) {
        prefsController.setString(KEY, json.encodeToString(WalletState.serializer(), state))
    }

    override suspend fun clear() {
        prefsController.clear(KEY)
    }

    private companion object {
        const val KEY = "de.walletState"
        val DefaultJson = Json { ignoreUnknownKeys = true }
    }
}
