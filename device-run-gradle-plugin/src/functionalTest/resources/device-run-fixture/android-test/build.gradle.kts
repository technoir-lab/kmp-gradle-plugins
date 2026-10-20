plugins {
    id("com.android.test")
}

android {
    namespace = "io.technoirlab.device.run.fixture.test"
    targetProjectPath = ":android-application"
}

dependencies {
    implementation(libs.junit)

    runtimeOnly(libs.androidx.test.runner)
}
