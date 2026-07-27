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
        // ExifInterface logs through android.util.Log on paths we exercise, and the
        // stub android.jar throws on every unmocked call. Returning defaults turns
        // those into no-ops. It is not sufficient on its own: android.util.Pair loses
        // its constructor body too, so src/test supplies a real one — see
        // src/test/kotlin/android/util/Pair.kt for why that matters.
        unitTests.isReturnDefaultValues = true

        // Run local unit tests on the JUnit 6 (Jupiter) platform. AGP supports
        // this natively for unit tests; no third-party plugin is required.
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.exifinterface)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Used as an oracle in the sample tier: after copying metadata onto an extracted
    // frame, the parser must no longer recognize that frame as a motion photo.
    testImplementation(project(":core-motionphoto"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.withType<Test>().configureEach {
    // The sample tier reads real motion photos from samples/, which Gradle cannot infer
    // as an input because nothing on the compile classpath references it. Without this,
    // adding or removing a sample leaves the task UP-TO-DATE and silently reuses the
    // previous run's results. Declared optional: samples/ is git-ignored and may be absent.
    inputs.dir(rootProject.layout.projectDirectory.dir("samples"))
        .withPropertyName("motionPhotoSamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
}
