plugins {
    id("io.technoirlab.conventions.gradle-plugin")
}

gradlePluginConfig {
    packageName = "io.technoirlab.device.run.gradle"

    metadata {
        name = "Device Run Gradle plugin"
        description = "Run Android tests on Google's Device Run."
    }
}

gradlePlugin {
    plugins {
        register("deviceRunProject") {
            id = "io.technoirlab.device-run"
            implementationClass = "io.technoirlab.device.run.gradle.DeviceRunPlugin"
        }
        register("deviceRunSettings") {
            id = "io.technoirlab.device-run.settings"
            implementationClass = "io.technoirlab.device.run.gradle.DeviceRunSettingsPlugin"
        }
    }
}

dependencies {
    implementation(project(":device-run-core"))
    implementation(libs.gradle.extensions)

    functionalTestImplementation(libs.android.test.result.proto)
    functionalTestImplementation(libs.assertj.core)
    functionalTestImplementation(libs.gradle.test.kit)

    compileOnly(libs.android.builder.test.api)
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.android.gradle.common.api)

    apiCompileOnly(libs.android.gradle.api)

    functionalTestCompileOnly(libs.protobuf.java)

    testFixturesImplementation(testFixtures(project(":device-run-core")))
    testFixturesImplementation(project(":device-run-core"))
    testFixturesImplementation(libs.android.builder.test.api)
    testFixturesImplementation(libs.android.gradle.api)
    testFixturesImplementation(libs.assertj.core)

    testImplementation(testFixtures(project(":device-run-core")))
    testImplementation(libs.android.builder.test.api)
    testImplementation(libs.android.gradle.api)
    testImplementation(libs.android.gradle.plugin)
    testImplementation(libs.assertj.core)
    testImplementation(libs.gradle.test.kit)
}
