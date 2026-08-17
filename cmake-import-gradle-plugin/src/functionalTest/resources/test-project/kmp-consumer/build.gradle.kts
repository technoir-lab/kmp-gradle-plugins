import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

repositories {
    maven {
        url = uri(layout.projectDirectory.dir("../build/repo"))
    }
    mavenCentral()
}

kotlin {
    linuxX64()
    macosArm64()
    mingwX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            executable {
                entryPoint = "kmp.consumer.main"
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation("com.example:kmp-library:1.0")
        }
    }
}
