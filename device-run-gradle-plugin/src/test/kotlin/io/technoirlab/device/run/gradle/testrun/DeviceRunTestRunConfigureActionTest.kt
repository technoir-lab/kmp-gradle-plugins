package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.dsl.CommonExtension
import io.technoirlab.device.run.core.testrun.RunOptions
import io.technoirlab.device.run.gradle.DeviceRunBuildService
import io.technoirlab.device.run.gradle.DeviceRunPlugin
import io.technoirlab.device.run.gradle.api.CapturePolicy
import io.technoirlab.device.run.gradle.api.DeviceOrientation
import io.technoirlab.device.run.gradle.api.DeviceRunDevice
import io.technoirlab.device.run.gradle.api.DeviceRunExtension
import io.technoirlab.device.run.gradle.api.RetryScope
import io.technoirlab.device.run.gradle.api.ShardingStrategy
import io.technoirlab.device.run.gradle.assertFinal
import io.technoirlab.gradle.test.kit.createRootProject
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.newInstance
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

class DeviceRunTestRunConfigureActionTest {
    private lateinit var project: Project
    private lateinit var device: DeviceRunDevice
    private lateinit var input: DeviceRunTestRunInput
    private lateinit var repository: Path

    @BeforeEach
    fun setUp() {
        project = createRootProject("device-run-fixture") {
            apply(plugin = "com.android.application")
            apply<DeviceRunPlugin>()
        }
        repository = project.projectDir.toPath() / "repository"
        project.repositories.maven { url = repository.toUri() }
        device = project.objects.newInstance<DeviceRunDevice>("mediumPhone")
        input = DeviceRunTestRunConfigureAction(project.objects, project).configureTaskInput(device)
    }

    @Test
    fun `device inputs follow device properties configured after input creation`() {
        device.locale.set("sv-SE")
        device.orientation.set(DeviceOrientation.LANDSCAPE)
        project.layout.buildDirectory.set(project.layout.projectDirectory.dir("output"))

        assertThat(input.locale.get()).isEqualTo("sv-SE")
        assertThat(input.orientation.get()).isEqualTo(DeviceOrientation.LANDSCAPE)
        assertThat(input.buildName.get()).isEqualTo(project.rootProject.name)
        assertThat(input.pushManifest.get()).isEqualTo(project.layout.projectDirectory.file("output/device-run/push-manifest.json"))
        assertThat(input.buildService.get()).isSameAs(DeviceRunBuildService.registerIfAbsent(project.gradle).get())
        assertFinal(input.locale, "en-US")
        assertFinal(input.orientation, DeviceOrientation.PORTRAIT)
    }

    @Test
    fun `run options follow the DSL configured after input creation`() {
        project.extensions.getByType<DeviceRunExtension>().apply {
            storage { bucket.set("example-bucket") }
            waitTimeout.set(Duration.ofHours(1))
            instrumentationTimeout.set(Duration.ofMinutes(20))
            labels.put("suite", "nightly")
            sharding {
                uniform { shardCount.set(5) }
                smart {
                    targetDuration.set(Duration.ofMinutes(4))
                    timingRecordName.set("nightly")
                    maxShardCount.set(3)
                }
            }
            retries {
                maxAttempts.set(3)
                parallel.set(true)
                scope.set(RetryScope.FAILED_TEST_CASES)
            }
            capture {
                logcat.set(CapturePolicy.DISABLED)
                video.set(CapturePolicy.ON_FAILURE)
            }
            files { pathsToPull.add("/sdcard/output") }
        }

        input.options.apply {
            assertThat(bucket.get()).isEqualTo("example-bucket")
            assertThat(waitTimeout.get()).isEqualTo(Duration.ofHours(1))
            assertThat(instrumentationTimeout.get()).isEqualTo(Duration.ofMinutes(20))
            assertThat(labels.get()).containsExactlyEntriesOf(mapOf("suite" to "nightly"))
            assertThat(shardingStrategy.get()).isEqualTo(ShardingStrategy.SMART)
            assertThat(shardCount.get()).isEqualTo(5)
            assertThat(shardTargetDuration.get()).isEqualTo(Duration.ofMinutes(4))
            assertThat(timingRecordName.get()).isEqualTo("nightly")
            assertThat(maxShardCount.get()).isEqualTo(3)
            assertThat(maxAttempts.get()).isEqualTo(3)
            assertThat(parallelRetries.get()).isTrue()
            assertThat(retryScope.get()).isEqualTo(RetryScope.FAILED_TEST_CASES)
            assertThat(pathsToPull.get()).containsExactly("/sdcard/output")
            assertThat(logcat.get()).isEqualTo(CapturePolicy.DISABLED)
            assertThat(video.get()).isEqualTo(CapturePolicy.ON_FAILURE)
        }
    }

    @Test
    fun `orchestrator execution runs the orchestrator version of androidTestUtil`() {
        val orchestratorApk = publishApk("androidx.test", "orchestrator", "1.6.1")
        val testServicesApk = publishApk("androidx.test.services", "test-services", "1.6.0")
        publishApk("com.example", "helper", "1.0")
        project.dependencies.add(ANDROID_TEST_UTIL, "androidx.test:orchestrator:1.6.1")
        project.dependencies.add(ANDROID_TEST_UTIL, "androidx.test.services:test-services:1.6.0")
        project.dependencies.add(ANDROID_TEST_UTIL, "com.example:helper:1.0")
        project.extensions.getByType<CommonExtension>().testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"

        assertThat(input.options.orchestratorVersion.get()).isEqualTo("1.6.1")
        assertThat(input.options.suppliedHelperApks).containsExactlyInAnyOrder(orchestratorApk, testServicesApk)
    }

    @Test
    fun `orchestrator execution without an orchestrator in androidTestUtil runs the default version of Device Run`() {
        project.extensions.getByType<CommonExtension>().testOptions.execution = "ANDROID_TEST_ORCHESTRATOR"

        assertThat(input.options.orchestratorVersion.get()).isEqualTo(RunOptions.Job.AUTO_ORCHESTRATOR_VERSION)
        assertThat(input.options.suppliedHelperApks).isEmpty()
    }

    @Test
    fun `host execution runs without the orchestrator and keeps the test services that Device Run supplies`() {
        val orchestratorApk = publishApk("androidx.test", "orchestrator", "1.6.1")
        val testServicesApk = publishApk("androidx.test.services", "test-services", "1.6.0")
        project.dependencies.add(ANDROID_TEST_UTIL, "androidx.test:orchestrator:1.6.1")
        project.dependencies.add(ANDROID_TEST_UTIL, "androidx.test.services:test-services:1.6.0")

        assertThat(input.options.orchestratorVersion.orNull).isNull()
        assertThat(input.options.suppliedHelperApks).containsExactlyInAnyOrder(orchestratorApk, testServicesApk)
    }

    /**
     * Publishes an APK module to the Maven repository of the project and returns the APK.
     */
    private fun publishApk(group: String, module: String, version: String): File {
        val directory = (repository / group.replace('.', '/') / module / version).createDirectories()
        (directory / "$module-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$module</artifactId>
                <version>$version</version>
                <packaging>apk</packaging>
            </project>
            """.trimIndent(),
        )
        return (directory / "$module-$version.apk").apply { writeText(module) }.toFile()
    }

    private companion object {
        private const val ANDROID_TEST_UTIL = "androidTestUtil"
    }
}
