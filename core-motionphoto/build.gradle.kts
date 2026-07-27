import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module: format detection and offset parsing with no Android
// dependency, so it can be unit-tested on the JVM against the files in samples/.
java {
    // Compile with the JDK 21 toolchain — the same JVM the Gradle daemon runs on
    // (see gradle/gradle-daemon-jvm.properties; AGP does not support JDK 26).
    // Matching it keeps this to a single JDK: asking for a different toolchain
    // version here would make a fresh clone provision a second one to build a
    // module that emits Java 17 bytecode either way.
    //
    // Java 17 output is the floor JUnit 6 requires and is consistent with the
    // Android modules. minSdk 26 is unaffected (AGP desugars Java 17).
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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

    // The parser tests read real motion photos from samples/, which Gradle cannot infer
    // as an input because nothing on the compile classpath references it. Without this,
    // adding or removing a sample leaves the task UP-TO-DATE and silently reuses the
    // previous run's results — which reads as "tests skipped" long after the samples are
    // back in place. Declared optional: samples/ is git-ignored and may be absent.
    inputs.dir(rootProject.layout.projectDirectory.dir("samples"))
        .withPropertyName("motionPhotoSamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
}
