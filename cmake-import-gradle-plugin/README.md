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
`cmakeInstall<Target>` and `cmakeGenerateCInteropDefinition<Target>` tasks, plus a `cmake`
cinterop. They form a dependency chain that generates a toolchain, generates the CMake build
system, builds the selected target, stages its interop surface, and generates its C-interop
definition.

`cmakeBuild<Target>` runs at most one parallel build job per available processor. Set the
`CMAKE_BUILD_PARALLEL_LEVEL` environment variable to choose another limit.

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

The plugin configures `CMAKE_INSTALL_PREFIX` as `/usr/local` on every host, so that files the project generates from
its install prefix, such as pkg-config metadata, do not depend on the host. Installation stages into the build directory
regardless. A `CMAKE_INSTALL_PREFIX` entry in `defines` takes precedence.

### Linker options

If the CMake project installs a pkg-config file for its archive, the generated definition also lists the file's `Libs`
and `Libs.private` linker options, except those that link the archive itself. Files that declare `Requires` or
`Requires.private` are ignored.

Kotlin/Native links Android and MinGW binaries through the Clang driver, so their definitions list these options as
written. It links binaries for other targets with the linker itself, so their definitions convert these compiler-driver
options for the linker:

- `-Wl,<options>` and `-Xlinker <option>` become the linker options they wrap.
- `-pthread` becomes `-lpthread`.
- `-rdynamic` becomes `-export_dynamic` on Apple targets and `--export-dynamic` on Linux targets.
- `-static-libgcc`, `-shared-libgcc`, and `-static-libstdc++` are dropped, because Kotlin/Native links the C and C++
  runtime libraries itself.

Other options reach the linker unchanged.

On Apple targets, Kotlin/Native embeds these options in static frameworks and Swift export static libraries as autolink
entries, so apps that link those binaries need no extra linker flags for them. Only `-framework <name>` and `-l<name>`
options can be autolinked, so the definition lists them before all other options and adds a strong copy of each weak
framework or library:

- An app still links such a framework weakly when all its references to it are weak, as references to APIs introduced
  after the library's deployment target are. To link it weakly regardless, add `-weak_framework <name>` to the app's
  linker flags, such as Xcode's `OTHER_LDFLAGS`.
- Binaries that Kotlin/Native links itself, such as executables and dynamic frameworks, keep linking it weakly.

Add options that can't be autolinked, such as library search paths, to the app's linker flags.

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
