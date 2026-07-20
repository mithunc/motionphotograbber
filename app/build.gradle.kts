plugins {
    // Kotlin is provided by AGP's built-in Kotlin support; only the Compose
    // compiler plugin is applied on top.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "xyz.mithunc.motionphotograbber"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "xyz.mithunc.motionphotograbber"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // Kotlin jvmTarget defaults to compileOptions.targetCompatibility (17) under
    // AGP built-in Kotlin, so no separate kotlin { } block is needed.
    testOptions {
        // Run local unit tests on the JUnit 6 (Jupiter) platform. AGP supports
        // this natively for unit tests; no third-party plugin is required.
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    // Core modules (parsing / extraction / EXIF logic lives here, not in :app).
    implementation(project(":core-motionphoto"))
    implementation(project(":core-extract"))
    implementation(project(":core-exif"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Compose — versions resolved via the BOM.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
