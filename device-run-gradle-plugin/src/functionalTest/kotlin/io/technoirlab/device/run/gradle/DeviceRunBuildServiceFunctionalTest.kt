package io.technoirlab.device.run.gradle

import io.technoirlab.gradle.test.kit.GradleRunnerExtension
import io.technoirlab.gradle.test.kit.appendBuildScript
import io.technoirlab.gradle.test.kit.settingsScript
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.junit.jupiter.api.AutoClose
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.div
import kotlin.io.path.writeText

class DeviceRunBuildServiceFunctionalTest {
    @RegisterExtension
    private val gradleRunner = GradleRunnerExtension("device-run-fixture")

    @AutoClose
    private val tokenServer = RejectingTokenServer()

    @Test
    fun `cloud tasks of all projects declare the build service and use the credentials selected for each build`() {
        // Gradle reports undeclared build service usage only with this feature preview.
        gradleRunner.root.settingsScript.appendText("\nenableFeaturePreview(\"INTERNAL_BUILD_SERVICE_USAGE\")\n")
        for (module in MODULES) {
            gradleRunner.root.project(module).appendBuildScript(
                """
                deviceRun.files.push.register("input") {
                    gcsUri = "gs://example-bucket/input.txt"
                    destinationPath = "/sdcard/input.txt"
                }
                """.trimIndent(),
            )
        }
        val credentialsFiles = listOf("first", "second").map(::writeUserCredentials)

        val builds = credentialsFiles.map { credentialsFile ->
            val result = gradleRunner.build("smallPhoneSetup", "deviceRunPreparePushedFiles", expectFailure = true) {
                arguments.addAll(listOf("--continue", "--parallel"))
                environmentVariables.putAll(System.getenv())
                environmentVariables["GOOGLE_APPLICATION_CREDENTIALS"] = credentialsFile
            }
            result to tokenServer.takeRefreshTokens()
        }

        val (firstResult, firstRefreshTokens) = builds.first()
        val (secondResult, secondRefreshTokens) = builds.last()
        assertThat(secondResult.output).contains("Reusing configuration cache.")
        for (result in listOf(firstResult, secondResult)) {
            assertThat(CLOUD_TASKS).allSatisfy { task -> assertThat(result.task(task)?.outcome).isEqualTo(FAILED) }
            assertThat(result.output).contains("invalid_grant")
            assertThat(result.output).doesNotContain("without the corresponding declaration")
        }
        assertThat(firstRefreshTokens).isNotEmpty().containsOnly("first")
        assertThat(secondRefreshTokens).isNotEmpty().containsOnly("second")
    }

    private fun writeUserCredentials(refreshToken: String): Path = (gradleRunner.root.dir / "$refreshToken.json").apply {
        writeText(
            """
            {
                "type": "authorized_user",
                "client_id": "test-client",
                "client_secret": "test-secret",
                "refresh_token": "$refreshToken",
                "quota_project_id": "test-project",
                "token_uri": "${tokenServer.tokenUri}"
            }
            """.trimIndent(),
        )
    }

    private companion object {
        private val MODULES = listOf("android-application", "android-library", "android-test")
        private val CLOUD_TASKS = MODULES.flatMap { listOf(":$it:smallPhoneSetup", ":$it:deviceRunPreparePushedFiles") }
    }
}
