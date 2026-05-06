/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Bridge from the de-logic-level `Signer` interface to the EUDI wallet-core
 * SDK's per-document signing primitives.
 */

package eu.europa.ec.defeature.ui.authorize

import eu.europa.ec.corelogic.controller.WalletCoreDocumentsController
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.corelogic.model.toDocumentIdentifier
import eu.europa.ec.delogic.jwt.JoseSignatureEncoding
import eu.europa.ec.delogic.jwt.Signer
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.koin.core.annotation.Factory
import org.multipaz.securearea.UnlockReason
import java.time.Instant

/**
 * Locates the held PID and exposes its public JWK plus a [Signer] backed by the
 * StrongBox-bound credential the upstream OID4VCI flow created at issuance.
 *
 * Per `specs/protocols/de-wallet-app-api.md` and the M1 single-key decision,
 * PID `documentSpecificRules` is overridden to `RotateUse, 1` — there is one
 * credential whose public key is both the `cnf.jwk` the bank pinned at
 * onboarding and the key that signs every AUTHORIZE_OPERATION.
 */
@Factory
class PidCredentialSigner(
    private val walletCoreDocumentsController: WalletCoreDocumentsController,
) {

    /**
     * Resolves the PID, fetches its single credential, and returns the
     * material the JWT builder needs: the public key as a JWK plus a [Signer]
     * that signs JWS input bytes via `SecureArea.sign(...)`.
     *
     * Throws [WalletNotProvisionedException] when no PID is held — caller is
     * expected to translate this into the protocol's `wallet_not_provisioned`
     * callback error.
     */
    suspend fun resolveOrThrow(): Resolved {
        val pid = walletCoreDocumentsController.getAllIssuedDocuments()
            .firstOrNull { it.toDocumentIdentifier().isPid() }
            ?: throw WalletNotProvisionedException()

        val credential = pid.findCredential(Instant.now())
            ?: throw WalletNotProvisionedException()

        val secureArea = credential.secureArea
        val alias = credential.alias
        val publicKey = credential.getAttestation().publicKey
        val publicJwk: JsonObject = publicKey.toJwk(buildJsonObject { })

        val signer = Signer { signingInput ->
            val signature = secureArea.sign(
                alias = alias,
                dataToSign = signingInput,
                unlockReason = UnlockReason.Unspecified,
            )
            JoseSignatureEncoding.encodeP256(r = signature.r, s = signature.s)
        }

        return Resolved(
            document = pid,
            publicJwk = publicJwk,
            signer = signer,
        )
    }

    data class Resolved(
        val document: IssuedDocument,
        val publicJwk: JsonObject,
        val signer: Signer,
    )

    class WalletNotProvisionedException : Exception("No PID issued in this wallet")
}

private fun DocumentIdentifier.isPid(): Boolean =
    this is DocumentIdentifier.MdocPid || this is DocumentIdentifier.SdJwtPid
