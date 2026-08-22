# Kotlin Multiplatform CMake integration

The `io.technoirlab.cmake-import` plugin connects Kotlin/Native targets in a Kotlin Multiplatform
project to a CMake static-library target. It configures and builds each supported target,
installs its public headers and archive into a Gradle-managed staging directory, and
generates the C-interop definition from that installed surface.

## Prerequisites

- CMake 3.29 or newer, available on `PATH`.
- Apple targets: macOS with an Xcode installation that contains the corresponding
  platform SDK.
- MinGW targets: `ninja` available on `PATH`, or `CMAKE_GENERATOR` set to another
  compiler-compatible CMake generator.

The CMake project must:
- Expose a static-library target with a `PUBLIC` or `INTERFACE` `HEADERS` file set.
- Install exactly one static archive to `lib/` and its public headers to `include/`.

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

The install rules may use any component name. Set `component` to select it; when
the property is absent, the plugin stages the full project install. Selecting a component
is useful when a full third-party install would contain additional static archives.
Private headers and implementation files must not be part of the selected install.

## Usage

```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.technoirlab.cmake-import")
}

cmakeImport {
    sourceDirectory = layout.projectDirectory.dir("cmake")
    // CMake target to configure and build
    targetName = "hello"
    // CMake configuration. Defaults to Release
    buildType = "Debug"
    // Optional; omit to run the full project install
    installComponent = "Development"
    // The package name for the generated bindings
    packageName = "com.example.hello"
    // Passed to CMake as -DBUILD_SHARED_LIBS=OFF
    defines.put("BUILD_SHARED_LIBS", "OFF")
    // Optional include-directory-relative headers. Empty (the default) exposes every installed header.
    headers.add("hello.h")
}
```

For every Kotlin/Native target, the plugin registers target-specific
`cmakeGenerateToolchain<Target>`, `cmakeGenerate<Target>`, `cmakeBuild<Target>`, and
`cmakeInstall<Target>` tasks, plus a `cmake` cinterop. They form a dependency chain that
generates a toolchain, generates the CMake build system, builds the selected target, and
stages its interop surface.

The generated toolchain reuses Kotlin/Native's prepared Clang compiler, compiler arguments,
target triple, sysroot, LLVM archiver, linker, and Apple or Android platform settings. This
makes the CMake library use the same cross-compilation environment as the Kotlin/Native binary.
The Apple toolchain supports Kotlin/Native's ARM64 iOS and tvOS device and simulator targets,
including `iosArm64`, `iosSimulatorArm64`, `tvosArm64`, and `tvosSimulatorArm64`.
Linux and MinGW targets use Kotlin/Native's host-compatible linker for executable CMake
capability checks. Android targets use Kotlin/Native's API-specific Android compiler driver
and linker settings, so their compiler bootstrap and capability checks also link executables.

Kotlin/Native targets unavailable on the current host still have their CMake and cinterop
tasks registered, but those tasks are skipped just like Kotlin's own target tasks. Projects
that declare unavailable targets must opt in to Kotlin's disabled-target behavior:

```properties
kotlin.native.ignoreDisabledTargets=true
```

The generated definition references the installed public headers and static archive.
For a KMP library, the Release native publication is intended to carry a self-contained
archive so consumers can use the library without applying this plugin or repeating
linker options. Transitive native archive linkage and dynamic-library support are currently
unsupported.
