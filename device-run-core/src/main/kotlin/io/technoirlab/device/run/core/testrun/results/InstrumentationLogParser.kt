package io.technoirlab.device.run.core.testrun.results

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.bufferedReader

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
     *
     * Malformed UTF-8 is replaced, so that a single bad byte does not hide the rest of the log.
     */
    fun parse(path: Path): InstrumentationLog? = try {
        // Unlike Path.readLines and Path.useLines, an InputStreamReader replaces malformed input rather than throwing.
        path.bufferedReader().useLines { lines -> OutputReader().apply { lines.forEach(::read) }.toLog() }
    } catch (_: IOException) {
        null
    }

    /**
     * Reads the lines of an instrumentation log in order, keeping only the values that it uses.
     */
    private class OutputReader {
        private val failedEntries = mutableListOf<String>()
        private val result = mutableMapOf<String, StringBuilder>()
        private var status = mutableMapOf<String, StringBuilder>()

        // The value that the next lines continue, unless the parser ignores it, such as a stack trace.
        private var continuedValue: StringBuilder? = null
        private var plannedTests: Int? = null
        private var resultCode: Int? = null
        private var startedEntries = 0
        private var classEntries = 0
        private var isClassEntryOpen = false
        private var isInstrumentationOutput = false

        fun read(line: String) {
            when {
                line.startsWith(STATUS) -> startValue(status, STATUS_KEYS, line.removePrefix(STATUS))
                line.startsWith(STATUS_CODE) -> endEntry(line.removePrefix(STATUS_CODE).trim().toIntOrNull())
                line.startsWith(RESULT) -> startValue(result, RESULT_KEYS, line.removePrefix(RESULT))
                line.startsWith(RESULT_CODE) -> {
                    resultCode = line.removePrefix(RESULT_CODE).trim().toIntOrNull()
                    continuedValue = null
                }
                else -> {
                    continuedValue?.append('\n')?.append(line)
                    return
                }
            }
            // Any key or code line shows that the file is the output of an instrumentation run.
            isInstrumentationOutput = true
        }

        fun toLog(): InstrumentationLog? {
            if (!isInstrumentationOutput) {
                return null
            }
            val shortMessage = result[SHORT_MESSAGE]?.toString()
            val (summaryFailureCount, summaryFailures) = summaryFailures(result[STREAM]?.lines().orEmpty())
            return InstrumentationLog(
                plannedTests = plannedTests,
                startedEntries = startedEntries,
                // A class event that never ended, such as one that crashed the process, stands for its tests too.
                classEntries = classEntries + if (isClassEntryOpen) 1 else 0,
                failedEntries = failedEntries.toList(),
                endedNormally = resultCode == RESULT_OK && shortMessage == null,
                endMessage = shortMessage,
                summaryFailureCount = summaryFailureCount,
                summaryFailures = summaryFailures,
            )
        }

        private fun startValue(values: MutableMap<String, StringBuilder>, readKeys: Set<String>, keyValue: String) {
            val key = keyValue.substringBefore('=')
            continuedValue = if (key in readKeys) {
                StringBuilder(keyValue.substringAfter('=', missingDelimiterValue = "")).also { values[key] = it }
            } else {
                null
            }
        }

        private fun endEntry(code: Int?) {
            status[NUM_TESTS]?.toString()?.trim()?.toIntOrNull()?.let { plannedTests = it }
            val test = status[TEST]?.toString()?.trim().orEmpty()
            when (code) {
                null -> {}
                STARTED -> {
                    startedEntries++
                    isClassEntryOpen = test == CLASS_EVENT_TEST
                }
                else -> {
                    if (test == CLASS_EVENT_TEST && code != IGNORED) {
                        classEntries++
                    }
                    if (code == FAILED) {
                        failedEntries += describe(status[CLASS]?.toString()?.trim().orEmpty(), test)
                    }
                    isClassEntryOpen = false
                }
            }
            status = mutableMapOf()
            continuedValue = null
        }

        /**
         * Returns how JUnit describes a test in summaries, `<test>(<class>)`, or the class for an event of the whole class, whose
         * test is `null`, or empty as Android Test Orchestrator describes it.
         */
        private fun describe(className: String, test: String): String =
            if (test == CLASS_EVENT_TEST || test.isEmpty()) className else "$test($className)"

        /**
         * Returns the number of failures that the summary of the run lists and the failures that it numbers, or no failures when the
         * run printed no readable summary of failures.
         */
        private fun summaryFailures(stream: List<String>): Pair<Int, List<InstrumentationLog.SummaryFailure>> {
            val (header, match) = stream.withIndex()
                .firstNotNullOfOrNull { (index, line) -> SUMMARY_HEADER.matchEntire(line)?.let { index to it } }
                ?: return 0 to emptyList()
            val count = match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return 0 to emptyList()
            val failures = mutableListOf<InstrumentationLog.SummaryFailure>()
            var index = header + 1
            for (number in 1..count) {
                val prefix = "$number) "
                val entry = (index until stream.size).firstOrNull { stream[it].startsWith(prefix) } ?: break
                val firstLine = stream.getOrNull(entry + 1)?.takeUnless { it.startsWith("${number + 1}) ") }.orEmpty()
                val test = stream[entry].removePrefix(prefix)
                failures += InstrumentationLog.SummaryFailure(CLASS_EVENT_SUMMARY.matchEntire(test)?.groupValues?.get(1) ?: test, firstLine)
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
        private const val CLASS_EVENT_TEST = "null"
        private const val STARTED = 1
        private const val FAILED = -2
        private const val IGNORED = -3

        // Activity.RESULT_OK, which am instrument reports when the run finished.
        private const val RESULT_OK = -1

        // JUnit's TextListener prints "There was 1 failure:" or "There were <n> failures:".
        private val SUMMARY_HEADER = Regex("There (?:was 1 failure|were (\\d+) failures):")

        // Android Test Orchestrator lists an event of a whole class as `(<class>)` in summaries.
        private val CLASS_EVENT_SUMMARY = Regex("\\((.+)\\)")

        // The keys of the values that the parser reads, of status blocks and of the result.
        private val STATUS_KEYS = setOf(NUM_TESTS, CLASS, TEST)
        private val RESULT_KEYS = setOf(SHORT_MESSAGE, STREAM)
    }
}
