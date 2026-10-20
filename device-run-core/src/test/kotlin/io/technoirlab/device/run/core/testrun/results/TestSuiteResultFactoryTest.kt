package io.technoirlab.device.run.core.testrun.results

import com.google.protobuf.Timestamp
import com.google.testing.platform.proto.api.core.TestArtifactProto.Artifact
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.io.path.Path

class TestSuiteResultFactoryTest {
    private val factory = TestSuiteResultFactory()

    @Test
    fun `starts a test case at the estimated start time and ends it after the reported duration`() {
        val test = executedTest(time = "1.5")

        val result = create(test)

        val testCase = result.testResultList.single().testCase
        assertThat(testCase.startTime.toInstant()).isEqualTo(START_TIME)
        assertThat(testCase.endTime.toInstant()).isEqualTo(START_TIME.plusMillis(1500))
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "soon", "-0.5", "1e300"])
    fun `leaves the end time unset when the reported duration is missing or invalid`(time: String?) {
        val test = executedTest(time)

        val result = create(test)

        val testCase = result.testResultList.single().testCase
        assertThat(testCase.startTime.toInstant()).isEqualTo(START_TIME)
        assertThat(testCase.hasEndTime()).isFalse()
    }

    @Test
    fun `writes no timestamps when the start time is unknown`() {
        val test = executedTest(startTime = null)

        val result = create(test)

        val testCase = result.testResultList.single().testCase
        assertThat(testCase.hasStartTime()).isFalse()
        assertThat(testCase.hasEndTime()).isFalse()
    }

    @Test
    fun `attaches the device information to every test and to the suite, before their own files`() {
        val logcat = ResultArtifact("logcat", Path("device-run/mediumPhone/execution-000/logcat.txt"), "text/plain")
        val video = ResultArtifact("deviceRun.video", Path("device-run/mediumPhone/execution-000/video-1.webm"), "video/webm")

        val result = factory.create(
            suiteName = "mediumPhone",
            deviceInfo = DEVICE_INFO,
            status = TestStatus.PASSED,
            tests = listOf(executedTest(artifacts = listOf(logcat)), executedTest()),
            artifacts = listOf(video),
            platformFailures = emptyList(),
            warnings = emptyList(),
        )

        assertThat(result.testResultList.map { it.outputArtifactList.labelsAndPaths() }).containsExactly(
            listOf("device-info" to DEVICE_INFO.toString(), "logcat" to logcat.path.toString()),
            listOf("device-info" to DEVICE_INFO.toString()),
        )
        assertThat(result.outputArtifactList.labelsAndPaths()).containsExactly(
            "device-info" to DEVICE_INFO.toString(),
            "deviceRun.video" to video.path.toString(),
        )
    }

    private fun create(test: ExecutedTest): TestSuiteResult = factory.create(
        suiteName = "mediumPhone",
        deviceInfo = DEVICE_INFO,
        status = TestStatus.PASSED,
        tests = listOf(test),
        artifacts = emptyList(),
        platformFailures = emptyList(),
        warnings = emptyList(),
    )

    private fun executedTest(
        time: String? = "1.5",
        startTime: Instant? = START_TIME,
        artifacts: List<ResultArtifact> = emptyList(),
    ): ExecutedTest {
        val outcome = TestOutcome(
            className = "a.FirstTest",
            name = "runs",
            time = time,
            status = TestOutcome.Status.PASSED,
            recovered = false,
            detail = null,
        )
        return ExecutedTest(outcome, startTime, artifacts)
    }

    private fun List<Artifact>.labelsAndPaths(): List<Pair<String, String>> = map { it.label.label to it.sourcePath.path }

    private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

    private companion object {
        private val START_TIME = Instant.parse("2026-10-11T18:04:55.940Z")
        private val DEVICE_INFO = Path("results/device-info.pb")
    }
}
