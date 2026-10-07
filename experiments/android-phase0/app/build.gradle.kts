plugins {
    id("com.android.application")
}

android {
    namespace = "dev.pocketdisco.phase0"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pocketdisco.phase0"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.1")

    testImplementation("junit:junit:4.13.2")
}
