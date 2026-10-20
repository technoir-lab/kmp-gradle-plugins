package io.technoirlab.device.run.core.testrun.job

import com.google.api.services.devicerun.v1alpha.DeviceRun
import com.google.api.services.devicerun.v1alpha.model.CatalogSoftwareVersion
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.collectPages
import io.technoirlab.device.run.core.testrun.RunOptions

/**
 * Checks Android Test Orchestrator versions against the software versions of the Device Run catalog in [location].
 */
internal class OrchestratorVersions(
    private val deviceRun: DeviceRun,
    private val location: DeviceRunLocation,
) {
    // Concurrent checks list the versions on their own instead of waiting on a lock, which an interrupt cannot end. The first
    // listing that succeeds is kept; a failed listing is not, so the next check lists the versions again.
    private val catalogVersions by lazy(LazyThreadSafetyMode.PUBLICATION) { listCatalogVersions() }

    /**
     * Checks that Device Run provides orchestrator [version]. [RunOptions.Job.AUTO_ORCHESTRATOR_VERSION] selects the catalog
     * default and needs no check.
     *
     * @throws DeviceRunException when the catalog does not list the version.
     */
    fun requireAvailable(version: String) {
        if (version == RunOptions.Job.AUTO_ORCHESTRATOR_VERSION) {
            return
        }
        val versions = catalogVersions
        if (versions.none { it.version == version }) {
            val available = versions.map { if (it.isDefault == true) "${it.version} (default)" else it.version }
            throw DeviceRunException(
                "androidx.test:orchestrator:$version from androidTestUtil is not available in Device Run. " +
                    "Available versions: ${available.joinToString()}.",
            )
        }
    }

    /**
     * Returns the orchestrator versions that the catalog lists, following every result page.
     */
    private fun listCatalogVersions(): List<CatalogSoftwareVersion> {
        val request = deviceRun.projects().locations().softwareVersions().list(location.name).setFilter(ORCHESTRATOR_FILTER)
        return collectPages(
            requestPage = { pageToken -> request.setPageToken(pageToken).execute() },
            items = { it.softwareVersions },
            nextPageToken = { it.nextPageToken },
        )
    }

    private companion object {
        private const val ORCHESTRATOR_FILTER = "software_type = \"ANDROIDX_TEST_ORCHESTRATOR\""
    }
}
