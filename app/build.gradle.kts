plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing credentials are read from project properties
// (typically supplied via ~/.gradle/gradle.properties — never via the repo).
// See docs/release-signing.md for the full setup and backup procedure.
val releaseStoreFile = providers.gradleProperty("AI_INBOX_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("AI_INBOX_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("AI_INBOX_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("AI_INBOX_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "uk.nordtek.aiinbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.nordtek.aiinbox"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "uk.nordtek.aiinbox.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Downgrade Kotlin metadata version so Hilt's KAPT-based component aggregation
        // (hiltJavaCompileDebug) can read class metadata produced by the Kotlin 2.x compiler.
        freeCompilerArgs += "-Xmetadata-version=1.9.0"
        // LiteRT-LM 0.10.x ships with Kotlin metadata version 2.3.0 but our compiler
        // is 2.1.0. The library API surface we use (Engine / EngineConfig / etc.) does
        // not depend on Kotlin 2.3-specific features, so skipping the version check is
        // safe for now.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }

    sourceSets {
        getByName("main").kotlin.srcDirs("src/main/kotlin")
        getByName("test").kotlin.srcDirs("src/test/kotlin")
        getByName("androidTest").kotlin.srcDirs("src/androidTest/kotlin")
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.litertlm.android)
    implementation(libs.mlkit.text.recognition.latin)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.mockwebserver)

    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.yaml)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
