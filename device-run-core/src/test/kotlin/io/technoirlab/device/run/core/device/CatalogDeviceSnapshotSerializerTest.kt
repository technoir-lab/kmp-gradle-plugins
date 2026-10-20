package io.technoirlab.device.run.core.device

import io.technoirlab.device.run.core.DeviceRunException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeText

class CatalogDeviceSnapshotSerializerTest {
    private val serializer = CatalogDeviceSnapshotSerializer()
    private val snapshot = CatalogDeviceSnapshot(
        deviceId = "tokay-35",
        displayName = "Pixel 9",
        manufacturer = "Google",
        physical = true,
        apiLevel = 35,
        abis = listOf("arm64-v8a"),
        screenDensity = null,
    )

    @Test
    fun `reads the snapshots it writes`(@TempDir tempDir: Path) {
        val file = tempDir / "pixel.json"
        serializer.write(snapshot, file)

        val result = serializer.read(file)

        assertThat(result).isEqualTo(snapshot)
    }

    @Test
    fun `asks to run setup again for snapshots in another format`(@TempDir tempDir: Path) {
        val file = (tempDir / "pixel.json").apply { writeText("""{"schemaVersion": 2, "device": {"id": "tokay-35"}}""") }

        assertThatThrownBy { serializer.read(file) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                "The device snapshot $file has format version 2, but this plugin reads version 1. " +
                    "Run the setup task of the device again.",
            )
    }

    @Test
    fun `names the snapshot file after the device`() {
        val fileName = serializer.fileName("mediumPhone")

        assertThat(fileName).isEqualTo("mediumPhone.json")
    }
}
