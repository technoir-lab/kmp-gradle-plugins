import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.technoirlab.cmake-import")
}

kotlin {
    linuxX64()
    macosArm64()
    mingwX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            executable {
                entryPoint = "kmp.application.main"
            }
        }
    }
}

cmakeImport {
    sourceDirectory = layout.projectDirectory.dir("../cmake")
    targetName = "hello"
    packageName = "kmp.application.cmake"
}
