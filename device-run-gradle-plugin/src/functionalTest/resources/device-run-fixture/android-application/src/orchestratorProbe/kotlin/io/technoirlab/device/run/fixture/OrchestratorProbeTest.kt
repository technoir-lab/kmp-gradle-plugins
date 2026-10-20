package io.technoirlab.device.run.fixture

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Checks that Android Test Orchestrator runs each test in its own instrumentation, in the version that the build expects, and
 * clears the data of the tested application between tests through test services.
 */
class OrchestratorProbeTest {
    @Test
    fun `first test runs alone with cleared data`() {
        checkIsolated()
    }

    @Test
    fun `second test runs alone with cleared data`() {
        checkIsolated()
    }

    @Test
    fun `orchestrator has the version of androidTestUtil`() {
        val expectedVersion = InstrumentationRegistry.getArguments().getString("expectedOrchestratorVersion")

        val versionName = installedVersionName(ORCHESTRATOR_PACKAGE)

        assertEquals(expectedVersion, versionName)
    }

    private fun checkIsolated() {
        testsInInstrumentation++
        val marker = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "probe-marker")

        assertEquals("tests in this instrumentation", 1, testsInInstrumentation)
        assertFalse("data of the tested application was not cleared", marker.exists())
        marker.writeText("probe")
    }

    private companion object {
        private var testsInInstrumentation = 0
    }
}
