plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Developer tool, not shipped: a virtual CONTOUR-style glucose meter (Bluetooth GATT server) to test
// Neutrino's meter sync on a second phone or emulator. Uses only made-up readings.
android {
    namespace = "dev.ytosko.neutrino.metersim"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ytosko.neutrino.metersim"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":glucose-ble"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
