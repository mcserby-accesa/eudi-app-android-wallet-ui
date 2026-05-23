/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

import com.android.build.api.dsl.LibraryExtension
import project.convention.logic.config.LibraryModule

plugins {
    id("project.android.library")
}

extensions.configure<LibraryExtension>("android") {
    namespace = "eu.europa.ec.denfc"
}

moduleConfig {
    module = LibraryModule.DeNfc
}

dependencies {
    // de-nfc holds the HCE service + reader-mode controller for the
    // offline-token NFC handshake (ADR 0011 §6). Depends on de-logic for
    // the TransferProof types and on de-storage for the SimulatedSecureElement
    // it forwards inbound items to via NfcTransferCallback.
    implementation(project(LibraryModule.DeLogic.path))
    implementation(project(LibraryModule.DeStorage.path))

    testImplementation(project(LibraryModule.TestLogic.path))
}
