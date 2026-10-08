pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.1.0"
        id("org.jetbrains.kotlin.jvm") version "2.1.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Sonnik"
include(":core")
// The Android app needs the Android SDK; set CORE_ONLY=1 to build just the pure-Kotlin logic.
if (System.getenv("CORE_ONLY") == null) include(":app")
