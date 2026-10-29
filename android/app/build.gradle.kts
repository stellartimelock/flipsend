plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single build. Google Play builds were dropped for F-Droid (Oct 28 2026).
val appVersionCode = 73

base.archivesName.set("FlipSend")

android {
    namespace = "org.stellartimelock.stellarsend"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.stellartimelock.stellarsend"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "1.5.63"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "store"
    productFlavors {
        create("sideload") {
            dimension = "store"
            isDefault = true
            // Debug builds use the normal debug signer (the maintainer's own debug key).
            // Release builds stay unsigned in Gradle so F-Droid (or a local env-var config) signs them.
            buildConfigField("boolean", "REMOTE_DESKTOP", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Uncompressed JNI is zip-aligned to 16 KB by AGP 8.5.1+. Play requires that for target 35+.
            useLegacyPackaging = false
        }
        resources {
            excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":stellarsend"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

