/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

import com.android.build.api.dsl.LibraryExtension
import project.convention.logic.config.LibraryModule

plugins {
    id("project.android.feature")
}

extensions.configure<LibraryExtension>("android") {
    namespace = "eu.europa.ec.defeature"
}

moduleConfig {
    module = LibraryModule.DeFeature
}

dependencies {
    implementation(project(LibraryModule.DeLogic.path))
    implementation(project(LibraryModule.DeStorage.path))
}
