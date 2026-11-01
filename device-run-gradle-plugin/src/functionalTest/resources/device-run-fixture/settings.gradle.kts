import com.android.build.api.dsl.CommonExtension
import io.technoirlab.device.run.gradle.api.DeviceRunExtension

plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("com.android.settings")
    id("com.android.test") apply false
    id("io.technoirlab.device-run.settings")
}

rootProject.name = "device-run-fixture"

include(":android-application")
include(":android-library")
include(":android-test")
include(":plain")

android {
    compileSdk = 37
    minSdk = 30
}

gradle.lifecycle.beforeProject {
    pluginManager.withPlugin("com.android.base") {
        extensions.configure<CommonExtension> {
            defaultConfig.apply {
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    }

    pluginManager.withPlugin("io.technoirlab.device-run") {
        extensions.configure<DeviceRunExtension> {
            devices.register("smallPhone") {
                deviceId = "smallphone-arm-35"
            }
            storage {
                bucket = providers.gradleProperty("deviceRunBucket").orElse("example-bucket")
            }
        }
    }
}
