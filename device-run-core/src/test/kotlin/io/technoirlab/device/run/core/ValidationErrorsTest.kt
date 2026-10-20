package io.technoirlab.device.run.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ValidationErrorsTest {
    private val errors = ValidationErrors()

    @Test
    fun `invalid options keep each error apart from the summary`() {
        errors.add("deviceRun.waitTimeout", "must be positive, but was 0s")
        errors.add("deviceRun.retries.maxAttempts", "must be between 1 and 5 total attempts, but was 7")

        assertThatThrownBy { errors.throwIfAny() }
            .isInstanceOfSatisfying(InvalidOptionsException::class.java) { exception ->
                assertThat(exception.summary).isEqualTo("Invalid Device Run configuration")
                assertThat(exception.errors).containsExactly(
                    "deviceRun.waitTimeout must be positive, but was 0s.",
                    "deviceRun.retries.maxAttempts must be between 1 and 5 total attempts, but was 7.",
                )
            }
            .hasMessage(
                """
                Invalid Device Run configuration:
                - deviceRun.waitTimeout must be positive, but was 0s.
                - deviceRun.retries.maxAttempts must be between 1 and 5 total attempts, but was 7.
                """.trimIndent(),
            )
    }
}
