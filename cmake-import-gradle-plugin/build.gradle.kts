plugins {
    id("io.technoirlab.conventions.gradle-plugin")
}

gradlePluginConfig {
    packageName = "io.technoirlab.cmake.import"

    buildFeatures {
        abiValidation = true
    }

    metadata {
        description = "CMake import for Kotlin/Native."
    }
}

dependencies {
    implementation(libs.core.utils)
    implementation(libs.gradle.extensions)

    functionalTestImplementation(libs.assertj.core)
    functionalTestImplementation(libs.core.utils)
    functionalTestImplementation(libs.gradle.test.kit)

    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("cmakeImport") {
            id = "io.technoirlab.cmake-import"
            implementationClass = "io.technoirlab.cmake.import.CMakeImportPlugin"
        }
    }
}
