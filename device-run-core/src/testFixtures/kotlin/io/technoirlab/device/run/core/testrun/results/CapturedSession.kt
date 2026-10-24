package io.technoirlab.device.run.core.testrun.results

import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.devicerun.v1alpha.model.SessionReport
import io.technoirlab.device.run.core.client.FakeCloudTransport
import io.technoirlab.device.run.core.client.deviceRunFixtures
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
import io.technoirlab.device.run.core.testDeviceRunner
import io.technoirlab.device.run.core.testrun.TestRunRequest

/**
 * A finished Device Run session of the fixtures, `device-run/sessions/<scenario>`, which test runs replay. The runner submits the
 * session of a test run to a fake Device Run service that finishes it with the report and outputs of the captured session, and
 * writes the results as for any session.
 *
 * The device of a test run needs the name of the captured job, such as `mediumPhone` for `test-failure`, because the outputs of a
 * job are stored in the directory of its name. The fixtures leave out device logs, so the files that the report references but
 * the fixtures lack are replayed as placeholders.
 *
 * @param scenario the directory of the session below `device-run/sessions`, such as `test-failure`.
 * @param jobReport the job report of the session, `<job>/junit.xml`, or null for the captured one.
 */
class CapturedSession(
    private val scenario: String,
    private val jobReport: String? = null,
) {
    private val json = GsonFactory.getDefaultInstance()

    /**
     * Runs the tests of [request] in a replay of the session, writes their results, and returns whether they passed.
     */
    fun runTests(request: TestRunRequest): Boolean {
        val captured = parseDeviceRunFixture<Session>("sessions/$scenario/session-full.json")
        val capturedJob = captured.sessionConfig.jobConfigs.single().displayName
        val cloud = FakeCloudTransport().apply {
            sessionOutputs = outputs(captured, capturedJob)
            sessionReport = { sessionDirectory -> captured.reportFor(sessionDirectory) }
        }

        val passed = testDeviceRunner(cloud).use { it.runTests(request) }

        val job = cloud.sessions.single().sessionConfig.jobConfigs.single().displayName
        check(job == capturedJob) { "The test run ran the job $job, but the captured session $scenario ran the job $capturedJob" }
        return passed
    }

    private fun outputs(captured: Session, job: String): Map<String, String> {
        val placeholders = captured.referencedOutputs().associateWith { "Placeholder for the Device Run output $it" }
        val outputs = placeholders + deviceRunFixtures("sessions/$scenario/outputs")
        return if (jobReport == null) outputs else outputs + ("$job/$JOB_REPORT" to jobReport)
    }

    /**
     * Returns the paths below the session directory of the outputs that the report of the captured session references.
     */
    private fun Session.referencedOutputs(): List<String> {
        val prefix = "${capturedDirectory()}/"
        return referencedOutputFiles().map { it.toString() }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    /**
     * Returns the report of the captured session with the outputs that it references moved to [sessionDirectory].
     */
    private fun Session.reportFor(sessionDirectory: String): SessionReport {
        val report = json.toString(sessionReport).replace("${capturedDirectory()}/", "$sessionDirectory/")
        return json.fromString(report, SessionReport::class.java)
    }

    /**
     * Returns the `gs://` directory of the outputs of the captured session.
     */
    private fun Session.capturedDirectory(): String =
        "${sessionConfig.outputDirectoryConfig.gcsOutputDirectory.path}/${name.substringAfterLast('/')}"

    private companion object {
        private const val JOB_REPORT = "junit.xml"
    }
}
