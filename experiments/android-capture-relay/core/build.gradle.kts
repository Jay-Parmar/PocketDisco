plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketdisco.capturecore"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
    }
    sourceSets {
        getByName("main") {
            java.srcDir("../lab/src/main/java")
            res.srcDir("../lab/src/main/res")
        }
        getByName("test").java.srcDir("../lab/src/test/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":protocol"))
    testImplementation("junit:junit:4.13.2")
}
