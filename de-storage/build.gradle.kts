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
    namespace = "eu.europa.ec.destorage"
}

moduleConfig {
    module = LibraryModule.DeStorage
}

dependencies {
    // Encrypted KV (Tink-AEAD-backed DataStore) lives in business-logic's
    // PrefsController — reused so the simulated SE persists under the same
    // master key as every other Accesa secret in the wallet.
    implementation(project(LibraryModule.BusinessLogic.path))
    implementation(project(LibraryModule.ResourcesLogic.path))
    implementation(project(LibraryModule.DeLogic.path))

    testImplementation(project(LibraryModule.TestLogic.path))
}
