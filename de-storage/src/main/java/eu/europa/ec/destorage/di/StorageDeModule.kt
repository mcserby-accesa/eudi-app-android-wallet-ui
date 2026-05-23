/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.destorage.di

import eu.europa.ec.businesslogic.controller.storage.PrefsController
import eu.europa.ec.delogic.delivery.SerialStatusLookup
import eu.europa.ec.delogic.jws.OfflineTokenVerifier
import eu.europa.ec.delogic.jws.TransferProofVerifier
import eu.europa.ec.destorage.SimulatedSecureElement
import eu.europa.ec.destorage.SimulatedSecureElementImpl
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@Configuration
@ComponentScan("eu.europa.ec.destorage")
class StorageDeModule

@Single
fun provideSimulatedSecureElement(
    prefs: PrefsController,
    verifier: OfflineTokenVerifier,
    transferProofVerifier: TransferProofVerifier,
    serialStatusClient: SerialStatusLookup,
): SimulatedSecureElement = SimulatedSecureElementImpl(
    prefs = prefs,
    verifier = verifier,
    transferProofVerifier = transferProofVerifier,
    serialStatusClient = serialStatusClient,
)
