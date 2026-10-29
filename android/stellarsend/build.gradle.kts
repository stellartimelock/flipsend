plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.stellartimelock.send"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // Self-update from flipsend.app (CloudPull). F-Droid builds pass -PflipsendCloudUpdate=false.
        buildConfigField("boolean", "CLOUD_UPDATE", (project.findProperty("flipsendCloudUpdate")?.toString() ?: "true"))
        consumerProguardFiles("consumer-rules.pro")
    }

    flavorDimensions += "store"
    productFlavors {
        create("sideload") {
            dimension = "store"
            isDefault = true
        }
    }

    sourceSets {
        getByName("sideload") {
            java.srcDir("src/full/kotlin")
            res.srcDir("src/full/res")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

val copyRelayTiers = tasks.register<Copy>("copyRelayTiers") {
    from(rootProject.file("../relay/tiers.json"))
    into(layout.buildDirectory.dir("generated/relay-assets"))
}

android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/relay-assets"))

tasks.configureEach {
    if (name == "preBuild" || (name.startsWith("merge") && name.endsWith("Assets"))) {
        dependsOn(copyRelayTiers)
    }
}

dependencies {
    api(project(":core")) {
        exclude(group = "org.json", module = "json")
    }
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.zxing:core:3.5.3")
}
