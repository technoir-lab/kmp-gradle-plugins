import io.technoirlab.cmake.import.tasks.CMakeGenerateTask
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    id("io.technoirlab.conventions.kotlin-multiplatform-application")
    id("io.technoirlab.cmake-import")
}

kotlinMultiplatformApplication {
    packageName = "io.technoirlab.sdl3smoke"
}

kotlin {
    androidNativeArm64()
    iosSimulatorArm64()
    linuxX64()
    macosArm64()
    mingwX64()
    tvosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    sourceSets {
        nativeTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

cmakeImport {
    sourceDirectory = layout.projectDirectory.dir("third_party/SDL")
    targetName = "SDL3-static"
    packageName = "sdl3"
    headers.add("SDL3/SDL.h")
    defines.putAll(
        mapOf(
            "CMAKE_DISABLE_PRECOMPILE_HEADERS" to "ON",
            "SDL_ANDROID_JAR" to "OFF",
            "SDL_EXAMPLES" to "OFF",
            "SDL_INSTALL" to "ON",
            "SDL_INSTALL_CPACK" to "OFF",
            "SDL_INSTALL_DOCS" to "OFF",
            "SDL_SHARED" to "OFF",
            "SDL_STATIC" to "ON",
            "SDL_TEST_LIBRARY" to "OFF",
            "SDL_TESTS" to "OFF",
            "SDL_UNIX_CONSOLE_BUILD" to "ON",
        ),
    )
}

tasks.withType<CMakeGenerateTask>().configureEach {
    if (konanTarget.get() != KonanTarget.MINGW_X64) return@configureEach

    cmakeDefines.putAll(
        mapOf(
            "CMAKE_CXX_FLAGS_RELEASE" to "-O3 -DNDEBUG -msse3",
            "SDL_GPU" to "OFF",
            "SDL_RENDER_D3D12" to "OFF",
        ),
    )
}
