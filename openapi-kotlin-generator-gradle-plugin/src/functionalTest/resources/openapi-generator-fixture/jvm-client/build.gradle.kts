import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.technoirlab.openapi-kotlin-generator")
    `jvm-test-suite`
}

openApiKotlinGenerator {
    specUrl = uri("../petstore.yaml")
    packageName = "com.example.petstore"
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())

            dependencies {
                implementation(libs.assertj.core)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.mock)
                implementation(libs.ktor.http)
                implementation(libs.ktor.serialization.kotlinx.json)
            }

            targets.configureEach {
                testTask.configure {
                    testLogging.exceptionFormat = TestExceptionFormat.FULL
                }
            }
        }
    }
}
