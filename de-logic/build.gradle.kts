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
    namespace = "eu.europa.ec.delogic"
}

moduleConfig {
    module = LibraryModule.DeLogic
}

dependencies {
    implementation(project(LibraryModule.BusinessLogic.path))
    implementation(project(LibraryModule.ResourcesLogic.path))

    testImplementation(project(LibraryModule.TestLogic.path))
}
