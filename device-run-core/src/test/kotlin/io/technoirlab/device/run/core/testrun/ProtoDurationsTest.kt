package io.technoirlab.device.run.core.testrun

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

class ProtoDurationsTest {
    @ParameterizedTest
    @CsvSource(
        "PT5M, 300s",
        "PT0S, 0s",
        "PT1.5S, 1.500s",
        "PT1M0.12345S, 60.123450s",
        "PT0.000000001S, 0.000000001s",
        "PT3H, 10800s",
        "PT-1.5S, -1.500s",
    )
    fun `formats durations as protobuf strings without losing precision`(duration: Duration, expected: String) {
        val formatted = duration.toProtoDuration()

        assertThat(formatted).isEqualTo(expected)
    }
}
