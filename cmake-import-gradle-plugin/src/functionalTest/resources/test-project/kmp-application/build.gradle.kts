import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.technoirlab.cmake-import")
}

kotlin {
    linuxX64()
    macosArm64()
    mingwX64()
    iosArm64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            if (konanTarget.family in setOf(Family.IOS, Family.TVOS, Family.WATCHOS)) {
                framework {
                    isStatic = true
                }
            } else {
                executable {
                    entryPoint = "kmp.application.main"
                }
            }
        }
    }
}

cmakeImport {
    sourceDirectory = layout.projectDirectory.dir("../cmake")
    targetName = "hello"
    packageName = "kmp.application.cmake"
}
