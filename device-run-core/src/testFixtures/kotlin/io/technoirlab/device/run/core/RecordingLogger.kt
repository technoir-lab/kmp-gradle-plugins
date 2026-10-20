package io.technoirlab.device.run.core

import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter

/**
 * Passes the messages logged at every level to [recorder].
 */
internal class RecordingLogger(
    name: String,
    private val recorder: LogRecorder,
) : LegacyAbstractLogger() {
    init {
        this.name = name
    }

    override fun isTraceEnabled(): Boolean = true

    override fun isDebugEnabled(): Boolean = true

    override fun isInfoEnabled(): Boolean = true

    override fun isWarnEnabled(): Boolean = true

    override fun isErrorEnabled(): Boolean = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        recorder.record(level, MessageFormatter.basicArrayFormat(messagePattern, arguments))
    }
}
