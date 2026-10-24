package io.technoirlab.device.run.core

import com.google.api.client.util.Sleeper
import com.google.common.base.Ticker
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A clock whose time passes only when [sleeper] sleeps, so that waits end at once.
 */
internal class VirtualClock {
    private val elapsedNanos = AtomicLong()
    private val interruptsNextSleep = AtomicBoolean()

    /**
     * The delays of the sleeps that passed, in the order they ended.
     */
    val sleeps = CopyOnWriteArrayList<Duration>()

    /**
     * Reads the time that the sleeps have passed.
     */
    val ticker = object : Ticker() {
        override fun read(): Long = elapsedNanos.get()
    }

    /**
     * Passes the time of each sleep at once, unless [interruptNextSleep] makes it throw [InterruptedException].
     */
    val sleeper = Sleeper { millis ->
        if (interruptsNextSleep.getAndSet(false)) {
            throw InterruptedException()
        }
        val delay = Duration.ofMillis(millis)
        elapsedNanos.addAndGet(delay.toNanos())
        sleeps += delay
    }

    /**
     * Makes the next sleep throw [InterruptedException], as when the sleeping thread is interrupted.
     */
    fun interruptNextSleep() {
        interruptsNextSleep.set(true)
    }
}
