plugins {
    id("com.android.application")
}

android {
    namespace = "io.technoirlab.device.run.fixture.application"
}

dependencies {
    implementation(project(":android-library"))

    androidTestImplementation(libs.junit)

    androidTestRuntimeOnly(libs.androidx.test.runner)
}
