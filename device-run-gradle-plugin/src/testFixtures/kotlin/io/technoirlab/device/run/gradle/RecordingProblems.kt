package io.technoirlab.device.run.gradle

import org.gradle.api.Action
import org.gradle.api.problems.AdditionalData
import org.gradle.api.problems.Problem
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.ProblemReporter
import org.gradle.api.problems.ProblemSpec
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity

/**
 * Records the problems reported to it instead of passing them to Gradle: [reported] problems, which Gradle treats as warnings, and
 * [thrown] problems, which fail the build.
 *
 * Problems keep their contextual label and solutions; specifying other parts of a problem fails.
 */
@Suppress("UnstableApiUsage")
internal class RecordingProblems : Problems {
    private val reporter = Reporter()

    val reported = mutableListOf<RecordedProblem>()
    val thrown = mutableListOf<RecordedProblem>()

    override fun getReporter(): ProblemReporter = reporter

    private inner class Reporter : ProblemReporter {
        override fun create(problemId: ProblemId, action: Action<in ProblemSpec>): Problem =
            Spec(problemId).also { action.execute(it) }.toProblem()

        override fun report(problemId: ProblemId, spec: Action<in ProblemSpec>) {
            report(create(problemId, spec))
        }

        override fun report(problem: Problem) {
            reported += problem as RecordedProblem
        }

        override fun report(problems: Collection<Problem>) {
            problems.forEach(::report)
        }

        override fun throwing(exception: Throwable, problemId: ProblemId, spec: Action<in ProblemSpec>): RuntimeException =
            throwing(exception, create(problemId, spec))

        override fun throwing(exception: Throwable, problem: Problem): RuntimeException = throwing(exception, listOf(problem))

        override fun throwing(exception: Throwable, problems: Collection<Problem>): RuntimeException {
            thrown += problems.map { it as RecordedProblem }
            return exception as? RuntimeException ?: RuntimeException(exception)
        }
    }

    private class Spec(
        private val problemId: ProblemId,
    ) : ProblemSpec {
        private var contextualLabel: String? = null
        private val solutions = mutableListOf<String>()

        override fun contextualLabel(contextualLabel: String): ProblemSpec = apply { this.contextualLabel = contextualLabel }

        override fun solution(solution: String): ProblemSpec = apply { solutions += solution }

        override fun details(details: String): ProblemSpec = unsupported()

        override fun documentedAt(url: String): ProblemSpec = unsupported()

        override fun fileLocation(path: String): ProblemSpec = unsupported()

        override fun lineInFileLocation(path: String, line: Int): ProblemSpec = unsupported()

        override fun lineInFileLocation(path: String, line: Int, column: Int): ProblemSpec = unsupported()

        override fun lineInFileLocation(path: String, line: Int, column: Int, length: Int): ProblemSpec = unsupported()

        override fun offsetInFileLocation(path: String, offset: Int, length: Int): ProblemSpec = unsupported()

        override fun stackLocation(): ProblemSpec = unsupported()

        override fun <T : AdditionalData> additionalData(type: Class<T>, config: Action<in T>): ProblemSpec = unsupported()

        override fun withException(t: Throwable): ProblemSpec = unsupported()

        @Deprecated("Gradle derives the severity from the reporting method.")
        override fun severity(severity: Severity): ProblemSpec = unsupported()

        fun toProblem(): RecordedProblem = RecordedProblem("${problemId.group.name}:${problemId.name}", contextualLabel, solutions.toList())

        private fun unsupported(): Nothing = throw UnsupportedOperationException("RecordingProblems records no such part of a problem")
    }
}
