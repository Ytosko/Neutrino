import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Neutrino for Wear OS: a companion to the GitHub (full) phone app. It shows what the phone sends
// over the Wearable Data Layer and never goes online itself. Not part of the F-Droid build (it
// needs Google Play services, which every Wear OS watch has); see docs/WEAR.md.

/** 1.2.3 -> 10203, the same scheme as the phone app. */
fun versionCodeFor(name: String): Int {
    val (major, minor, patch) = (name.split(".").mapNotNull { it.toIntOrNull() } + listOf(0, 0, 0)).take(3)
    return major * 10_000 + minor * 100 + patch
}

// Signed with the same key as the phone app (keystore.properties, never committed): Wear OS only
// pairs the two over the Data Layer when package name and signing certificate match.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.ytosko.neutrino.wear"
    compileSdk = 37

    defaultConfig {
        // Same as the phone app, so Wear OS pairs them.
        applicationId = "dev.ytosko.neutrino"
        minSdk = 30
        targetSdk = 36
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"
        versionCode = versionCodeFor(versionName!!)
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Don't embed Google's encrypted dependency metadata blob (privacy, reproducible builds).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":wear-protocol"))
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)

    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.complications.data.source)
    implementation(libs.androidx.concurrent.futures.ktx)

    testImplementation(libs.junit)
}
