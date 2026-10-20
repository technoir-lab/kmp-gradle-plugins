package io.technoirlab.device.run.core.client

import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.ComputeEngineCredentials
import com.google.auth.oauth2.GoogleAuthUtils
import com.google.auth.oauth2.GoogleCredentials
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

/**
 * Loads Application Default Credentials (ADC) that request access tokens through a given transport.
 *
 * [GoogleCredentials.getApplicationDefault] keeps the first credentials it loads, together with their transport, for the lifetime of
 * the class loader, which a long-lived process reuses across runners. This loader reads ADC files on every call instead, so each
 * runner uses its own `GOOGLE_APPLICATION_CREDENTIALS` selection and transport.
 */
internal class CredentialsLoader(
    private val environment: (String) -> String?,
) {
    fun load(transportFactory: HttpTransportFactory): GoogleCredentials {
        val credentials = credentialsFile()?.let { loadFile(it, transportFactory) } ?: loadRuntimeCredentials(transportFactory)
        val quotaProject = environment(QUOTA_PROJECT_ENV_VAR)?.takeIf { it.isNotBlank() }
        return if (quotaProject == null) credentials else credentials.createWithQuotaProject(quotaProject)
    }

    private fun credentialsFile(): Path? {
        val environmentPath = environment(CREDENTIALS_ENV_VAR)
        if (environmentPath.isNullOrEmpty()) {
            return Path(GoogleAuthUtils.getWellKnownCredentialsPath()).takeIf { it.isRegularFile() }
        }
        val file = Path(environmentPath)
        if (!file.isRegularFile()) {
            throw IOException("$CREDENTIALS_ENV_VAR is set to '$environmentPath', which is not a file.")
        }
        return file
    }

    private fun loadFile(file: Path, transportFactory: HttpTransportFactory): GoogleCredentials = try {
        file.inputStream().use { GoogleCredentials.fromStream(it, transportFactory) }
    } catch (e: IOException) {
        throw IOException("Unable to load Application Default Credentials from '$file': ${e.message}", e)
    }

    private fun loadRuntimeCredentials(transportFactory: HttpTransportFactory): GoogleCredentials {
        // The library caches runtime credentials, so they are discovered with its own transport and then bound to this one.
        val credentials = GoogleCredentials.getApplicationDefault()
        return if (credentials is ComputeEngineCredentials) {
            credentials.toBuilder().setHttpTransportFactory(transportFactory).build()
        } else {
            credentials
        }
    }

    private companion object {
        private const val CREDENTIALS_ENV_VAR = "GOOGLE_APPLICATION_CREDENTIALS"
        private const val QUOTA_PROJECT_ENV_VAR = "GOOGLE_CLOUD_QUOTA_PROJECT"
    }
}
