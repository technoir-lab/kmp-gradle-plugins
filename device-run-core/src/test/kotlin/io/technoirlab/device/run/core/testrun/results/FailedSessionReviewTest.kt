package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.Session
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class FailedSessionReviewTest {
    private val failedSessionReview = FailedSessionReview()

    @Test
    fun `passes the tests of a session that failed only because no attempt of its shard passed every test`() {
        val session = failedSession {
            failedAttempt("execution-000")
            failedAttempt("execution-000-rerun-000")
        }

        val review = review(session, session.attempt("execution-000"), session.attempt("execution-000-rerun-000"))

        assertThat(review).isEqualTo(
            testsPass(
                reason = "JOB_HAS_FAILED_TESTS",
                failure = "Device Run failed session session-1 (JOB_HAS_FAILED_TESTS) because no attempt of execution-000 passed every " +
                    "test.",
            ),
        )
    }

    @Test
    fun `names the cause of the session in the warning when its job gives no cause`() {
        val session = failedSession(jobCause = null) { failedAttempt("execution-000") }

        val review = review(session, session.attempt("execution-000"))

        assertThat(review).isEqualTo(
            testsPass(
                reason = "SESSION_HAS_FAILED_JOBS",
                failure = "Device Run failed session session-1 (SESSION_HAS_FAILED_JOBS) because no attempt of execution-000 passed " +
                    "every test.",
            ),
        )
    }

    @Test
    fun `names in order each shard of which no attempt passed every test in the warning`() {
        val session = failedSession {
            failedAttempt("execution-002")
            failedAttempt("execution-001")
            passedAttempt("execution-001-rerun-000")
            failedAttempt("execution-000")
        }

        val review = review(
            session,
            session.attempt("execution-002"),
            session.attempt("execution-001"),
            session.attempt("execution-001-rerun-000", report = attemptReport()),
            session.attempt("execution-000"),
        )

        assertThat(review).isEqualTo(
            testsPass(
                reason = "JOB_HAS_FAILED_TESTS",
                failure = "Device Run failed session session-1 (JOB_HAS_FAILED_TESTS) because no attempt of execution-000 or " +
                    "execution-002 passed every test.",
            ),
        )
    }

    @Test
    fun `warns without naming a shard when an attempt of each shard passed every test`() {
        // The review does not depend on why Device Run failed a session each of whose shards had an attempt that passed every test.
        val session = failedSession {
            failedAttempt("execution-000")
            passedAttempt("execution-000-rerun-000")
        }

        val review = review(session, session.attempt("execution-000"), session.attempt("execution-000-rerun-000", report = attemptReport()))

        assertThat(review).isEqualTo(
            testsPass(reason = "JOB_HAS_FAILED_TESTS", failure = "Device Run failed session session-1 (JOB_HAS_FAILED_TESTS)."),
        )
    }

    @Test
    fun `passes the tests of a session by the outputs of the passed and failed attempts that its final report omits`() {
        val session = failedSession(jobCause = null)
        val failedAttempt = session.attempt("execution-000", log = completedRunLog(plannedTests = 2, failedTests = listOf(FAILED_TEST)))
        val passedAttempt = session.attempt("execution-000-rerun-000", report = attemptReport(), log = completedRunLog(plannedTests = 2))

        val review = review(session, failedAttempt, passedAttempt)

        assertThat(review).isEqualTo(
            testsPass(reason = "SESSION_HAS_FAILED_JOBS", failure = "Device Run failed session session-1 (SESSION_HAS_FAILED_JOBS)."),
        )
    }

    @Test
    fun `counts an attempt that the final report omits as failed when its log shows a failed entry`() {
        val session = failedSession(jobCause = null)
        val log = completedRunLog(plannedTests = 2, failedTests = listOf(FAILED_TEST), summaryFailures = emptyList())

        val review = review(session, session.attempt("execution-000", log = log))

        assertThat(review).isEqualTo(
            testsPass(
                reason = "SESSION_HAS_FAILED_JOBS",
                failure = "Device Run failed session session-1 (SESSION_HAS_FAILED_JOBS) because no attempt of execution-000 passed " +
                    "every test.",
            ),
        )
    }

    @Test
    fun `counts an attempt that the final report omits as failed when only the summary of its log lists a failure`() {
        val session = failedSession(jobCause = null)
        val summaryFailure = InstrumentationLog.SummaryFailure("io.example.SetupTest", "java.lang.IllegalStateException: Fails")
        val log = completedRunLog(plannedTests = 2, summaryFailures = listOf(summaryFailure))

        val review = review(session, session.attempt("execution-000", log = log))

        assertThat(review).isEqualTo(
            testsPass(
                reason = "SESSION_HAS_FAILED_JOBS",
                failure = "Device Run failed session session-1 (SESSION_HAS_FAILED_JOBS) because no attempt of execution-000 passed " +
                    "every test.",
            ),
        )
    }

    @Test
    fun `confirms a failed execution by a test that ended with an error in its report`() {
        val session = failedSession { failedAttempt("execution-000") }

        val review = review(session, session.attempt("execution-000", report = attemptReport(error = CRASH)))

        assertThat(review).isEqualTo(
            testsPass(
                reason = "JOB_HAS_FAILED_TESTS",
                failure = "Device Run failed session session-1 (JOB_HAS_FAILED_TESTS) because no attempt of execution-000 passed every " +
                    "test.",
            ),
        )
    }

    @Test
    fun `keeps the failure of a session that Device Run gave no cause`() {
        val session = failedSession(sessionCause = null) { failedAttempt("execution-000") }

        val review = review(session, session.attempt("execution-000"))

        assertThat(review).isEqualTo(unexplained("Device Run gave no cause"))
    }

    @Test
    fun `keeps the failure of a session that Device Run blames on something other than failed jobs`() {
        val session = failedSession(sessionCause = "SESSION_HAS_ERROR_JOBS") { failedAttempt("execution-000") }

        val review = review(session, session.attempt("execution-000"))

        assertThat(review).isEqualTo(unexplained("the cause is SESSION_HAS_ERROR_JOBS, not SESSION_HAS_FAILED_JOBS"))
    }

    @ParameterizedTest
    @CsvSource(
        "INFRA, PLATFORM_INFRASTRUCTURE_ERROR, INFRA / PLATFORM_INFRASTRUCTURE_ERROR",
        "CUSTOMER, TEST_TIMED_OUT, CUSTOMER / TEST_TIMED_OUT",
        "INFRA, TEST_CASE_FAILURE, INFRA / TEST_CASE_FAILURE",
        ", , no type / no reason",
    )
    fun `keeps the failure of a session whose failed execution has a cause other than failed tests`(
        type: String?,
        reason: String?,
        cause: String,
    ) {
        val session = failedSession { execution("execution-000") { result("FAILED", reason = reason, type = type) } }

        val review = review(session, session.attempt("execution-000"))

        assertThat(review).isEqualTo(unexplained("execution-000 failed with $cause, not CUSTOMER / TEST_CASE_FAILURE"))
    }

    @ParameterizedTest
    @CsvSource("RESULT_UNSPECIFIED, RESULT_UNSPECIFIED", ", no result")
    fun `keeps the failure of a session that lists an execution that neither passed nor failed`(resultType: String?, reported: String) {
        val session = failedSession { execution("execution-000") { resultType?.let { result(it) } } }

        val review = review(session, session.attempt("execution-000"))

        assertThat(review).isEqualTo(unexplained("Device Run reports execution-000 with $reported"))
    }

    @Test
    fun `keeps the failure of a session whose failed execution has no readable report`() {
        val session = failedSession { failedAttempt("execution-000") }

        val review = review(session, session.attempt("execution-000", report = null))

        assertThat(review).isEqualTo(unexplained("execution-000 failed, but its report is missing or malformed"))
    }

    @Test
    fun `keeps the failure of a session whose failed execution reports no failed test`() {
        val session = failedSession { failedAttempt("execution-000") }

        val review = review(session, session.attempt("execution-000", report = attemptReport()))

        assertThat(review).isEqualTo(unexplained("execution-000 failed, but its report lists no failed test"))
    }

    @Test
    fun `keeps the failure of a session whose final report omits an attempt without a readable report`() {
        val session = failedSession { failedAttempt("execution-000") }
        val omittedAttempt = session.attempt("execution-000-rerun-000", report = null, log = completedRunLog(plannedTests = 2))

        val review = review(session, session.attempt("execution-000"), omittedAttempt)

        assertThat(review).isEqualTo(
            unexplained("the session report omits execution-000-rerun-000, whose outputs lack a readable report"),
        )
    }

    @Test
    fun `keeps the failure of a session whose final report omits an attempt without an instrumentation log`() {
        val session = failedSession { failedAttempt("execution-000") }
        val omittedAttempt = session.attempt("execution-000-rerun-000", log = null)

        val review = review(session, session.attempt("execution-000"), omittedAttempt)

        assertThat(review).isEqualTo(
            unexplained("the session report omits execution-000-rerun-000, whose outputs lack an instrumentation log that ended normally"),
        )
    }

    @Test
    fun `keeps the failure of a session whose final report omits an attempt whose instrumentation log ended abnormally`() {
        val session = failedSession { failedAttempt("execution-000") }
        val omittedAttempt = session.attempt("execution-000-rerun-000", log = crashedRunLog(plannedTests = 2, startedEntries = 1))

        val review = review(session, session.attempt("execution-000"), omittedAttempt)

        assertThat(review).isEqualTo(
            unexplained("the session report omits execution-000-rerun-000, whose outputs lack an instrumentation log that ended normally"),
        )
    }

    @Test
    fun `keeps the failure of a session none of whose executions failed`() {
        val session = failedSession {
            passedAttempt("execution-000")
            passedAttempt("execution-001")
        }

        val review = review(
            session,
            session.attempt("execution-000", report = attemptReport()),
            session.attempt("execution-001", report = attemptReport()),
        )

        assertThat(review).isEqualTo(unexplained("no execution failed"))
    }

    @Test
    fun `keeps the failure of a session without executions`() {
        val session = failedSession()

        val review = review(session)

        assertThat(review).isEqualTo(unexplained("no execution failed"))
    }

    @Test
    fun `explains the failure of a session by each problem that leaves it unexplained`() {
        val session = failedSession(sessionCause = null) { failedAttempt("execution-000") }
        val omittedAttempt = session.attempt("execution-000-rerun-000", report = null)

        val review = review(session, session.attempt("execution-000", report = attemptReport()), omittedAttempt)

        assertThat(review).isEqualTo(
            unexplained(
                "Device Run gave no cause",
                "execution-000 failed, but its report lists no failed test",
                "the session report omits execution-000-rerun-000, whose outputs lack a readable report",
            ),
        )
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["PASSED", "ERROR", "TIMED_OUT", "CANCELLED"])
    fun `reviews no session that Device Run did not fail`(resultType: String?) {
        val session = buildSession(SESSION_NAME) {
            resultType?.let { result(it) }
            job(DEVICE_NAME) { failedAttempt("execution-000") }
        }

        val review = review(session, session.attempt("execution-000"))

        assertThat(review).isNull()
    }

    @Test
    fun `reviews no session without a readable job report`() {
        val session = failedSession { failedAttempt("execution-000") }

        val review = review(session, session.attempt("execution-000"), outcomes = null)

        assertThat(review).isNull()
    }

    @Test
    fun `reviews no session whose job report lists no tests`() {
        val session = failedSession { failedAttempt("execution-000") }

        val review = review(session, session.attempt("execution-000"), outcomes = emptyList())

        assertThat(review).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["FAILED", "ERROR"])
    fun `reviews no session whose job report lists a test that failed or ended with an error`(status: String) {
        val session = failedSession { failedAttempt("execution-000") }
        val failingOutcome = PASSING_OUTCOMES.single().copy(name = "fails", status = TestOutcome.Status.valueOf(status))

        val review = review(session, session.attempt("execution-000"), outcomes = PASSING_OUTCOMES + failingOutcome)

        assertThat(review).isNull()
    }

    @ParameterizedTest
    @CsvSource("PLATFORM_INFRASTRUCTURE_ERROR, INFRA", "INCOMPLETE_TEST_RUN, INSTRUMENTATION")
    fun `reviews no session whose platform failures explain its failure`(reason: String, classification: String) {
        // An execution that ended with an error, or an instrumentation run that left tests unrun, explains the failure.
        val session = failedSession { failedAttempt("execution-000") }
        val platformFailure = PlatformFailure(reason, classification, "The failure of the session.")

        val review = review(session, session.attempt("execution-000"), platformFailures = listOf(platformFailure))

        assertThat(review).isNull()
    }

    /**
     * Reviews [session] with the [executions] of its job, the [outcomes] of its job report, and the [platformFailures] found so far.
     */
    private fun review(
        session: Session,
        vararg executions: JobExecution,
        outcomes: List<TestOutcome>? = PASSING_OUTCOMES,
        platformFailures: List<PlatformFailure> = emptyList(),
    ): FailedSessionReview.Review? = failedSessionReview.review(
        session = session,
        outcomes = outcomes,
        platformFailures = platformFailures,
        executions = executions.toList(),
        deviceName = DEVICE_NAME,
    )

    /**
     * Returns a session that Device Run failed with [sessionCause] for its job, which failed with [jobCause] and whose report
     * [executions] build.
     */
    private fun failedSession(
        sessionCause: String? = "SESSION_HAS_FAILED_JOBS",
        jobCause: String? = "JOB_HAS_FAILED_TESTS",
        executions: SessionReportBuilder.JobReportBuilder.() -> Unit = {},
    ): Session = buildSession(SESSION_NAME) {
        result("FAILED", reason = sessionCause)
        job(DEVICE_NAME) {
            result("FAILED", reason = jobCause)
            executions()
        }
    }

    private fun SessionReportBuilder.JobReportBuilder.failedAttempt(name: String) {
        execution(name) { result("FAILED", reason = "TEST_CASE_FAILURE", type = "CUSTOMER") }
    }

    private fun SessionReportBuilder.JobReportBuilder.passedAttempt(name: String) {
        execution(name) { result("PASSED") }
    }

    /**
     * Returns the attempt [name] of the job of the session, with the entry that the session report lists for it, if any, its own
     * [report], and its instrumentation [log].
     */
    private fun Session.attempt(
        name: String,
        report: JUnitReport? = attemptReport(failure = ASSERTION_FAILURE),
        log: InstrumentationLog? = null,
    ): JobExecution = JobExecution(
        name = name,
        reportEntry = sessionReport.jobReports.orEmpty().flatMap { it.executionReports.orEmpty() }.find { it.displayName == name },
        report = report,
        instrumentationLog = lazyOf(log),
    )

    /**
     * Returns the report of an attempt that ran the test `a.FirstTest#runs`, which failed with the trace [failure] or ended with the
     * trace [error] when one is given, and passed otherwise.
     */
    private fun attemptReport(failure: String? = null, error: String? = null): JUnitReport = JUnitReport(
        testCases = listOf(
            JUnitTestCase(
                className = "a.FirstTest",
                name = "runs",
                time = "1.0",
                flaky = false,
                failures = listOfNotNull(failure?.let { JUnitOutcomeElement(message = null, type = null, text = it) }),
                error = error?.let { JUnitOutcomeElement(message = null, type = null, text = it) },
                skipped = null,
            ),
        ),
        time = "1.0",
    )

    private fun testsPass(reason: String, failure: String): FailedSessionReview.Review = FailedSessionReview.Review.TestsPass(
        ReportWarning(reason, "$failure Every test passed in some attempt, so the tests of $DEVICE_NAME pass."),
    )

    private fun unexplained(vararg problems: String): FailedSessionReview.Review = FailedSessionReview.Review.Unexplained(
        problems.map { problem ->
            PlatformFailure(
                RunnerFailure.UNEXPLAINED_SESSION_FAILURE,
                "Device Run failed the session although its job report lists no failed test: $problem.",
            )
        },
    )

    private companion object {
        private const val SESSION_NAME = "projects/example-project/locations/global/sessions/session-1"
        private const val DEVICE_NAME = "mediumPhone"
        private const val FAILED_TEST = "runs(a.FirstTest)"
        private const val ASSERTION_FAILURE = "java.lang.AssertionError: Fails\n\tat a.FirstTest.runs(FirstTest.kt:12)"
        private const val CRASH = "java.lang.IllegalStateException: Crashes\n\tat a.FirstTest.runs(FirstTest.kt:12)"
        private val PASSING_OUTCOMES = listOf(
            TestOutcome(
                className = "a.FirstTest",
                name = "runs",
                time = "1.0",
                status = TestOutcome.Status.PASSED,
                detail = null,
                failedAttempts = emptyList(),
            ),
        )
    }
}
