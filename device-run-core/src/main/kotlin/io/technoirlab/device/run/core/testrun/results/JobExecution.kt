package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.ExecutionReport

/**
 * An execution of a job: a shard, such as `execution-001`, or a retry attempt of a shard, such as `execution-000-rerun-000`.
 *
 * @property name the display name of the execution, which also names its output directory.
 * @property reportEntry the entry of the execution in the session report, or null when the report omits the execution.
 * @property report the JUnit report of the execution, or null when it is missing or malformed.
 * @param instrumentationLog reads the instrumentation log of the execution, which only the verdict on a session whose tests ran
 * needs.
 */
internal class JobExecution(
    val name: String,
    val reportEntry: ExecutionReport?,
    val report: JUnitReport?,
    instrumentationLog: Lazy<InstrumentationLog?>,
) {
    /**
     * The shard that the execution ran: `execution-NNN` for its retries `execution-NNN-rerun-MMM`, and the name of any other
     * execution.
     */
    val shard = NAME.matchEntire(name)?.groupValues?.get(1) ?: name

    /**
     * The instrumentation log of the execution, read when first used, or null when it is missing or unreadable.
     */
    val instrumentationLog: InstrumentationLog? by instrumentationLog

    companion object {
        /**
         * The name of the device log of an execution.
         */
        const val LOGCAT = "logcat.txt"

        /**
         * The name of the instrumentation log of an execution.
         */
        const val INSTRUMENT_LOG = "instrument.log"

        /**
         * The name of the JUnit report of an execution, and of the job report `<job>/junit.xml`.
         */
        const val JUNIT_REPORT = "junit.xml"

        /**
         * The names of the JUnit report of an execution. Executions write [JUNIT_REPORT]; older executions on virtual devices wrote
         * `test_result.xml`, as in the fixtures.
         */
        val REPORT_NAMES = setOf(JUNIT_REPORT, "test_result.xml")

        /**
         * Returns whether [name] names an execution: a shard, `execution-NNN`, or a retry attempt of one, `execution-NNN-rerun-MMM`.
         */
        fun isExecutionName(name: String): Boolean = NAME.matches(name)

        // The name of a retry attempt extends the name of its shard, which the group captures.
        private val NAME = Regex("(execution-\\d+)(?:-rerun-\\d+)?")
    }
}
