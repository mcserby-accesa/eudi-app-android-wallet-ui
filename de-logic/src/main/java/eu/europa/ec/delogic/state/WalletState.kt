/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.state

import kotlinx.serialization.Serializable

/**
 * Persistent state the wallet keeps for Digital Euro flows. Bank-agnostic.
 *
 * `pidIssuerUrl` is set by the first-launch QR-config screen.
 *
 * `deviceKeyAlias` and `deviceKeyPublicJwk` point at the StrongBox-bound key
 * the EUDI wallet-core SDK creates for the held PID. They become populated
 * after PID issuance — see [`mobile-wallet.md` §First launch] in the
 * companion specs repo.
 */
@Serializable
data class WalletState(
    val pidIssuerUrl: String,
    val deviceKeyAlias: String? = null,
    val deviceKeyPublicJwk: String? = null,
)
