# Kotlin Multiplatform CMake integration

The `io.technoirlab.cmake-import` plugin connects one host-native Kotlin Multiplatform
target to a CMake static-library target. It configures and builds the selected target,
installs its public headers and archive into a Gradle-managed staging directory, and
generates the C-interop definition from that installed surface.

## Prerequisites

Install CMake 3.23 or newer and make `cmake` available on `PATH`.

The CMake project must expose a static-library target with a `PUBLIC` or `INTERFACE`
`HEADERS` file set. It must install exactly one static archive to `lib/` and its public
headers to `include/`:

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
    // Passed to CMake as -DBUILD_SHARED_LIBS=OFF
    defines.put("BUILD_SHARED_LIBS", "OFF")
    // Optional; omit to run the full project install
    installComponent = "Development"
    // The package name for the generated bindings
    packageName = "com.example.hello"
}
```

The plugin registers target-specific `cmakeGenerate<HostTarget>`,
`cmakeBuild<HostTarget>`, and `cmakeInstall<HostTarget>` tasks. They form a dependency
chain that generates the CMake build system, builds the selected target, and stages its
interop surface. CMake runs only for the Kotlin/Native target matching the current host;
cross-compilation targets do not run CMake.

The generated definition references the installed public headers and static archive.
For a KMP library, the Release native publication is intended to carry a self-contained
archive so consumers can use the library without applying this plugin or repeating
linker options. Transitive native archive linkage and dynamic-library support are outside
the current scope.
