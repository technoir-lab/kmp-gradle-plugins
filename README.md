KMP Gradle plugins
==================

[![Build](https://github.com/technoir-lab/kmp-gradle-plugins/actions/workflows/ci.yaml/badge.svg?branch=main)](https://github.com/technoir-lab/kmp-gradle-plugins/actions/workflows/ci.yaml)
![Maven Central Version](https://img.shields.io/maven-central/v/io.technoirlab.kmp/vfs-overlay-gradle-plugin)

Gradle plugins for Kotlin Multiplatform.

## VFS overlay plugin

Gradle plugin for remapping file and directory locations during Kotlin/Native C interop compilation.
Supports only native targets that use Clang compiler.

```kotlin
plugins {
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
