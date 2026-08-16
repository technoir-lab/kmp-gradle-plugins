pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("io.technoirlab.cmake-import") apply false
}

rootProject.name = "test-project"

include(":kmp-application")
include(":kmp-consumer")
include(":kmp-library")
