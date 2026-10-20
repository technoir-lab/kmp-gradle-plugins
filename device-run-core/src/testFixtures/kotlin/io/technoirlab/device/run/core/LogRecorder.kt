package io.technoirlab.device.run.core

import org.slf4j.ILoggerFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records the messages that tests log through SLF4J, in the order that all loggers log them.
 */
internal class LogRecorder : ILoggerFactory {
    private val loggers = ConcurrentHashMap<String, Logger>()
    private val recordedEntries = CopyOnWriteArrayList<Pair<Level, String>>()

    val entries: List<Pair<Level, String>>
        get() = recordedEntries.toList()

    override fun getLogger(name: String): Logger = loggers.computeIfAbsent(name) { RecordingLogger(it, this) }

    fun record(level: Level, message: String) {
        recordedEntries.add(level to message)
    }

    fun clear() {
        recordedEntries.clear()
    }
}

/**
 * Clears the messages recorded so far and returns the [LogRecorder] of the tests.
 *
 * JUnit creates a test instance for each test, so a property initialized with this function starts every test without entries.
 */
internal fun recordLogs(): LogRecorder = (LoggerFactory.getILoggerFactory() as LogRecorder).apply { clear() }
