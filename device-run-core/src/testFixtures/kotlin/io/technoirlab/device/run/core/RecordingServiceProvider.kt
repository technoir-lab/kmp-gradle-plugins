package io.technoirlab.device.run.core

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * Routes SLF4J logging in tests to a [LogRecorder]. SLF4J discovers the provider through
 * `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`, which only the runner's tests contain, so that consumers of the fixtures
 * keep their own provider.
 */
internal class RecordingServiceProvider : SLF4JServiceProvider {
    private val logRecorder = LogRecorder()
    private val markerFactory = BasicMarkerFactory()
    private val mdcAdapter = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = logRecorder

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    override fun getRequestedApiVersion(): String = REQUESTED_API_VERSION

    override fun initialize() = Unit

    private companion object {
        private const val REQUESTED_API_VERSION = "2.0.99"
    }
}
