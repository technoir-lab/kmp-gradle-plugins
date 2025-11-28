plugins {
    id("io.technoirlab.conventions.gradle-plugin")
}

gradlePluginConfig {
    packageName = "io.technoirlab.vfsoverlay"

    buildFeatures {
        abiValidation = true
        serialization = true
    }

    metadata {
        description = "Clang VFS overlay generator for Kotlin/Native."
    }
}

gradlePlugin {
    plugins {
        register("vfsOverlay") {
            id = "io.technoirlab.vfs-overlay"
            implementationClass = "io.technoirlab.vfsoverlay.VfsOverlayPlugin"
        }
    }
}

dependencies {
    implementation(libs.gradle.extensions)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    functionalTestImplementation(libs.assertj.core)
    functionalTestImplementation(libs.gradle.test.kit)

    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(libs.assertj.core)
}
