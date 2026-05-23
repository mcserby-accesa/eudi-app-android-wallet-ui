/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.di

import eu.europa.ec.businesslogic.controller.storage.PrefsController
import eu.europa.ec.delogic.config.WalletConfigDecoder
import eu.europa.ec.delogic.delivery.BankSerialStatusClient
import eu.europa.ec.delogic.delivery.SerialStatusLookup
import eu.europa.ec.delogic.delivery.WithdrawDeliveryClient
import eu.europa.ec.delogic.envelope.EnvelopeDecoder
import eu.europa.ec.delogic.jws.BundledNcbTrustStore
import eu.europa.ec.delogic.jws.NcbTrustStore
import eu.europa.ec.delogic.jws.OfflineTokenVerifier
import eu.europa.ec.delogic.jws.OfflineTokenVerifierImpl
import eu.europa.ec.delogic.jws.TransferProofVerifier
import eu.europa.ec.delogic.jws.TransferProofVerifierImpl
import eu.europa.ec.delogic.jwt.AuthorizationJwtBuilder
import eu.europa.ec.delogic.jwt.NonceProvider
import eu.europa.ec.delogic.jwt.SecureRandomNonceProvider
import eu.europa.ec.delogic.state.WalletStateRepository
import eu.europa.ec.delogic.state.WalletStateRepositoryImpl
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import io.ktor.client.HttpClient
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@Configuration
@ComponentScan("eu.europa.ec.delogic")
class LogicDeModule

@Single
fun provideWalletStateRepository(prefsController: PrefsController): WalletStateRepository =
    WalletStateRepositoryImpl(prefsController)

@Factory
fun provideEnvelopeDecoder(): EnvelopeDecoder = EnvelopeDecoder()

@Factory
fun provideWalletConfigDecoder(): WalletConfigDecoder = WalletConfigDecoder()

@Factory
fun provideNonceProvider(): NonceProvider = SecureRandomNonceProvider()

@Factory
fun provideAuthorizationJwtBuilder(
    nonceProvider: NonceProvider,
): AuthorizationJwtBuilder = AuthorizationJwtBuilder(nonceProvider = nonceProvider)

@Single
fun provideNcbTrustStore(resourceProvider: ResourceProvider): NcbTrustStore =
    BundledNcbTrustStore(resourceProvider = resourceProvider)

@Factory
fun provideOfflineTokenVerifier(trustStore: NcbTrustStore): OfflineTokenVerifier =
    OfflineTokenVerifierImpl(trustStore = trustStore)

@Factory
fun provideWithdrawDeliveryClient(httpClient: HttpClient): WithdrawDeliveryClient =
    WithdrawDeliveryClient(httpClient = httpClient)

@Factory
fun provideTransferProofVerifier(): TransferProofVerifier = TransferProofVerifierImpl()

@Single
fun provideSerialStatusLookup(httpClient: HttpClient): SerialStatusLookup =
    BankSerialStatusClient(httpClient = httpClient)
