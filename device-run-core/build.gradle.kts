plugins {
    id("io.technoirlab.conventions.jvm-library")
}

jvmLibrary {
    packageName = "io.technoirlab.device.run.core"

    buildFeatures {
        abiValidation = true
        serialization = true
    }

    metadata {
        name = "Device Run core"
        description = "Library for running tests on Google's Device Run."
    }
}

dependencies {
    constraints {
        implementation(libs.gson)
    }

    implementation(platform(libs.google.api.client.bom))
    implementation(platform(libs.google.auth.bom))
    implementation(platform(libs.google.http.client.bom))
    implementation(libs.android.test.result.proto)
    implementation(libs.google.api.client)
    implementation(libs.google.api.services.devicerun)
    implementation(libs.google.api.services.storage)
    implementation(libs.google.auth.credentials)
    implementation(libs.google.auth.oauth2.http)
    implementation(libs.google.http.client)
    implementation(libs.google.http.client.gson)
    implementation(libs.guava)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.protobuf.java)
    implementation(libs.slf4j.api)

    testFixturesImplementation(platform(libs.google.auth.bom))
    testFixturesImplementation(platform(libs.google.http.client.bom))
    testFixturesImplementation(libs.google.api.services.devicerun)
    testFixturesImplementation(libs.google.api.services.storage)
    testFixturesImplementation(libs.google.auth.oauth2.http)
    testFixturesImplementation(libs.google.http.client)
    testFixturesImplementation(libs.google.http.client.gson)
    testFixturesImplementation(libs.slf4j.api)

    testImplementation(libs.assertj.core)
}
