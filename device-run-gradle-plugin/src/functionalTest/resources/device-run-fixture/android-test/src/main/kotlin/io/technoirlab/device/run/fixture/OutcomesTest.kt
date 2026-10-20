package io.technoirlab.device.run.fixture

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test

class OutcomesTest {
    @Test
    fun `passes after one second`() {
        Thread.sleep(1_000)

        assertEquals(4, 2 + 2)
    }

    @Test
    @Ignore("Exercises ignored test reporting")
    fun `is ignored`() {
        error("An ignored test must not execute")
    }

    @Test
    fun `is skipped by assumption`() {
        assumeTrue("Exercises assumption failure reporting", false)

        error("A test with a failed assumption must not continue")
    }
}
