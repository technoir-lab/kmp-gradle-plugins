import io.technoirlab.conventions.gradle.plugin.apiOf

plugins {
    id("io.technoirlab.conventions.gradle-plugin")
}

gradlePluginConfig {
    packageName = "io.technoirlab.openapi.kotlin.generator"

    metadata {
        description = "OpenAPI Kotlin generator."
    }
}

dependencies {
    implementation(libs.core.utils)
    implementation(libs.gradle.extensions)
    implementation(libs.openapi.generator.core)
    implementation(libs.openapi.generator.gradle.plugin)
    implementation(libs.swagger.models)

    functionalTestImplementation(libs.assertj.core)
    functionalTestImplementation(libs.gradle.test.kit)

    compileOnly(apiOf(libs.common.conventions))

    testImplementation(gradleKotlinDsl())
    testImplementation(libs.assertj.core)
    testImplementation(libs.gradle.test.kit)
    testImplementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("openApiKotlinGenerator") {
            id = "io.technoirlab.openapi-kotlin-generator"
            implementationClass = "io.technoirlab.openapi.kotlin.generator.OpenApiKotlinGeneratorPlugin"
        }
    }
}

tasks.named<Test>("functionalTest") {
    inputs.property("updateCheckedInClient", providers.environmentVariable("UPDATE_CHECKED_IN_CLIENT")).optional(true)
}
