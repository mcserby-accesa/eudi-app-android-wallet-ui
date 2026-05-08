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

import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import project.convention.logic.AppBuildType
import project.convention.logic.config.LibraryModule
import project.convention.logic.getProperty

plugins {
    id("project.android.application")
    id("project.android.application.compose")
    alias(libs.plugins.firebase.appdistribution)
}

android {

    signingConfigs {
        create("release") {

            storeFile = file("${rootProject.projectDir}/sign")

            keyAlias = getProperty("androidKeyAlias") ?: System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = getProperty("androidKeyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")
            storePassword =
                getProperty("androidKeyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")

            enableV2Signing = true
        }

        // Accesa: workshop (Firebase App Distribution) signing config. Reads
        // from Gradle properties so CI can sign with a stable keystore secret
        // without committing keys. Only registered when -PwalletKeystoreFile
        // is passed; locally absent so upstream's `release` config still
        // resolves for non-CI use. GitHub Actions decodes WALLET_KEYSTORE_B64
        // to a file and passes:
        //   -PwalletKeystoreFile=$RUNNER_TEMP/wallet.jks
        //   -PwalletKeystorePassword=$WALLET_KEYSTORE_PASSWORD
        //   -PwalletKeyAlias=$WALLET_KEY_ALIAS
        //   -PwalletKeyPassword=$WALLET_KEY_PASSWORD
        val walletKeystoreFile = project.findProperty("walletKeystoreFile") as String?
        if (walletKeystoreFile != null) {
            create("workshop") {
                storeFile = file(walletKeystoreFile)
                storePassword = (project.findProperty("walletKeystorePassword") as String?) ?: ""
                keyAlias = (project.findProperty("walletKeyAlias") as String?) ?: ""
                keyPassword = (project.findProperty("walletKeyPassword") as String?) ?: ""
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "eu.europa.ec.euidi"
        versionCode = 1

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = AppBuildType.DEBUG.applicationIdSuffix
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            applicationIdSuffix = AppBuildType.RELEASE.applicationIdSuffix
            // Prefer the CI-provisioned `workshop` config when present; fall
            // back to upstream's env-var-based `release` config otherwise.
            signingConfig = signingConfigs.findByName("workshop")
                ?: signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Accesa: Firebase App Distribution wiring for the workshop demo build.
    // Scoped to the `demo` flavor's release variant — `dev` is the local-dev
    // flavor and isn't distributed. App ID is committed (it's a public
    // identifier, visible to anyone with the APK) so CI doesn't need to
    // inject it as a secret. Override via -PfirebaseWalletAppId when targeting
    // a different Firebase project. See plan/workshop/wallet-distribution-setup.md
    // in the companion repo for the full pipeline.
    val firebaseWalletAppId: String =
        (project.findProperty("firebaseWalletAppId") as String?)
            ?: "1:147830702926:android:553e4246ba5a05c208141f"
    val firebaseServiceAccount: String? =
        project.findProperty("firebaseServiceAccount") as String?
    val firebaseTesterGroups: String =
        (project.findProperty("firebaseTesterGroups") as String?) ?: "workshop"
    val firebaseReleaseNotes: String =
        (project.findProperty("firebaseReleaseNotes") as String?)
            ?: "Workshop demo build"

    productFlavors {
        getByName("demo") {
            firebaseAppDistribution {
                appId = firebaseWalletAppId
                serviceCredentialsFile = firebaseServiceAccount.orEmpty()
                groups = firebaseTesterGroups
                releaseNotes = firebaseReleaseNotes
            }
        }
    }

    namespace = "eu.europa.ec.euidi"
}

dependencies {
    implementation(project(LibraryModule.AssemblyLogic.path))
    "baselineProfile"(project(LibraryModule.BaselineProfileLogic.path))
}
