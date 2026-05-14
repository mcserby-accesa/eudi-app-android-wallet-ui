/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.corelogic.config

import android.content.Context
import eu.europa.ec.corelogic.BuildConfig
import eu.europa.ec.corelogic.model.DocumentIdentifier
import eu.europa.ec.delogic.state.WalletStateRepository
import eu.europa.ec.eudi.wallet.EudiWalletConfig
import eu.europa.ec.eudi.wallet.document.CreateDocumentSettings.CredentialPolicy
import eu.europa.ec.eudi.wallet.issue.openid4vci.OpenId4VciManager
import eu.europa.ec.eudi.wallet.issue.openid4vci.dpop.DPopConfig
import eu.europa.ec.eudi.wallet.transfer.openId4vp.ClientIdScheme
import eu.europa.ec.eudi.wallet.transfer.openId4vp.Format
import eu.europa.ec.resourceslogic.R
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class WalletCoreConfigImpl(
    private val context: Context,
    // Accesa: read at issuance time so the upstream OID4VCI client targets the
    // PID issuer the user configured via QR-config, not a hard-coded constant.
    private val walletStateRepository: WalletStateRepository,
) : WalletCoreConfig {

    private var _config: EudiWalletConfig? = null

    override val config: EudiWalletConfig
        get() {
            if (_config == null) {
                _config = EudiWalletConfig {
                    configureDocumentKeyCreation(
                        userAuthenticationRequired = false,
                        userAuthenticationTimeout = 30.seconds,
                        useStrongBoxForKeys = true
                    )
                    configureOpenId4Vp {
                        withClientIdSchemes(
                            listOf(
                                ClientIdScheme.X509SanDns,
                                ClientIdScheme.X509Hash
                            )
                        )
                        withSchemes(
                            listOf(
                                BuildConfig.OPENID4VP_SCHEME,
                                BuildConfig.EUDI_OPENID4VP_SCHEME,
                                BuildConfig.MDOC_OPENID4VP_SCHEME,
                                BuildConfig.HAIP_OPENID4VP_SCHEME
                            )
                        )
                        withFormats(
                            Format.MsoMdoc.ES256, Format.SdJwtVc.ES256
                        )
                    }

                    configureDCAPI {
                        withEnabled(true)
                    }

                    configureReaderTrustStore(
                        context,
                        R.raw.pidissuerca02_cz,
                        R.raw.pidissuerca02_ee,
                        R.raw.pidissuerca02_eu,
                        R.raw.pidissuerca02_lu,
                        R.raw.pidissuerca02_nl,
                        R.raw.pidissuerca02_pt,
                        R.raw.pidissuerca02_ut,
                        R.raw.dc4eu,
                        R.raw.r45_staging,
                        // Accesa: workshop CA used by bank-simulator to sign OID4VP
                        // request JWTs (x509_san_dns scheme). The CA cert lives in
                        // the companion repo at
                        // services/bank-simulator/src/main/resources/workshop-ca/cert.pem
                        // and is bundled here so the wallet trusts request signatures
                        // from any bank-* host issued by this CA. Workshop-only —
                        // remove (or replace with a real per-deployment CA) for any
                        // non-demo build.
                        R.raw.accesa_workshop_ca
                    )
                }
            }
            return _config!!
        }

    override val issuersConfig: List<VciConfig>
        get() {
            // Accesa: when the user has configured a PID issuer via QR-config,
            // route the upstream OID4VCI flow through it exclusively. The fallback
            // below is the unmodified upstream dev configuration so that an
            // unprovisioned APK still functions for the EU reference flow.
            val runtimePidIssuerUrl = runBlocking {
                walletStateRepository.current()?.pidIssuerUrl
            }
            if (runtimePidIssuerUrl != null) {
                return listOf(
                    VciConfig(
                        config = OpenId4VciManager.Config.Builder()
                            .withIssuerUrl(issuerUrl = runtimePidIssuerUrl)
                            .withClientAuthenticationType(OpenId4VciManager.ClientAuthenticationType.AttestationBased)
                            .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                            .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                            .withDPopConfig(DPopConfig.Default)
                            .build(),
                        order = 0
                    )
                )
            }
            return listOf(
                VciConfig(
                    config = OpenId4VciManager.Config.Builder()
                        .withIssuerUrl(issuerUrl = "https://ec.dev.issuer.eudiw.dev")
                        .withClientAuthenticationType(OpenId4VciManager.ClientAuthenticationType.AttestationBased)
                        .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                        .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                        .withDPopConfig(DPopConfig.Default)
                        .build(),
                    order = 0
                ),
                VciConfig(
                    config = OpenId4VciManager.Config.Builder()
                        .withIssuerUrl(issuerUrl = "https://dev.issuer-backend.eudiw.dev")
                        .withClientAuthenticationType(OpenId4VciManager.ClientAuthenticationType.AttestationBased)
                        .withAuthFlowRedirectionURI(BuildConfig.ISSUE_AUTHORIZATION_DEEPLINK)
                        .withParUsage(OpenId4VciManager.Config.ParUsage.IF_SUPPORTED)
                        .withDPopConfig(DPopConfig.Default)
                        .build(),
                    order = 1
                )
            )
        }

    override val documentIssuanceConfig: DocumentIssuanceConfig
        get() = DocumentIssuanceConfig(
            defaultRule = DocumentIssuanceRule(
                policy = CredentialPolicy.RotateUse,
                numberOfCredentials = 1
            ),
            // Accesa: PID is overridden from upstream's OneTimeUse, N=60 to a
            // single rotated credential. See the demo flavor's WalletCoreConfigImpl
            // for the rationale — same protocol contract applies.
            documentSpecificRules = mapOf(
                DocumentIdentifier.MdocPid to DocumentIssuanceRule(
                    policy = CredentialPolicy.RotateUse,
                    numberOfCredentials = 1
                ),
                DocumentIdentifier.SdJwtPid to DocumentIssuanceRule(
                    policy = CredentialPolicy.RotateUse,
                    numberOfCredentials = 1
                ),
            ),
            reissuanceRule = ReIssuanceRule(
                minNumberOfCredentials = 2,
                minExpirationHours = 24,
                backgroundInterval = Duration.ofMinutes(15)
            )
        )

    override val walletProviderHost: String
        get() = "https://dev.wallet-provider.eudiw.dev"
}