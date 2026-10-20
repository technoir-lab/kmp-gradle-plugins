package io.technoirlab.device.run.core

import com.google.api.client.http.HttpTransport
import com.google.api.client.util.Sleeper
import com.google.auth.oauth2.GoogleCredentials
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Ticker
import io.technoirlab.device.run.core.client.CloudConnector
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.RequestRetryPolicy
import io.technoirlab.device.run.core.client.createDeviceRunClient
import io.technoirlab.device.run.core.client.createStorageClient
import io.technoirlab.device.run.core.client.executionProjectId
import io.technoirlab.device.run.core.device.CatalogDeviceResolver
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import io.technoirlab.device.run.core.device.DeviceCatalogManager
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.device.DeviceOptionsValidator
import io.technoirlab.device.run.core.device.DeviceSpec
import io.technoirlab.device.run.core.files.FileOptionsValidator
import io.technoirlab.device.run.core.files.PreparedFile
import io.technoirlab.device.run.core.files.PushedFileOptions
import io.technoirlab.device.run.core.files.PushedFilePreparer
import io.technoirlab.device.run.core.files.PushedFilesSpec
import io.technoirlab.device.run.core.storage.FileHashCache
import io.technoirlab.device.run.core.storage.InputUploader
import io.technoirlab.device.run.core.storage.StorageManager
import io.technoirlab.device.run.core.testrun.DeviceRunTestRunner
import io.technoirlab.device.run.core.testrun.RunOptionsValidator
import io.technoirlab.device.run.core.testrun.TestRunRequest
import io.technoirlab.device.run.core.testrun.TestRunSpec
import io.technoirlab.device.run.core.testrun.apk.TestPackagePreparer
import io.technoirlab.device.run.core.testrun.job.OrchestratorVersions
import io.technoirlab.device.run.core.testrun.results.SessionStorage
import io.technoirlab.device.run.core.testrun.session.SessionCanceller
import io.technoirlab.device.run.core.testrun.session.SessionLifecycle
import io.technoirlab.device.run.core.testrun.session.SessionManager
import io.technoirlab.device.run.core.testrun.session.SessionRegistry
import io.technoirlab.device.run.core.testrun.session.SessionSubmitter
import io.technoirlab.device.run.core.testrun.session.SessionWaiter
import io.technoirlab.device.run.core.testrun.session.checkNotInterrupted
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Runs tests on Device Run and stores their inputs and outputs in Cloud Storage.
 *
 * Each operation validates its options before it connects to Google Cloud. The first operation that needs Google Cloud creates the
 * connection: the transport, the credentials, the API clients, and the state that later operations share. Operations can run
 * concurrently, so the connection is created once under synchronization; when creating it fails, the next operation tries again.
 * Closing the runner closes the connection, if there is one, which cancels the sessions that may still run.
 *
 * @param clientName the name of the client that uses the runner, which identifies its requests.
 */
internal class DeviceRunnerImpl(
    private val cloudConnector: CloudConnector,
    private val clientName: String,
) : DeviceRunner {
    private val deviceValidator = DeviceOptionsValidator()
    private val fileValidator = FileOptionsValidator()
    private val runValidator = RunOptionsValidator()
    private val lazyConnection = lazy { connect() }
    private val connection by lazyConnection

    @get:VisibleForTesting
    internal val apiClientLogger = Logger.getLogger("com.google.api.client").apply { level = Level.WARNING }

    override fun resolveDevice(device: DeviceOptions): CatalogDeviceSnapshot {
        val spec = deviceValidator.validate(device)
        return connection.resolveDevice(spec)
    }

    override fun preparePushedFiles(bucket: String?, files: List<PushedFileOptions>): List<PreparedFile> {
        if (files.isEmpty()) {
            // Without files, no bucket is needed and Cloud Storage need not be accessed, so no credentials are loaded.
            return emptyList()
        }
        val spec = fileValidator.validatePushedFiles(bucket, files)
        return connection.preparePushedFiles(spec)
    }

    override fun runTests(request: TestRunRequest): Boolean {
        val spec = runValidator.validate(request)
        return try {
            connection.runTests(spec)
        } catch (e: Exception) {
            // Interrupted requests fail with the interrupt flag set instead of throwing InterruptedException.
            checkNotInterrupted(e)
            throw e
        }
    }

    override fun close() {
        if (lazyConnection.isInitialized()) {
            connection.close()
        }
    }

    /**
     * Creates a connection with a new transport, which is shut down again when the credentials or their project cannot be loaded.
     */
    private fun connect(): Connection {
        val transport = cloudConnector.createHttpTransport()
        try {
            // Token requests use the transport shared with the API clients.
            return Connection(transport, cloudConnector.createCredentials { transport }, clientName)
        } catch (e: Exception) {
            transport.shutdown()
            throw e
        }
    }

    /**
     * The Google Cloud state that operations share: the transport, the Device Run location of the project that the credentials
     * select, the API clients, the uploaded inputs, and the submitted sessions.
     */
    private class Connection(
        private val transport: HttpTransport,
        credentials: GoogleCredentials,
        clientName: String,
    ) : AutoCloseable {
        private val location = DeviceRunLocation(credentials.executionProjectId())

        // The API clients share one copy of the credentials, so they share its access token.
        private val quotaCredentials = credentials.createWithQuotaProject(location.projectId)
        private val deviceRun = createDeviceRunClient(
            transport = transport,
            credentials = quotaCredentials,
            location = location,
            retryPolicy = RequestRetryPolicy.STANDARD,
            clientName = clientName,
        )
        private val storageManager = StorageManager(
            createStorageClient(
                transport = transport,
                credentials = quotaCredentials,
                location = location,
                retryPolicy = RequestRetryPolicy.STANDARD,
                clientName = clientName,
            ),
        )
        private val inputUploader = InputUploader(FileHashCache(), storageManager)
        private val sessionManager = SessionManager(deviceRun, location)
        private val sessionRegistry = SessionRegistry()

        // Cancellation retries less, so that interrupting a test run or closing the runner is not delayed by an unreachable service.
        private val cleanupManager = SessionManager(
            createDeviceRunClient(
                transport = transport,
                credentials = quotaCredentials,
                location = location,
                retryPolicy = RequestRetryPolicy.CLEANUP,
                clientName = clientName,
            ),
            location,
        )

        private val sessionLifecycle = SessionLifecycle(
            sessionManager = sessionManager,
            registry = sessionRegistry,
            submitter = SessionSubmitter(sessionManager, location, sessionRegistry),
            waiter = SessionWaiter(sessionManager, Sleeper.DEFAULT, Ticker.systemTicker()),
            canceller = SessionCanceller(cleanupManager, Ticker.systemTicker(), Sleeper.DEFAULT),
            ticker = Ticker.systemTicker(),
        )

        private val deviceResolver = CatalogDeviceResolver(DeviceCatalogManager(deviceRun, location), location)
        private val pushedFilePreparer = PushedFilePreparer(inputUploader, storageManager)

        private val testRunner = DeviceRunTestRunner(
            orchestratorVersions = OrchestratorVersions(deviceRun, location),
            packagePreparer = TestPackagePreparer(inputUploader),
            sessionStorage = SessionStorage(storageManager),
            sessionLifecycle = sessionLifecycle,
        )

        fun resolveDevice(device: DeviceSpec): CatalogDeviceSnapshot = deviceResolver.resolve(device)

        fun preparePushedFiles(files: PushedFilesSpec): List<PreparedFile> = pushedFilePreparer.prepare(files.bucket, files.files)

        fun runTests(testRun: TestRunSpec): Boolean = testRunner.run(testRun)

        override fun close() {
            try {
                sessionLifecycle.cancelActiveSessions()
            } finally {
                transport.shutdown()
            }
        }
    }
}
