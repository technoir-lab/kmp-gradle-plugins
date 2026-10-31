package io.technoirlab.device.run.core.files

import io.technoirlab.device.run.core.storage.GcsUri
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeText

class PushManifestSerializerTest {
    private val serializer = PushManifestSerializer()

    @Test
    fun `reads the manifests it writes`(@TempDir tempDir: Path) {
        val file = tempDir / "push-manifest.json"
        val files = listOf(
            PreparedFile("config", GcsUri("results", "automation/inputs/0000/config.json"), "sha256:0000", "/sdcard/config.json"),
            PreparedFile("fixture", GcsUri("fixtures", "data/fixture.bin"), "md5:XUFAKrxLKna5cZ2REBfFkg==", "/sdcard/fixture.bin"),
        )
        serializer.write(files, file)

        val result = serializer.read(file)

        assertThat(result).isEqualTo(files)
    }

    @Test
    fun `rejects a manifest that lists a file outside Cloud Storage`(@TempDir tempDir: Path) {
        val file = (tempDir / "push-manifest.json").apply {
            writeText(
                """
                {"files": [{"name": "config", "uri": "config.json", "checksum": "sha256:0", "destinationPath": "/sdcard/config.json"}]}
                """.trimIndent(),
            )
        }

        assertThatThrownBy { serializer.read(file) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'config.json' must have the form gs://<bucket>/<object>")
    }
}
