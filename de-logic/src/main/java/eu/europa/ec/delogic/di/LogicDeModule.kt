/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.delogic.di

import eu.europa.ec.businesslogic.controller.storage.PrefsController
import eu.europa.ec.delogic.envelope.EnvelopeDecoder
import eu.europa.ec.delogic.jwt.AuthorizationJwtBuilder
import eu.europa.ec.delogic.jwt.NonceProvider
import eu.europa.ec.delogic.jwt.SecureRandomNonceProvider
import eu.europa.ec.delogic.state.WalletStateRepository
import eu.europa.ec.delogic.state.WalletStateRepositoryImpl
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
fun provideNonceProvider(): NonceProvider = SecureRandomNonceProvider()

@Factory
fun provideAuthorizationJwtBuilder(
    nonceProvider: NonceProvider,
): AuthorizationJwtBuilder = AuthorizationJwtBuilder(nonceProvider = nonceProvider)
