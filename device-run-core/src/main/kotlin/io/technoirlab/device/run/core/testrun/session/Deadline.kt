package io.technoirlab.device.run.core.testrun.session

import com.google.common.base.Stopwatch
import com.google.common.base.Ticker
import java.time.Duration

/**
 * A time limit of [timeout] that starts when it is created.
 *
 * @param ticker the monotonic time source, so that changes of the system clock do not move the deadline.
 */
internal class Deadline(
    private val timeout: Duration,
    ticker: Ticker,
) {
    private val stopwatch = Stopwatch.createStarted(ticker)

    /**
     * Returns the time left until the deadline, which is zero or negative once it has passed.
     */
    fun remaining(): Duration = timeout - stopwatch.elapsed()

    /**
     * Returns whether the deadline has passed.
     */
    fun hasPassed(): Boolean = stopwatch.elapsed() >= timeout
}
