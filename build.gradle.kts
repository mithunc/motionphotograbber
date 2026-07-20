// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 9 ships built-in Kotlin for Android modules, so there is no
    // org.jetbrains.kotlin.android here — applying it would clash with AGP's own
    // `kotlin` extension. Only the pure-JVM module uses the standalone kotlin.jvm.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}
