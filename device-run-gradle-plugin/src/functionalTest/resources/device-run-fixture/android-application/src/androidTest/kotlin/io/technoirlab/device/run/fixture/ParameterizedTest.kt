package io.technoirlab.device.run.fixture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

@RunWith(Parameterized::class)
class ParameterizedTest(
    private val number: Int,
    private val expectedSquare: Int,
) {
    @Test
    fun `squares number`() {
        val square = number * number

        assertEquals(expectedSquare, square)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{index}")
        fun parameters(): List<Array<Int>> = listOf(arrayOf(1, 1), arrayOf(2, 4))
    }
}
