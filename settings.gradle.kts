pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
    plugins {
        val conventionPluginsVersion = "v45"
        id("io.technoirlab.conventions.gradle-plugin") version conventionPluginsVersion
        id("io.technoirlab.conventions.root") version conventionPluginsVersion
        id("io.technoirlab.conventions.settings") version conventionPluginsVersion
    }
}

plugins {
    id("io.technoirlab.conventions.gradle-plugin") apply false
    id("io.technoirlab.conventions.root") apply false
    id("io.technoirlab.conventions.settings")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

globalSettings {
    projectId = "kmp-gradle-plugins"

    metadata {
        developer(name = "technoir", email = "technoir.dev@gmail.com")
        license(name = "The Apache Software License, Version 2.0", url = "http://www.apache.org/licenses/LICENSE-2.0.txt")
    }
}

include(":vfs-overlay-gradle-plugin")
