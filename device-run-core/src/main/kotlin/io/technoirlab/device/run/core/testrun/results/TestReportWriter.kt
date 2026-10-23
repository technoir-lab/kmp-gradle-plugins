package io.technoirlab.device.run.core.testrun.results

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.outputStream

/**
 * Writes the JUnit XML report that AGP reads for its HTML test report.
 *
 * AGP counts only `failure` elements as failed tests, so tests that ended with an error are written as failures. The counts are
 * derived from the written test cases. Failed attempts follow Maven Surefire, which JUnit XML consumers such as
 * action-junit-report and TeamCity read: a flaky test passed and has a `flakyFailure` for each failed attempt, and a failed test has
 * a `failure` for its first failed attempt and a `rerunFailure` for each later one. Neither contains `system-out`, which AGP would
 * read as the output of the test.
 */
internal class TestReportWriter {
    /**
     * Writes [outcomes] to [path] with the suite [properties].
     *
     * @param time the duration of the suite in seconds, as reported.
     */
    fun write(path: Path, outcomes: List<TestOutcome>, properties: Map<String, String>, time: String?) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val failures = outcomes.count { it.status.isFailure }
        val suite = document.createElement("testsuite").apply {
            setAttribute("name", "")
            setAttribute("tests", outcomes.size.toString())
            setAttribute("failures", failures.toString())
            setAttribute("errors", "0")
            setAttribute("skipped", outcomes.count { it.status == TestOutcome.Status.SKIPPED }.toString())
            setAttribute("flakes", outcomes.count { it.flaky }.toString())
            time?.let { setAttribute("time", it) }
        }
        document.appendChild(suite)
        suite.appendChild(document.properties(properties))
        outcomes.forEach { suite.appendChild(document.testCase(it)) }
        @Suppress("HttpUrlsUsage")
        val transformer = TransformerFactory.newInstance()
            .apply { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            .newTransformer()
            .apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }
        path.outputStream().use { output -> transformer.transform(DOMSource(document), StreamResult(output)) }
    }

    private fun Document.properties(properties: Map<String, String>): Element = createElement("properties").apply {
        for ((name, value) in properties) {
            appendChild(
                createElement("property").apply {
                    setAttribute("name", name)
                    setAttribute("value", value)
                },
            )
        }
    }

    private fun Document.testCase(outcome: TestOutcome): Element = createElement("testcase").apply {
        setAttribute("name", outcome.name)
        setAttribute("classname", outcome.className)
        outcome.time?.let { setAttribute("time", it) }
        if (outcome.flaky) {
            setAttribute("flaky", "true")
        }
        when (outcome.status) {
            TestOutcome.Status.FAILED -> {
                appendChild(outcomeElement("failure", outcome.detail))
                outcome.failedAttempts.drop(1).forEach { appendChild(attemptElement("rerunFailure", it)) }
            }
            // An error that some attempts end with fails the test; the failed attempts are reruns.
            TestOutcome.Status.ERROR -> {
                appendChild(outcomeElement("failure", outcome.detail))
                outcome.failedAttempts.forEach { appendChild(attemptElement("rerunFailure", it)) }
            }
            TestOutcome.Status.SKIPPED -> appendChild(outcomeElement("skipped", outcome.detail))
            TestOutcome.Status.PASSED -> outcome.failedAttempts.forEach { appendChild(attemptElement("flakyFailure", it)) }
        }
    }

    private fun Document.outcomeElement(tagName: String, detail: TestOutcome.Detail?): Element = createElement(tagName).apply {
        if (detail != null) {
            setAttribute("message", detail.message)
            detail.type?.let { setAttribute("type", it) }
            textContent = detail.trace
        }
    }

    /**
     * Returns a Surefire element of a failed attempt, which keeps the trace in a `stackTrace` element.
     */
    private fun Document.attemptElement(tagName: String, detail: TestOutcome.Detail): Element = createElement(tagName).apply {
        setAttribute("message", detail.message)
        detail.type?.let { setAttribute("type", it) }
        appendChild(createElement("stackTrace").apply { textContent = detail.trace })
    }
}
