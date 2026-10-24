package io.technoirlab.device.run.core.testrun.results

import io.technoirlab.device.run.core.storage.GcsUri

/**
 * Outputs of a Device Run session in the results bucket.
 *
 * @property directory the directory of the session, `gs://<bucket>/automation/sessions/<session id>`.
 * @property paths the paths of the outputs below [directory], in the order of their names.
 */
internal data class SessionOutputs(
    val directory: GcsUri,
    val paths: List<String>,
) {
    /**
     * The device logs of the executions, `<job>/<execution>/logcat.txt`.
     */
    val deviceLogs: SessionOutputs
        get() = copy(paths = paths.filter { DEVICE_LOG.matches(it) })

    /**
     * The outputs other than the device logs of the executions.
     */
    val withoutDeviceLogs: SessionOutputs
        get() = copy(paths = paths.filterNot { DEVICE_LOG.matches(it) })

    private companion object {
        // Files pulled from the device lie deeper, below the artifacts directory of the execution.
        private val DEVICE_LOG = Regex("[^/]+/[^/]+/${Regex.escape(JobExecution.LOGCAT)}")
    }
}
