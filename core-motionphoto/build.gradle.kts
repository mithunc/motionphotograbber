import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module: format detection and offset parsing with no Android
// dependency, so it can be unit-tested on the JVM against the files in samples/.
java {
    // Compile with the JDK 26 toolchain (the project's JDK) and emit Java 17
    // bytecode — the floor required by JUnit 6, and consistent with the Android
    // modules. minSdk 26 is unaffected (AGP desugars Java 17).
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
