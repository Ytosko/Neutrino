plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// What the phone and the watch say to each other over the Wearable Data Layer: the day's snapshot
// and the watch's actions. Plain Kotlin with no Google libraries, so the phone's F-Droid build can
// keep this module (it just never uses it) and it's unit-tested on the JVM.
android {
    namespace = "dev.ytosko.neutrino.wear.protocol"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
