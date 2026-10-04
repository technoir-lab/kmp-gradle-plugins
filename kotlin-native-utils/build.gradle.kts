plugins {
    id("io.technoirlab.conventions.jvm-library")
}

jvmLibrary {
    packageName = "io.technoirlab.kotlin.native.utils"

    buildFeatures {
        abiValidation = true
    }

    metadata {
        description = "Shared JVM utilities for Kotlin/Native tooling."
    }
}

dependencies {
    implementation(gradleApi())

    testImplementation(libs.assertj.core)
}

tasks.test {
    // ProjectBuilder needs reflective access to java.lang when injecting synthetic classes.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}
