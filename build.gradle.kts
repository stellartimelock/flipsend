// Root project for the Kotlin Multiplatform + Compose Multiplatform build.
// The pure-Android project (android/) is a separate Gradle build kept intact.
plugins {
    // Version catalog applied here so subprojects can reference aliases.
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}
