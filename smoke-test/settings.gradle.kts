pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("io.technoirlab.conventions.kotlin-multiplatform-application") version "v55"
    }

    includeBuild("..")
}

plugins {
    id("io.technoirlab.conventions.kotlin-multiplatform-application") apply false
    id("io.technoirlab.cmake-import") apply false
    id("io.technoirlab.vfs-overlay") apply false
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "cmake-import-smoke-test"

include(":sdl")
include(":volk")
