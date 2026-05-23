/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

package eu.europa.ec.denfc.di

import eu.europa.ec.denfc.NfcReaderController
import eu.europa.ec.denfc.OfflineTransferReader
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan("eu.europa.ec.denfc")
class NfcDeModule

@Factory
fun provideNfcReaderController(): NfcReaderController = NfcReaderController()

@Factory
fun provideOfflineTransferReader(): OfflineTransferReader = OfflineTransferReader()
