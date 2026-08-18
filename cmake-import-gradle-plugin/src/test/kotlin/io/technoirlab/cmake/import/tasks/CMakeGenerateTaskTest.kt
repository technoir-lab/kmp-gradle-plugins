package io.technoirlab.cmake.import.tasks

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CMakeGenerateTaskTest {
    @Test
    fun `defaults to Ninja on Windows`() {
        assertThat(CMakeGenerateTask.defaultGenerator("Windows 11")).isEqualTo("Ninja")
        assertThat(CMakeGenerateTask.defaultGenerator("windows 10")).isEqualTo("Ninja")
    }

    @Test
    fun `uses the platform default generator outside Windows`() {
        assertThat(CMakeGenerateTask.defaultGenerator("Mac OS X")).isNull()
        assertThat(CMakeGenerateTask.defaultGenerator("Linux")).isNull()
    }
}
