package io.technoirlab.device.run.core.testrun.results

import io.technoirlab.device.run.core.client.deviceRunFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeText

class InstrumentationLogParserTest {
    private val parser = InstrumentationLogParser()

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `reads a run that a process crash ended before it started every planned test`() {
        val log = parseFixture("sessions/crash-tests-lost/outputs/smallPhone/execution-000/instrument.log")

        assertThat(log).isEqualTo(
            InstrumentationLog(
                plannedTests = 4,
                startedEntries = 2,
                failedEntries = listOf("b_crashesOnFirstAttempt(io.technoirlab.rollup.probe.CrashTest)"),
                endedNormally = false,
                endMessage = "Process crashed.",
                summaryFailureCount = 0,
                summaryFailures = emptyList(),
            ),
        )
    }

    @Test
    fun `reads a run that ended normally without failures`() {
        val log = parseFixture("sessions/crash-tests-lost/outputs/smallPhone/execution-000-rerun-000/instrument.log")

        assertThat(log).isEqualTo(
            InstrumentationLog(
                plannedTests = 1,
                startedEntries = 1,
                failedEntries = emptyList(),
                endedNormally = true,
                endMessage = null,
                summaryFailureCount = 0,
                summaryFailures = emptyList(),
            ),
        )
    }

    @Test
    fun `reads the failures that the summary lists along with the failed entries`() {
        val log = parseFixture("sessions/retry-outcomes/outputs/smallPhone/execution-000/instrument.log")

        assertThat(log?.plannedTests).isEqualTo(7)
        assertThat(log?.startedEntries).isEqualTo(7)
        assertThat(log?.endedNormally).isTrue()
        assertThat(log?.failedEntries).containsExactly(
            "coin0(io.technoirlab.rollup.probe.ShardCoinTest)",
            "coin2(io.technoirlab.rollup.probe.ShardCoinTest)",
            "failsOnFirstAttempt(io.technoirlab.rollup.probe.ShardCoinTest)",
        )
        assertThat(log?.summaryFailureCount).isEqualTo(3)
        assertThat(log?.summaryFailures).extracting<String> { it.test }.containsExactlyElementsOf(log?.failedEntries)
    }

    @Test
    fun `reads class-level events as one entry each, described by their class`() {
        val log = parseFixture("instrument-logs/class-level-events.log")

        assertThat(log).isEqualTo(
            InstrumentationLog(
                plannedTests = 8,
                startedEntries = 6,
                failedEntries = listOf("io.technoirlab.rollup.plan.FailBeforeClassTest"),
                endedNormally = true,
                endMessage = null,
                summaryFailureCount = 1,
                summaryFailures = listOf(
                    InstrumentationLog.SummaryFailure(
                        test = "io.technoirlab.rollup.plan.FailBeforeClassTest",
                        firstLine = "java.lang.IllegalStateException: Fails the class in @BeforeClass",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `reads the class failures that only the summary of Android Test Orchestrator lists`() {
        val log = parseFixture("sessions/orchestrator-class-failure/outputs/smallPhone/execution-000/instrument.log")

        val classFailure = InstrumentationLog.SummaryFailure(
            test = "(io.technoirlab.rollup.plan.FailBeforeClassTest)",
            firstLine = "java.lang.IllegalStateException: Fails the class in @BeforeClass",
        )
        assertThat(log).isEqualTo(
            InstrumentationLog(
                plannedTests = 3,
                startedEntries = 1,
                failedEntries = emptyList(),
                endedNormally = true,
                endMessage = null,
                summaryFailureCount = 2,
                summaryFailures = listOf(classFailure, classFailure),
            ),
        )
    }

    @Test
    fun `reads a crash that Android Test Orchestrator reports as a failed entry before running the remaining tests`() {
        val log = parseFixture("instrument-logs/orchestrator-crash.log")

        assertThat(log?.plannedTests).isEqualTo(11)
        assertThat(log?.startedEntries).isEqualTo(7)
        assertThat(log?.endedNormally).isTrue()
        assertThat(log?.failedEntries).containsExactly("b_crashesTheProcess(io.technoirlab.rollup.plan.CrashTest)")
        assertThat(log?.summaryFailureCount).isEqualTo(3)
        assertThat(log?.summaryFailures).extracting<String> { it.test }.containsExactly(
            "b_crashesTheProcess(io.technoirlab.rollup.plan.CrashTest)",
            "(io.technoirlab.rollup.plan.FailBeforeClassTest)",
            "(io.technoirlab.rollup.plan.FailBeforeClassTest)",
        )
        assertThat(
            log?.summaryFailures?.first()?.firstLine,
        ).isEqualTo("java.lang.IllegalStateException: Crashes the instrumentation process")
    }

    @ParameterizedTest
    @CsvSource("instrument-logs/smart-sharding-execution-000.log, 3", "instrument-logs/smart-sharding-execution-001.log, 4")
    fun `reads the shards of smart sharding, which plan only their own tests`(fixture: String, plannedTests: Int) {
        val log = parseFixture(fixture)

        assertThat(log?.plannedTests).isEqualTo(plannedTests)
        assertThat(log?.endedNormally).isTrue()
        assertThat(log?.failedEntries).isEmpty()
        assertThat(log?.summaryFailureCount).isZero()
    }

    @Test
    fun `reads a run that stopped without a result as ended abnormally`() {
        val text = deviceRunFixture("sessions/crash-tests-lost/outputs/smallPhone/execution-000/instrument.log")
            .substringBefore("INSTRUMENTATION_RESULT: ")

        val log = parse(text)

        assertThat(log?.endedNormally).isFalse()
        assertThat(log?.endMessage).isNull()
        assertThat(log?.startedEntries).isEqualTo(2)
    }

    @Test
    fun `yields no log for a missing file or a file without instrumentation output`() {
        val missing = parser.parse(tempDir / "missing.log")
        val placeholder = parse("Placeholder for the Device Run output smallPhone/execution-000/instrument.log")

        assertThat(missing).isNull()
        assertThat(placeholder).isNull()
    }

    private fun parseFixture(fixture: String): InstrumentationLog? = parse(deviceRunFixture(fixture))

    private fun parse(text: String): InstrumentationLog? = parser.parse((tempDir / "instrument.log").apply { writeText(text) })
}
