package io.technoirlab.device.run.core.testrun.results

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.IOException
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream

/**
 * Parses JUnit XML reports written by Device Run, whose roots are `testsuite` for job reports and `testsuites` for execution reports.
 *
 * Parsing is secure: document type declarations are rejected, and neither external entities nor XInclude are processed.
 */
internal class JUnitReportParser {
    /**
     * Parses the report at [path].
     *
     * @throws MalformedReportException when the file is not a well-formed JUnit XML report.
     */
    fun parse(path: Path): JUnitReport {
        val document = try {
            path.inputStream().use { input ->
                newDocumentBuilderFactory().newDocumentBuilder()
                    .apply { setErrorHandler(FailingErrorHandler()) }
                    .parse(input, path.toUri().toString())
            }
        } catch (e: SAXException) {
            throw MalformedReportException("$path is not well-formed XML: ${e.message}", e)
        } catch (e: IOException) {
            throw MalformedReportException("$path cannot be read: ${e.message}", e)
        }
        val root = document.documentElement
        val suites = when (root.tagName) {
            TEST_SUITE -> listOf(root)
            TEST_SUITES -> root.childElements(TEST_SUITE)
            else -> throw MalformedReportException(
                "$path has the root element <${root.tagName}> instead of <$TEST_SUITE> or <$TEST_SUITES>.",
            )
        }
        return JUnitReport(
            testCases = suites.flatMap { suite -> suite.childElements(TEST_CASE).map(::testCase) },
            time = suites.singleOrNull()?.attributeOrNull("time"),
        )
    }

    @Suppress("HttpUrlsUsage")
    private fun newDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }

    private fun testCase(element: Element): JUnitTestCase = JUnitTestCase(
        className = element.getAttribute("classname"),
        name = element.getAttribute("name"),
        time = element.attributeOrNull("time"),
        flaky = element.getAttribute("flaky") == "true",
        failure = element.outcomeElement("failure"),
        error = element.outcomeElement("error"),
        skipped = element.outcomeElement("skipped"),
    )

    private fun Element.outcomeElement(tagName: String): JUnitOutcomeElement? = childElements(tagName).firstOrNull()?.let { child ->
        JUnitOutcomeElement(child.attributeOrNull("message"), child.attributeOrNull("type"), child.textContent)
    }

    private fun Element.childElements(tagName: String): List<Element> =
        List(childNodes.length) { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tagName }

    private fun Element.attributeOrNull(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

    /**
     * Fails parsing on every error instead of printing it.
     */
    private class FailingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit

        override fun error(exception: SAXParseException): Unit = throw exception

        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }

    private companion object {
        private const val TEST_SUITE = "testsuite"
        private const val TEST_SUITES = "testsuites"
        private const val TEST_CASE = "testcase"
    }
}
