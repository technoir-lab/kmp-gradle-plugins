package io.technoirlab.device.run.core.storage

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GcsUriTest {
    @Test
    fun `div appends a relative path to the directory`() {
        val directory = GcsUri("results", "automation/sessions")

        val result = directory / "session-1/mediumPhone/junit.xml"

        assertThat(result).isEqualTo(GcsUri("results", "automation/sessions/session-1/mediumPhone/junit.xml"))
    }

    @Test
    fun `div keeps a single separator after a directory name that ends with a slash`() {
        val directory = GcsUri("results", "automation/sessions/")

        val result = directory / "session-1"

        assertThat(result).isEqualTo(GcsUri("results", "automation/sessions/session-1"))
    }

    @Test
    fun `parse reads the bucket and object name that toString writes`() {
        val uri = GcsUri("inputs", "data/fixture file#1.bin")

        val result = GcsUri.parse(uri.toString())

        assertThat(result).isEqualTo(uri)
    }

    @ParameterizedTest
    @ValueSource(strings = ["inputs/data.bin", "https://inputs/data.bin", "gs://inputs", "gs://inputs/"])
    fun `parse rejects text without the gs scheme or an object name`(text: String) {
        assertThatThrownBy { GcsUri.parse(text) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("'$text' must have the form gs://<bucket>/<object>")
    }

    @ParameterizedTest
    @ValueSource(strings = ["gs:///data.bin", "gs://Inputs/data.bin", "gs://in/data.bin"])
    fun `parse rejects invalid bucket names`(text: String) {
        assertThatThrownBy { GcsUri.parse(text) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("'$text' must name a valid Cloud Storage bucket")
    }

    @Test
    fun `parse rejects directory placeholders`() {
        val text = "gs://inputs/data/"

        assertThatThrownBy { GcsUri.parse(text) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("'$text' must name an object, not a directory")
    }

    @Test
    fun `parseOrNull returns null for text that parse rejects`() {
        val text = "gs://inputs/data/"

        val result = GcsUri.parseOrNull(text)

        assertThat(result).isNull()
    }
}
