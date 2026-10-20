plugins {
    id("com.android.library")
}

android {
    namespace = "io.technoirlab.device.run.fixture.library"
}

dependencies {
    androidTestImplementation(libs.junit)

    androidTestRuntimeOnly(libs.androidx.test.runner)
}
