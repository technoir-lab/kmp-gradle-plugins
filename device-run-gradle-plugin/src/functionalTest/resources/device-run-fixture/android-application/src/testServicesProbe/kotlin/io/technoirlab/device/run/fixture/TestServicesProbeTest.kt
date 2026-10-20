package io.technoirlab.device.run.fixture

import androidx.test.services.storage.TestStorage
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Checks that the device has test services, which test storage needs.
 */
class TestServicesProbeTest {
    @Test
    fun `writes a test output file through test services`() {
        val versionName = installedVersionName(TEST_SERVICES_PACKAGE)

        TestStorage().openOutputFile("probe.txt").use { it.write("probe".toByteArray()) }

        assertNotNull("$TEST_SERVICES_PACKAGE is not installed", versionName)
    }
}
