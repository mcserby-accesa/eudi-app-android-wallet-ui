/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 */

import com.android.build.api.dsl.LibraryExtension
import project.convention.logic.config.LibraryModule

plugins {
    id("project.android.library")
    id("project.ktor")
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
    // The withdrawToWallet direct-POST `/deliver` client (slice 4) uses
    // the singleton Ktor HttpClient declared by network-logic. Pulling
    // network-logic here is symmetric with the business-logic dep
    // above — both are infrastructure layers de-logic sits on top of.
    implementation(project(LibraryModule.NetworkLogic.path))

    testImplementation(project(LibraryModule.TestLogic.path))
}
