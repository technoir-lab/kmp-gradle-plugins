package io.technoirlab.device.run.core.files

import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.ValidationErrors
import io.technoirlab.device.run.core.storage.GcsUri
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

class FileOptionsValidatorTest {
    private val validator = FileOptionsValidator()

    @Test
    fun `maps pushed files to their sources`() {
        val files = listOf(
            PushedFileOptions("config", localFile = Path("config.json"), destinationPath = "/sdcard/config.json"),
            PushedFileOptions("fixture", gcsUri = "gs://inputs/data/fixture.bin", destinationPath = "/sdcard/fixture.bin"),
        )

        val spec = validator.validatePushedFiles("results-bucket", files)

        assertThat(spec).isEqualTo(
            PushedFilesSpec(
                bucket = "results-bucket",
                files = listOf(
                    PushedFileSpec("config", PushSource.LocalFile(Path("config.json")), "/sdcard/config.json"),
                    PushedFileSpec("fixture", PushSource.CloudFile(GcsUri("inputs", "data/fixture.bin")), "/sdcard/fixture.bin"),
                ),
            ),
        )
    }

    @Test
    fun `pushed files need a bucket, exactly one source, and distinct absolute destinations`() {
        val files = listOf(
            PushedFileOptions("both", "gs://inputs/input.txt", Path("input.txt"), "/sdcard/both.txt"),
            PushedFileOptions("neither", destinationPath = "/sdcard/neither.txt"),
            PushedFileOptions("directory", gcsUri = "gs://inputs/data/", destinationPath = "/sdcard/data"),
            PushedFileOptions("relative", localFile = Path("input.txt"), destinationPath = "sdcard/input.txt"),
            PushedFileOptions("unplaced", gcsUri = "gs://inputs/unplaced.txt"),
            PushedFileOptions("first", localFile = Path("first.txt"), destinationPath = "/sdcard/same.txt"),
            PushedFileOptions("second", localFile = Path("second.txt"), destinationPath = "/sdcard/same.txt"),
        )

        assertThatThrownBy { validator.validatePushedFiles(bucket = null, files) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                """
                Invalid Device Run configuration:
                - deviceRun.storage.bucket is required; set it to the name of an existing Cloud Storage bucket.
                - deviceRun.files.push entries 'first', 'second' share the destination path /sdcard/same.txt.
                - deviceRun.files.push["both"] sets both gcsUri and localFile; set exactly one source.
                - deviceRun.files.push["neither"] sets neither gcsUri nor localFile; set exactly one source.
                - deviceRun.files.push["directory"].gcsUri 'gs://inputs/data/' must name an object, not a directory.
                - deviceRun.files.push["relative"].destinationPath 'sdcard/input.txt' must be an absolute device path.
                - deviceRun.files.push["unplaced"].destinationPath is required.
                """.trimIndent(),
            )
    }

    @Test
    fun `pulled paths must be distinct absolute device paths`() {
        val paths = listOf("/sdcard/../data", "sdcard/output", "/sdcard/log", "/sdcard/log")
        val errors = ValidationErrors()

        validator.validatePathsToPull(paths, errors)

        assertThatThrownBy { errors.throwIfAny() }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                """
                Invalid Device Run configuration:
                - deviceRun.files.pathsToPull lists /sdcard/log more than once.
                - deviceRun.files.pathsToPull '/sdcard/../data' must not contain '.' or '..' segments.
                - deviceRun.files.pathsToPull 'sdcard/output' must be an absolute device path.
                """.trimIndent(),
            )
    }
}
