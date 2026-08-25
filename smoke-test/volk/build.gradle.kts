import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    id("io.technoirlab.conventions.kotlin-multiplatform-application")
    id("io.technoirlab.cmake-import")
}

kotlinMultiplatformApplication {
    packageName = "io.technoirlab.volksmoke"
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
    sourceDirectory = layout.projectDirectory.dir("third_party/volk")
    targetName = "volk"
    packageName = "volk"
    defines.put("VOLK_INSTALL", "ON")
}

val vulkanSdkDir = providers.environmentVariable("VULKAN_SDK")
tasks.withType<KotlinNativeTest>().configureEach {
    if (HostManager.hostIsMac) {
        // macOS purges dyld environment variables when launching protected processes,
        // so we have to define DYLD_LIBRARY_PATH ourselves
        vulkanSdkDir.orNull?.let { environment("DYLD_LIBRARY_PATH", "$it/lib") }
    }
}
