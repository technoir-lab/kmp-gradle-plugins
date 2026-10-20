package io.technoirlab.device.run.core.testrun.session

import java.time.Duration

/**
 * Returns the delay before the next poll of a session: the poll interval, shortened to the time left until [deadline] when there
 * is one.
 */
internal fun pollDelay(deadline: Deadline?): Duration {
    val remaining = deadline?.remaining() ?: POLL_INTERVAL
    return remaining.coerceIn(MIN_POLL_DELAY, POLL_INTERVAL)
}

private val MIN_POLL_DELAY = Duration.ofMillis(1)
private val POLL_INTERVAL = Duration.ofSeconds(10)
