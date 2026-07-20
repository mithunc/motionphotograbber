plugins {
    // Kotlin comes from AGP's built-in Kotlin support (Android module).
    alias(libs.plugins.android.library)
}

// Reads EXIF from the source and writes it onto the extracted still. This is an
// Android library (not a pure JVM module) because androidx.exifinterface — added
// in a later milestone — ships as an AAR and cannot be consumed by kotlin("jvm").
android {
    namespace = "xyz.mithunc.motionphotograbber.exif"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Run local unit tests on the JUnit 6 (Jupiter) platform. AGP supports
        // this natively for unit tests; no third-party plugin is required.
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
