plugins {
    alias(libs.plugins.android.library)
}

// The Bluetooth Glucose Profile in plain Kotlin: message encoding/decoding, the sync session and
// clock handling. No Android APIs here, so it's unit-tested on the JVM and shared by the app and
// the meter simulator.
android {
    namespace = "dev.ytosko.neutrino.glucose"
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
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
