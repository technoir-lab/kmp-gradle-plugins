package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.ExecutionReport

/**
 * An execution of a job: a shard, such as `execution-001`, or a retry attempt of a shard, such as `execution-000-rerun-000`.
 *
 * @property name the display name of the execution, which also names its output directory.
 * @property shard the shard that the execution ran: `execution-NNN` for its retries `execution-NNN-rerun-MMM`, and the name of any
 * other execution.
 * @property reportEntry the entry of the execution in the session report, or null when the report omits the execution.
 * @property report the JUnit report of the execution, or null when it is missing or malformed.
 * @property instrumentationLog the instrumentation log of the execution, or null when it is missing or unreadable.
 * @property logcat the device log of the execution, or null when it was not downloaded.
 */
internal data class JobExecution(
    val name: String,
    val shard: String,
    val reportEntry: ExecutionReport?,
    val report: JUnitReport?,
    val instrumentationLog: InstrumentationLog?,
    val logcat: MirroredArtifact?,
) {
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
         * The names of the JUnit report of an execution. Executions write `junit.xml`; older executions on virtual devices wrote
         * `test_result.xml`, as in the fixtures.
         */
        val REPORT_NAMES = setOf("junit.xml", "test_result.xml")
    }
}
