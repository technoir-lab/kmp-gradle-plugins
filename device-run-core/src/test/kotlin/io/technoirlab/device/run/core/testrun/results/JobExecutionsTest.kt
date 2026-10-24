package io.technoirlab.device.run.core.testrun.results

import io.technoirlab.device.run.core.recordLogs
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.event.Level
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.div

class JobExecutionsTest {
    private val logs = recordLogs()
    private val passedSession = buildSession { result("PASSED") }

    @TempDir
    private lateinit var mirrorDirectory: Path

    @Test
    fun `lists the executions that the report of the job lists, with or without outputs`() {
        val session = buildSession {
            result("PASSED")
            job(JOB) {
                execution("execution-000")
                execution("execution-001")
            }
            job("smallPhone") { execution("execution-002") }
        }
        val artifacts = mirror("mediumPhone/execution-000/junit.xml" to executionReport("a.FirstTest"))

        val executions = JobExecutions(session, artifacts, JOB).executions

        assertThat(executions)
            .extracting({ it.name }, { it.reportEntry?.displayName }, { it.report?.testCases?.map { testCase -> testCase.key } })
            .containsExactly(
                tuple("execution-000", "execution-000", listOf("a.FirstTest#runs")),
                tuple("execution-001", "execution-001", null),
            )
    }

    @Test
    fun `lists the executions that the report omits by the names of their directories, with the retries of a shard after it`() {
        val session = buildSession {
            result("FAILED")
            job(JOB) { result("FAILED") }
        }
        val artifacts = mirror(
            "mediumPhone/junit.xml" to JOB_REPORT,
            "mediumPhone/execution-000/junit.xml" to executionReport("a.FirstTest"),
            "mediumPhone/execution-000-rerun-000/junit.xml" to executionReport("a.FirstTest"),
            "mediumPhone/execution-001/instrument.log" to "",
            "smallPhone/execution-002/junit.xml" to executionReport("a.FirstTest"),
        )

        val executions = JobExecutions(session, artifacts, JOB).executions

        assertThat(executions).extracting({ it.name }, { it.reportEntry }).containsExactly(
            tuple("execution-000", null),
            tuple("execution-000-rerun-000", null),
            tuple("execution-001", null),
        )
    }

    @Test
    fun `takes another directory of the job for an execution only when the report lists it`() {
        val session = buildSession {
            result("PASSED")
            job(JOB) { execution("custom-shard") }
        }
        val artifacts = mirror(
            "mediumPhone/coverage/coverage.ec" to "coverage",
            "mediumPhone/custom-shard/junit.xml" to executionReport("a.FirstTest"),
        )

        val executions = JobExecutions(session, artifacts, JOB).executions

        assertThat(executions.map { it.name }).containsExactly("custom-shard")
    }

    @Test
    fun `reads the report of each execution, named junit xml or test_result xml, in its directory`() {
        val artifacts = mirror(
            "mediumPhone/execution-000/junit.xml" to executionReport("a.FirstTest"),
            "mediumPhone/execution-001/test_result.xml" to executionReport("b.SecondTest"),
            "mediumPhone/execution-002/artifacts/sdcard/junit.xml" to executionReport("c.ThirdTest"),
        )

        val executions = JobExecutions(passedSession, artifacts, JOB).executions

        assertThat(executions.associate { it.name to it.report?.testCases?.map { testCase -> testCase.key } }).containsExactlyEntriesOf(
            mapOf(
                "execution-000" to listOf("a.FirstTest#runs"),
                "execution-001" to listOf("b.SecondTest#runs"),
                "execution-002" to null,
            ),
        )
    }

    @Test
    fun `reads the instrumentation log of each execution in its directory`() {
        val scenario = CapturedScenario("crash-tests-lost")
        val artifacts = scenario.mirrorTo(mirrorDirectory)

        val executions = JobExecutions(scenario.session, artifacts, scenario.job).executions

        assertThat(executions)
            .extracting({ it.name }, { it.instrumentationLog?.plannedTests }, { it.instrumentationLog?.endedNormally })
            .containsExactly(tuple("execution-000", 4, false), tuple("execution-000-rerun-000", 1, true))
    }

    @Test
    fun `maps each test that exactly one execution ran to the device log of that execution once it is downloaded`() {
        val reports = mirror(
            "mediumPhone/execution-000/junit.xml" to executionReport("a.FirstTest", "a.RetriedTest"),
            "mediumPhone/execution-000-rerun-000/junit.xml" to executionReport("a.RetriedTest"),
            "mediumPhone/execution-001/artifacts/sdcard/logcat.txt" to "pulled",
            "mediumPhone/execution-001/junit.xml" to executionReport("b.SecondTest"),
            "mediumPhone/execution-002/junit.xml" to executionReport("c.ThirdTest"),
        )
        val deviceLogs = mirror(
            "mediumPhone/execution-000/logcat.txt" to "logcat",
            "mediumPhone/execution-000-rerun-000/logcat.txt" to "logcat",
            "mediumPhone/execution-001/logcat.txt" to "logcat",
        )
        // The verdict on the tests, which decides whether device logs are downloaded, comes first.
        val executions = JobExecutions(passedSession, reports, JOB)

        val testLogs = executions.deviceLogs(reports + deviceLogs)

        assertThat(testLogs.mapValues { (_, log) -> log.relativePath }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "a.FirstTest#runs" to "mediumPhone/execution-000/logcat.txt",
                "b.SecondTest#runs" to "mediumPhone/execution-001/logcat.txt",
            ),
        )
    }

    @Test
    fun `starts each test with the only execution that ran it, or else with the session`() {
        val session = buildSession {
            result("PASSED")
            startTime(SESSION_START)
            job(JOB) {
                execution("execution-000") { startTime(SESSION_START.plusSeconds(10)) }
                execution("execution-000-rerun-000") { startTime(SESSION_START.plusSeconds(40)) }
                execution("execution-001") { startTime(SESSION_START.plusSeconds(11)) }
                execution("execution-002") { startTime("2026-10-11 18:04:57") }
            }
        }
        val artifacts = mirror(
            "mediumPhone/execution-000/test_result.xml" to executionReport("a.FirstTest", "a.RetriedTest"),
            "mediumPhone/execution-000-rerun-000/test_result.xml" to executionReport("a.RetriedTest"),
            "mediumPhone/execution-001/test_result.xml" to executionReport("a.SecondTest"),
            "mediumPhone/execution-002/test_result.xml" to executionReport("a.ThirdTest"),
        )
        val executions = JobExecutions(session, artifacts, JOB)

        val startTimes = listOf("a.FirstTest", "a.SecondTest", "a.ThirdTest", "a.RetriedTest").map { executions.startTimeOf(outcome(it)) }

        assertThat(startTimes).containsExactly(
            SESSION_START.plusSeconds(10),
            SESSION_START.plusSeconds(11),
            SESSION_START,
            SESSION_START,
        )
    }

    @Test
    fun `starts each test with the session when the final report omits the job`() {
        val session = buildSession {
            result("PASSED")
            startTime(SESSION_START)
        }
        val artifacts = mirror("mediumPhone/execution-000/test_result.xml" to executionReport("a.FirstTest"))
        val executions = JobExecutions(session, artifacts, JOB)

        val startTime = executions.startTimeOf(outcome("a.FirstTest"))

        assertThat(startTime).isEqualTo(SESSION_START)
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["<testsuites"])
    fun `maps no test to an execution when the report of an execution with outputs is missing or malformed`(report: String?) {
        val session = buildSession {
            result("PASSED")
            startTime(SESSION_START)
            job(JOB) {
                execution("execution-000") { startTime(SESSION_START.plusSeconds(10)) }
                execution("execution-001") { startTime(SESSION_START.plusSeconds(11)) }
            }
        }
        val outputs = mapOf(
            "mediumPhone/execution-000/junit.xml" to executionReport("a.FirstTest"),
            "mediumPhone/execution-000/logcat.txt" to "logcat",
            "mediumPhone/execution-001/logcat.txt" to "logcat",
        )
        val artifacts = mirrorOutputs(mirrorDirectory, outputs + listOfNotNull(report?.let { "mediumPhone/execution-001/junit.xml" to it }))
        val executions = JobExecutions(session, artifacts, JOB)

        val testLogs = executions.deviceLogs(artifacts)
        val startTime = executions.startTimeOf(outcome("a.FirstTest"))

        // Device logs stay attached to the suite only.
        assertThat(testLogs).isEmpty()
        assertThat(startTime).isEqualTo(SESSION_START)
    }

    @Test
    fun `warns about the report of an execution that it cannot read`() {
        val artifacts = mirror("mediumPhone/execution-000/junit.xml" to "<testsuites")
        val report = mirrorDirectory / "mediumPhone/execution-000/junit.xml"

        JobExecutions(passedSession, artifacts, JOB)

        assertThat(logs.entries.map { it.first }).containsExactly(Level.WARN)
        assertThat(logs.entries.single().second).startsWith(
            "Unable to read the Device Run execution report mediumPhone/execution-000/junit.xml: $report is not well-formed XML: ",
        )
    }

    private fun mirror(vararg outputs: Pair<String, String>): List<MirroredArtifact> = mirrorOutputs(mirrorDirectory, mapOf(*outputs))

    private fun executionReport(vararg classNames: String): String =
        "<testsuites><testsuite>${classNames.joinToString("") { "<testcase classname=\"$it\" name=\"runs\"/>" }}</testsuite></testsuites>"

    private fun outcome(className: String): TestOutcome = TestOutcome(
        className = className,
        name = "runs",
        time = "1.0",
        status = TestOutcome.Status.PASSED,
        detail = null,
        failedAttempts = emptyList(),
    )

    private companion object {
        private const val JOB = "mediumPhone"
        private const val JOB_REPORT = """<testsuite><testcase classname="a.FirstTest" name="runs"/></testsuite>"""
        private val SESSION_START = Instant.parse("2026-10-11T18:04:45.328Z")
    }
}
