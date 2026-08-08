plugins {
    // Kotlin comes from AGP's built-in Kotlin support (Android module).
    alias(libs.plugins.android.library)
}

// Frame decoding via Media3 (added in a later milestone). Android dependency,
// but no UI. Consumes the video byte range produced by :core-motionphoto.
android {
    namespace = "xyz.mithunc.motionphotograbber.extract"
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
    implementation(project(":core-motionphoto"))
    // `api` because MotionPhotoPreviewPlayer exposes a StateFlow.
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.media3.inspector.frame)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource)
    // `api`, not `implementation`: MotionPhotoFrameExtractor is annotated @UnstableApi,
    // which lives in media3-common. Under `implementation` that annotation is off the
    // consumer's compile classpath and Kotlin cannot resolve it at the call site.
    api(libs.androidx.media3.common)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
