plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketdisco.capturelab.source"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.pocketdisco.capturelab.source"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-lab"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
