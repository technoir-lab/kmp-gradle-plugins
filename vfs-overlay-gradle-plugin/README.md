# VFS overlay plugin

![Maven Central Version](https://img.shields.io/maven-central/v/io.technoirlab.kmp/vfs-overlay-gradle-plugin)

Gradle plugin for remapping file and directory locations during Kotlin/Native C interop compilation.
Supports only native targets that use Clang compiler.

## Usage

Declare the plugin's version in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("io.technoirlab.vfs-overlay") version "1.1.0"
    }
}
```

Apply the plugin in a Kotlin Multiplatform module:

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.technoirlab.vfs-overlay")
}

vfsOverlay {
    // Example: Use Vulkan headers from Vulkan SDK instead of the ones bundled with the Kotlin/Native Android NDK toolchain.
    mapping(
        source = kotlinNativeDependenciesDir.map {
            File(it, "target-toolchain-2-${HostManager.hostOs()}-android_ndk/sysroot/usr/include/vulkan")
        },
        target = providers.environmentVariable("VULKAN_SDK").map {
            File(it, "${if (HostManager.hostIsMingw) "Include" else "include"}/vulkan")
        }
    )
}
```
