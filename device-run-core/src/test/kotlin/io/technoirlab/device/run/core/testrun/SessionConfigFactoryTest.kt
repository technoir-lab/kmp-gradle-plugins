package io.technoirlab.device.run.core.testrun

import com.google.api.services.devicerun.v1alpha.model.Session
import io.technoirlab.device.run.core.client.canonicalJson
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
import io.technoirlab.device.run.core.device.DeviceOrientation
import io.technoirlab.device.run.core.device.DeviceSpec
import io.technoirlab.device.run.core.files.PreparedFile
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.testrun.apk.TestPackages
import io.technoirlab.device.run.core.testrun.job.ArtifactCapture
import io.technoirlab.device.run.core.testrun.job.RunnerArguments
import io.technoirlab.device.run.core.testrun.job.TestReductionMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

class SessionConfigFactoryTest {
    private val validator = RunOptionsValidator()
    private val factory = SessionConfigFactory()
    private val libraryInputs = SessionInputs(
        device = DeviceSpec(name = "smallPhone", deviceId = "smallphone-arm-35", locale = null, orientation = null),
        packages = TestPackages(testApk = input("android-library-debug-androidTest.apk"), targetApp = emptyList()),
        pushedFiles = emptyList(),
        testRunnerClass = "androidx.test.runner.AndroidJUnitRunner",
        runnerArguments = RunnerArguments.Split(testTargets = emptyList(), additionalTestOptions = emptyMap()),
        testRunName = ":android-library debug",
        labels = mapOf("project" to ":android-library", "variant" to "debug", "device" to "smallPhone"),
        outputDirectory = GcsUri("example-bucket", "automation/sessions"),
    )

    @Test
    fun `sends only the test package and the explicit retry and timeout policy by default`() {
        val spec = validator.validate(RunOptions(bucket = "example-bucket"))

        val session = factory.create(spec, libraryInputs)

        assertThat(canonicalJson(session)).isEqualTo(
            canonicalJson(
                """
                {
                  "sessionConfig": {
                    "displayName": "android-library debug smallPhone",
                    "jobConfigs": [
                      {
                        "displayName": "smallPhone",
                        "labels": {"project": ":android-library", "variant": "debug", "device": "smallPhone"},
                        "action": {
                          "androidInstrumentationTest": {
                            "testInstallable": {"files": [{"gcsInputFile": {"path": "${input("android-library-debug-androidTest.apk")}"}}]},
                            "testRunnerClass": "androidx.test.runner.AndroidJUnitRunner",
                            "instrumentationTimeout": "300s"
                          }
                        },
                        "allocationConfig": {
                          "deviceConfigs": [{"requirement": {"deviceId": "smallphone-arm-35"}, "actions": [{"androidLogcat": {}}]}]
                        },
                        "settings": {
                          "retrySettings": {
                            "flakyTestRetryStrategy": {"flakyTestAttempts": 1, "parallelRetry": false, "testReductionMode": "NO_REDUCTION"}
                          }
                        }
                      }
                    ],
                    "outputDirectoryConfig": {"gcsOutputDirectory": {"path": "gs://example-bucket/automation/sessions"}}
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `reproduces the application request that Device Run accepted`() {
        val spec = validator.validate(
            RunOptions(
                bucket = "example-bucket",
                job = RunOptions.Job(
                    retries = RunOptions.Retries(flakyTestAttempts = 2, testReductionMode = TestReductionMode.REDUCE_TO_FAILED_TEST_CASES),
                    pathsToPull = listOf("/sdcard/test-output", APPLICATION_FILES),
                    capture = RunOptions.Capture(video = ArtifactCapture.ON_FAILURE),
                ),
            ),
        )
        val inputs = libraryInputs.copy(
            device = DeviceSpec(name = "mediumPhone", deviceId = "mediumphone-arm-35", locale = null, orientation = null),
            packages = TestPackages(
                testApk = GcsUri("example-bucket", "$INPUTS/$TEST_APK_SHA256/app-debug-androidTest.apk"),
                targetApp = listOf(GcsUri("example-bucket", "$INPUTS/$APP_APK_SHA256/app-debug.apk")),
            ),
            pushedFiles = listOf(
                PreparedFile(
                    name = "probeInput",
                    uri = GcsUri("example-bucket", "$INPUTS/$PROBE_INPUT_SHA256/probe-input.txt"),
                    checksum = "sha256:$PROBE_INPUT_SHA256",
                    destinationPath = "/sdcard/test-output/probe-input.txt",
                ),
            ),
            runnerArguments = validator.validateRunnerArguments(
                spec.job,
                mapOf(
                    "class" to
                        listOf("OutcomesTest", "ParameterizedTest", "ProbeTest").joinToString(",") {
                            "io.technoirlab.device.run.fixture.$it"
                        },
                    "probeOption" to "probe-value",
                ),
            ),
            testRunName = ":android-application debug",
            labels = mapOf("project" to ":android-application", "variant" to "debug", "device" to "mediumPhone"),
        )
        val acceptedRequest = parseDeviceRunFixture<Session>("sessions/flaky-retry-passed/request.json")
        // The probe named its session and left parallel retries unset; the runner names the session after the project, variant,
        // and device, and always sends its sequential retry policy.
        acceptedRequest.sessionConfig.setDisplayName("android-application debug mediumPhone")
        acceptedRequest.sessionConfig.jobConfigs.single().settings.retrySettings.flakyTestRetryStrategy.setParallelRetry(false)

        val session = factory.create(spec, inputs)

        assertThat(canonicalJson(session)).isEqualTo(canonicalJson(acceptedRequest))
    }

    @ParameterizedTest
    @CsvSource("DISABLED, false", "ON_FAILURE, true", "ALWAYS, true")
    fun `collects device logs unless the logcat policy disables them`(logcat: ArtifactCapture, collected: Boolean) {
        val capture = RunOptions.Capture(logcat = logcat)
        val spec = validator.validate(RunOptions(bucket = "example-bucket", job = RunOptions.Job(capture = capture)))

        val actions = factory.create(spec, libraryInputs).deviceConfig().actions.orEmpty()

        assertThat(actions.any { it.androidLogcat != null }).isEqualTo(collected)
    }

    @ParameterizedTest
    @CsvSource("DISABLED, ", "ON_FAILURE, true", "ALWAYS, false")
    fun `records videos according to the video policy`(video: String, discardOnPass: Boolean?) {
        val capture = RunOptions.Capture(video = ArtifactCapture.valueOf(video))
        val spec = validator.validate(RunOptions(bucket = "example-bucket", job = RunOptions.Job(capture = capture)))

        val videoActions = factory.create(spec, libraryInputs).deviceConfig().actions.mapNotNull { it.androidRecordVideo }

        assertThat(videoActions.map { it.discardOnPass }).isEqualTo(listOfNotNull(discardOnPass))
    }

    @Test
    fun `orders device actions like gcloud and maps sharding, retries, and device settings`() {
        val spec = validator.validate(
            RunOptions(
                bucket = "example-bucket",
                job = RunOptions.Job(
                    orchestratorVersion = "auto",
                    sharding = RunOptions.Sharding.Smart(Duration.ofSeconds(150).plusMillis(250), "library", maxShardCount = 4),
                    retries = RunOptions.Retries(flakyTestAttempts = 3, parallelRetry = true),
                    pathsToPull = listOf("/sdcard/test-output"),
                    capture = RunOptions.Capture(video = ArtifactCapture.ALWAYS),
                ),
            ),
        )
        val inputs = libraryInputs.copy(
            device = DeviceSpec(name = "pixel", deviceId = "tokay-35", locale = "fr-CA", orientation = DeviceOrientation.LANDSCAPE),
            packages = libraryInputs.packages.copy(
                targetApp = listOf(input("base.apk"), input("split_config.arm64_v8a.apk")),
            ),
            pushedFiles = listOf(PreparedFile("config", GcsUri("example-bucket", "config.json"), "sha256:0", "/sdcard/config.json")),
        )

        val job = factory.create(spec, inputs).sessionConfig.jobConfigs.single()

        assertThat(job.allocationConfig.deviceConfigs.single().actions.map { it.keys.single() }).containsExactly(
            "androidLogcat",
            "androidInstallPackages",
            "androidPushFiles",
            "androidSwitchLocale",
            "androidOrientation",
            "androidPullFiles",
            "androidRecordVideo",
        )
        assertThat(
            job.allocationConfig.deviceConfigs.single().actions[1].androidInstallPackages.targetApp.files.map {
                it.gcsInputFile.path
            },
        )
            .containsExactly(input("base.apk").toString(), input("split_config.arm64_v8a.apk").toString())
        assertThat(canonicalJson(job.action.androidInstrumentationTest.smartSharding)).isEqualTo(
            canonicalJson(
                """
                {
                  "targetedShardDuration": "150.250s",
                  "maxShardCount": 4,
                  "timingRecord": {"gcsInputFile": {"path": "gs://example-bucket/automation/smart-sharding/library.yaml"}}
                }
                """.trimIndent(),
            ),
        )
        assertThat(job.action.androidInstrumentationTest.orchestratorVersion).isEqualTo("auto")
        assertThat(job.settings.retrySettings.flakyTestRetryStrategy.parallelRetry).isTrue()
        assertThat(job.allocationConfig.deviceConfigs.single().actions[3].androidSwitchLocale.localeCode).isEqualTo("fr-CA")
        assertThat(job.allocationConfig.deviceConfigs.single().actions[4].androidOrientation.orientation).isEqualTo("landscape")
    }

    @Test
    fun `maps uniform sharding without a timing record`() {
        val sharding = RunOptions.Sharding.Uniform(shardCount = 4)
        val spec = validator.validate(RunOptions(bucket = "example-bucket", job = RunOptions.Job(sharding = sharding)))

        val test = factory.create(spec, libraryInputs).sessionConfig.jobConfigs.single().action.androidInstrumentationTest

        assertThat(test.uniformSharding.shardCount).isEqualTo(4)
        assertThat(test.smartSharding).isNull()
    }

    @Test
    fun `sanitizes generated display names but sends the test run labels unchanged`() {
        val spec = validator.validate(RunOptions(bucket = "example-bucket", job = RunOptions.Job(labels = mapOf("suite" to "smoke"))))
        val inputs = libraryInputs.copy(
            device = DeviceSpec(name = "pixel9Api35", deviceId = "tokay-35", locale = null, orientation = null),
            testRunName = ":features:checkout:payment-methods:credit-cards freeStagingDebug",
            labels = mapOf(
                "project" to ":features:checkout:payment-methods:credit-cards",
                "variant" to "freeStagingDebug",
                "device" to "pixel9Api35",
            ),
        )

        val config = factory.create(spec, inputs).sessionConfig

        assertThat(config.displayName).isEqualTo("features-checkout-payment-methods-credit-cards freeStagingDebug")
        assertThat(config.jobConfigs.single().labels).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "suite" to "smoke",
                "project" to ":features:checkout:payment-methods:credit-cards",
                "variant" to "freeStagingDebug",
                "device" to "pixel9Api35",
            ),
        )
    }

    private fun Session.deviceConfig() = sessionConfig.jobConfigs.single().allocationConfig.deviceConfigs.single()

    private companion object {
        private const val INPUTS = "automation/inputs"
        private const val TEST_APK_SHA256 = "9904e4c89f25ee7183f0dca9aaa3b177875d6567b2203a9b0750d017357a8bbd"
        private const val APP_APK_SHA256 = "e6456d1edc58b760b929edced40c2e778d77b2ab14bc7bb409352be188a791ac"
        private const val PROBE_INPUT_SHA256 = "fa1f29ea65735b49902631b4229fe1205304eeeb526f31951fcf33c33c1c30c0"
        private const val APPLICATION_FILES = "/sdcard/Android/data/io.technoirlab.device.run.fixture.application/files"

        private fun input(name: String): GcsUri = GcsUri("example-bucket", "$INPUTS/0000/$name")
    }
}
