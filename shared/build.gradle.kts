import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    // JVM target: used by host-machine unit tests (Linux CI, macOS CI)
    jvm {
        compilations.all {
            kotlinOptions.freeCompilerArgs += "-Xexpect-actual-classes"
        }
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // androidMain and jvmMain share the same JVM-based actual implementations
        // (BouncyCastle, java.security.*, java.net.*). The jvmMain source set
        // points to the same directory so we don't duplicate code.
        val jvmAndAndroid by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.bouncycastle.pkix)
                implementation(libs.bouncycastle.prov)
            }
        }
        androidMain.get().apply {
            dependsOn(jvmAndAndroid)
            dependencies {
                implementation(libs.androidx.core.ktx)
            }
        }
        jvmMain.get().dependsOn(jvmAndAndroid)
        // iosMain has no extra dependencies; uses platform frameworks via cinterop
    }
}

// The jvmAndAndroid source set uses the same files as androidMain.
// We achieve this by pointing jvmMain to the androidMain source directory.
afterEvaluate {
    kotlin.sourceSets.getByName("jvmMain").kotlin
        .srcDirs(project.file("src/androidMain/kotlin"))
}

android {
    namespace = "org.stellartimelock.send.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
