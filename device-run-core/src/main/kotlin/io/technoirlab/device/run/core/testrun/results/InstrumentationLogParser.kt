package io.technoirlab.device.run.core.testrun.results

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Parses the `instrument.log` of an execution, the raw output of `am instrument -r`.
 *
 * The output consists of status blocks, `INSTRUMENTATION_STATUS: <key>=<value>` lines that a status code ends, followed by the
 * result of the run, `INSTRUMENTATION_RESULT: <key>=<value>` lines and a result code. Values continue on the following lines
 * until the next key or code, such as the stack traces of failures and the summary that JUnit prints at the end of the run.
 */
internal class InstrumentationLogParser {
    /**
     * Parses the log at [path], or returns null when it is missing, unreadable, or not the output of an instrumentation run.
     */
    fun parse(path: Path): InstrumentationLog? {
        val lines = try {
            path.readLines()
        } catch (_: IOException) {
            return null
        }
        return OutputReader().apply { lines.forEach(::read) }.toLog()
    }

    /**
     * Reads the lines of an instrumentation log in order.
     */
    private class OutputReader {
        private val entryCodes = mutableListOf<Int>()
        private val failedEntries = mutableListOf<String>()
        private val result = mutableMapOf<String, String>()
        private var status = mutableMapOf<String, String>()

        // The values and the key of the value that the next lines continue.
        private var continuedValue: Pair<MutableMap<String, String>, String>? = null
        private var plannedTests: Int? = null
        private var resultCode: Int? = null
        private var isInstrumentationOutput = false

        fun read(line: String) {
            when {
                line.startsWith(STATUS) -> startValue(status, line.removePrefix(STATUS))
                line.startsWith(STATUS_CODE) -> endEntry(line.removePrefix(STATUS_CODE).trim().toIntOrNull())
                line.startsWith(RESULT) -> startValue(result, line.removePrefix(RESULT))
                line.startsWith(RESULT_CODE) -> {
                    resultCode = line.removePrefix(RESULT_CODE).trim().toIntOrNull()
                    isInstrumentationOutput = true
                    continuedValue = null
                }
                else -> continuedValue?.let { (values, key) -> values[key] = values.getValue(key) + "\n" + line }
            }
        }

        fun toLog(): InstrumentationLog? {
            if (!isInstrumentationOutput) {
                return null
            }
            val shortMessage = result[SHORT_MESSAGE]
            val summary = summaryFailures(result[STREAM].orEmpty().lines())
            return InstrumentationLog(
                plannedTests = plannedTests,
                startedEntries = entryCodes.count { it == STARTED },
                failedEntries = failedEntries.toList(),
                endedNormally = resultCode == RESULT_OK && shortMessage == null,
                endMessage = shortMessage,
                summaryFailureCount = summary?.first ?: 0,
                summaryFailures = summary?.second.orEmpty(),
            )
        }

        private fun startValue(values: MutableMap<String, String>, keyValue: String) {
            val key = keyValue.substringBefore('=')
            values[key] = keyValue.substringAfter('=', missingDelimiterValue = "")
            continuedValue = values to key
            isInstrumentationOutput = true
        }

        private fun endEntry(code: Int?) {
            status[NUM_TESTS]?.trim()?.toIntOrNull()?.let { plannedTests = it }
            if (code != null) {
                entryCodes += code
            }
            if (code == FAILED) {
                failedEntries += describe(status[CLASS].orEmpty().trim(), status[TEST].orEmpty().trim())
            }
            status = mutableMapOf()
            continuedValue = null
            isInstrumentationOutput = true
        }

        /**
         * Returns how JUnit describes a test in summaries: `<test>(<class>)`, or the class for an event of the whole class, which
         * the runner reports as the test `null`.
         */
        private fun describe(className: String, test: String): String = if (test == CLASS_LEVEL_TEST) className else "$test($className)"

        /**
         * Returns the number of failures that the summary of the run lists and the failures that it numbers, or null when the run
         * printed no summary of failures.
         */
        private fun summaryFailures(stream: List<String>): Pair<Int, List<InstrumentationLog.SummaryFailure>>? {
            val header = stream.indexOfFirst { SUMMARY_HEADER.matches(it) }
            if (header < 0) {
                return null
            }
            val count = SUMMARY_HEADER.matchEntire(stream[header])!!.groupValues[1].ifEmpty { "1" }.toInt()
            val failures = mutableListOf<InstrumentationLog.SummaryFailure>()
            var index = header + 1
            for (number in 1..count) {
                val prefix = "$number) "
                val entry = (index until stream.size).firstOrNull { stream[it].startsWith(prefix) } ?: break
                val firstLine = stream.getOrNull(entry + 1)?.takeUnless { it.startsWith("${number + 1}) ") }.orEmpty()
                failures += InstrumentationLog.SummaryFailure(stream[entry].removePrefix(prefix), firstLine)
                index = entry + 1
            }
            return count to failures
        }
    }

    private companion object {
        private const val STATUS = "INSTRUMENTATION_STATUS: "
        private const val STATUS_CODE = "INSTRUMENTATION_STATUS_CODE: "
        private const val RESULT = "INSTRUMENTATION_RESULT: "
        private const val RESULT_CODE = "INSTRUMENTATION_CODE: "
        private const val NUM_TESTS = "numtests"
        private const val CLASS = "class"
        private const val TEST = "test"
        private const val SHORT_MESSAGE = "shortMsg"
        private const val STREAM = "stream"
        private const val CLASS_LEVEL_TEST = "null"
        private const val STARTED = 1
        private const val FAILED = -2

        // Activity.RESULT_OK, which am instrument reports when the run finished.
        private const val RESULT_OK = -1

        // JUnit's TextListener prints "There was 1 failure:" or "There were <n> failures:".
        private val SUMMARY_HEADER = Regex("There (?:was 1 failure|were (\\d+) failures):")
    }
}
