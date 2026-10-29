pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "stellar-send-kmp"

// KMP shared logic and Compose Multiplatform UI modules.
// The existing Android-only project lives under android/ and is a separate Gradle project.
include(":shared", ":composeApp")
