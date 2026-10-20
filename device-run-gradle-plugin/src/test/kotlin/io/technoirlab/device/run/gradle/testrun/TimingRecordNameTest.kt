package io.technoirlab.device.run.gradle.testrun

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TimingRecordNameTest {
    @Test
    fun `derives stable record names that keep identities apart when they sanitize alike`() {
        val name = defaultTimingRecordName("device-run-fixture", ":feature:login", "debug", "mediumphone-arm-35")
        val repeatedName = defaultTimingRecordName("device-run-fixture", ":feature:login", "debug", "mediumphone-arm-35")
        val similarName = defaultTimingRecordName("device-run-fixture", ":feature-login", "debug", "mediumphone-arm-35")

        assertThat(name).isEqualTo(repeatedName).matches("device-run-fixture-feature-login-debug-mediumphone-arm-35-[0-9a-f]{12}")
        assertThat(similarName).startsWith("device-run-fixture-feature-login-debug-mediumphone-arm-35-").isNotEqualTo(name)
    }

    @Test
    fun `starts record names with a letter or digit when the build name starts with a symbol`() {
        val name = defaultTimingRecordName("_fixture", ":app", "debug", "mediumphone-arm-35")

        assertThat(name).matches("fixture-app-debug-mediumphone-arm-35-[0-9a-f]{12}")
    }
}
