package io.technoirlab.device.run.core.testrun.session

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException

class InterruptionTest {
    @Test
    fun `reports the failure of an interrupted request as an interruption and clears the interrupt flag`() {
        val requestFailure = IOException("Request failed")
        Thread.currentThread().interrupt()

        val failure = runCatching { checkNotInterrupted(requestFailure) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        assertThat(failure)
            .isInstanceOf(InterruptedException::class.java)
            .hasMessage("The test run was interrupted")
            .hasCause(requestFailure)
        assertThat(interrupted).isFalse()
    }

    @Test
    fun `throws an interruption that reported itself as it is`() {
        val interruption = InterruptedException("sleep interrupted")
        Thread.currentThread().interrupt()

        val failure = runCatching { checkNotInterrupted(interruption) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        assertThat(failure).isSameAs(interruption)
        assertThat(interrupted).isFalse()
    }

    @Test
    fun `passes on a thread that is not interrupted`() {
        val failure = runCatching { checkNotInterrupted(IOException("Request failed")) }.exceptionOrNull()

        assertThat(failure).isNull()
    }
}
