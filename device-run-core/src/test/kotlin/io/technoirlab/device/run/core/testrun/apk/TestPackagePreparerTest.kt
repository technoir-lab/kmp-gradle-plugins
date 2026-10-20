package io.technoirlab.device.run.core.testrun.apk

import com.google.api.client.testing.http.MockLowLevelHttpResponse
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.errorResponse
import io.technoirlab.device.run.core.client.testStorageClient
import io.technoirlab.device.run.core.storage.FileHashCache
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.InputUploader
import io.technoirlab.device.run.core.storage.StorageManager
import io.technoirlab.device.run.core.storage.enqueueStorageUpload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeText

class TestPackagePreparerTest {
    private val transport = RecordingHttpTransport()
    private val storageManager = StorageManager(testStorageClient(transport))
    private val preparer = TestPackagePreparer(InputUploader(FileHashCache(), storageManager))

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `installs no target application for self-instrumenting tests`() {
        val testApk = apk("library-debug-androidTest.apk", "test")

        val packages = preparer.prepare("results", testApk, testedApks = emptyList())

        assertThat(packages).isEqualTo(
            TestPackages(testApk = uploadUri("test", "library-debug-androidTest.apk"), targetApp = emptyList()),
        )
    }

    @Test
    fun `installs the split APKs of the tested application as one package`() {
        val testApk = apk("app-debug-androidTest.apk", "test")
        val splitApks =
            listOf(apk("base.apk", "base"), apk("split_config.arm64_v8a.apk", "arm64"), apk("split_config.xxhdpi.apk", "xxhdpi"))

        val packages = preparer.prepare("results", testApk, splitApks)

        assertThat(packages.testApk).isEqualTo(uploadUri("test", "app-debug-androidTest.apk"))
        assertThat(packages.targetApp).containsExactly(
            uploadUri("base", "base.apk"),
            uploadUri("arm64", "split_config.arm64_v8a.apk"),
            uploadUri("xxhdpi", "split_config.xxhdpi.apk"),
        )
    }

    @Test
    fun `rejects packages that are not APK files before uploading`() {
        val testApk = (tempDir / "app-debug-androidTest.apk").apply { writeText("test") }
        val bundle = (tempDir / "app-debug.aab").apply { writeText("bundle") }

        assertThatThrownBy { preparer.prepare("results", testApk, listOf(bundle)) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage("The packages to test must be APK files, but include $bundle.")
        assertThat(transport.requests).isEmpty()
    }

    private fun apk(name: String, content: String): Path {
        transport.responses.add(errorResponse(404))
        transport.enqueueStorageUpload(MockLowLevelHttpResponse().setContent("""{"bucket": "results", "name": "$name"}"""))
        return (tempDir / name).apply { writeText(content) }
    }

    private fun uploadUri(content: String, name: String): GcsUri = GcsUri("results", "automation/inputs/${SHA256.getValue(content)}/$name")

    private companion object {
        private val SHA256 = mapOf(
            "test" to "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            "base" to "cae662172fd450bb0cd710a769079c05bfc5d8e35efa6576edc7d0377afdd4a2",
            "arm64" to "f69162950f235e3cdbbad33f1f912d1a504be90d8a37d002c735d6f3e3882265",
            "xxhdpi" to "941c28c2a46d7e2cff38bd689fcb30cdef503c393fd7b1a5c0e7e0d39d3680eb",
        )
    }
}
