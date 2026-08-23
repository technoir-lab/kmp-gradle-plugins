# CMake import plugin

![Maven Central Version](https://img.shields.io/maven-central/v/io.technoirlab.kmp/cmake-import-gradle-plugin)

The plugin provides seamless integration of CMake projects into Kotlin Multiplatform builds.
It configures CMake toolchains for cross-compilation, wires project generation, build, and installation
into the build lifecycle, and generates Kotlin C-interop definition from CMake install targets automatically.

## Prerequisites

- Gradle 9.1 or newer
- Kotlin Multiplatform Gradle plugin 2.4.10 or newer
- CMake 3.29 or newer, available on `PATH`
- Apple targets: macOS with an Xcode installation that contains the corresponding platform SDK
- MinGW targets: `ninja` available on `PATH`, or `CMAKE_GENERATOR` set to another compiler-compatible CMake generator

## Usage

Declare the plugin's version in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("io.technoirlab.cmake-import") version "1.1.0"
    }
}
```

Apply the plugin in a Kotlin Multiplatform module:

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.technoirlab.cmake-import")
}

cmakeImport {
    sourceDirectory = layout.projectDirectory.dir("cmake")
    // CMake target to configure and build
    targetName = "hello"
    // The package name for the generated bindings
    packageName = "com.example.hello"

    // Optional CMake configuration. Defaults to Release
    // buildType = "Debug"

    // Optional; omit to run the full project install
    // installComponent = "Development"

    // Passed to CMake as -DBUILD_SHARED_LIBS=OFF
    // defines.put("BUILD_SHARED_LIBS", "OFF")

    // Optional include-directory-relative headers. The default exposes every installed header.
    // headers.add("hello.h")
}
```

For every Kotlin/Native target, the plugin registers target-specific
`cmakeGenerateToolchain<Target>`, `cmakeGenerate<Target>`, `cmakeBuild<Target>`, and
`cmakeInstall<Target>` tasks, plus a `cmake` cinterop. They form a dependency chain that
generates a toolchain, generates the CMake build system, builds the selected target, and
stages its interop surface.

The generated toolchain reuses Kotlin/Native's prepared compiler, compiler arguments,
target triple, sysroot, LLVM archiver, linker, and Apple or Android platform settings. This
makes the CMake library use the same cross-compilation environment as the Kotlin/Native binary.

Kotlin/Native targets unavailable on the current host still have their CMake and cinterop
tasks registered, but those tasks are skipped just like Kotlin's own target tasks. Projects
that declare unavailable targets must opt in to ignore disabled targets:

```properties
kotlin.native.ignoreDisabledTargets=true
```

The generated C-interop definition references the installed public headers and static archive.
For a KMP library, the Release native publication is intended to carry a self-contained
archive so consumers can use the library without applying this plugin or repeating
linker options.

### CMake project

The imported CMake project must:
- Expose a static-library target with a `PUBLIC` or `INTERFACE` `HEADERS` file set.
- Install its public headers to `include/` and exactly one static archive to `lib/`.

```cmake
add_library(hello STATIC src/hello.c)

target_sources(hello
    PUBLIC
        FILE_SET HEADERS
        BASE_DIRS include
        FILES include/hello.h
)

install(TARGETS hello
    ARCHIVE DESTINATION lib
    FILE_SET HEADERS DESTINATION include
)
```

### Supported targets

All non-deprecated Kotlin/Native [targets](https://kotlinlang.org/docs/native-target-support.html) are supported.

- Android Native: `androidNativeX86`, `androidNativeX64`, `androidNativeArm32`, and
  `androidNativeArm64`.
- Linux: `linuxX64` and `linuxArm64`.
- MinGW: `mingwX64`.
- macOS: `macosArm64`.
- iOS: `iosArm64` and `iosSimulatorArm64`.
- tvOS: `tvosArm64` and `tvosSimulatorArm64`.
- watchOS: `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, and
  `watchosSimulatorArm64`.

### Unsupported functionality

* CMake-built dynamic libraries
* Transitive native archive linkage
