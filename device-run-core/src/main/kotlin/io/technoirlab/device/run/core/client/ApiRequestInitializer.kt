package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpBackOffIOExceptionHandler
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.HttpUnsuccessfulResponseHandler
import com.google.api.client.util.Sleeper
import com.google.auth.http.HttpCredentialsAdapter

/**
 * Authenticates the Device Run and Cloud Storage requests of the runner, bills them to [quotaProject], and retries transient
 * failures with bounded exponential backoff.
 *
 * Retries cover HTTP 408, 429, 500, 502, 503, and 504 responses and I/O failures; invalid requests and permission failures fail
 * immediately. Refreshing an access token after a 401 response stays with [credentials], whose response handler this initializer
 * chains instead of replacing. An interrupted backoff stops retrying and keeps the thread's interrupt flag.
 */
internal class ApiRequestInitializer(
    private val credentials: HttpCredentialsAdapter,
    private val quotaProject: String,
    private val retryPolicy: RequestRetryPolicy,
    private val sleeper: Sleeper,
) : HttpRequestInitializer {
    override fun initialize(request: HttpRequest) {
        credentials.initialize(request)
        // Credentials with a preloaded token can omit the quota project from their cached metadata.
        request.headers[QUOTA_PROJECT_HEADER] = quotaProject
        request.connectTimeout = CONNECT_TIMEOUT_MILLIS
        request.readTimeout = READ_TIMEOUT_MILLIS
        request.numberOfRetries = retryPolicy.maxRetries
        request.unsuccessfulResponseHandler = RetryingResponseHandler(
            refreshHandler = credentials,
            backOffHandler = HttpBackOffUnsuccessfulResponseHandler(retryPolicy.newBackOff())
                .setBackOffRequired { response -> isTransientStatusCode(response.statusCode) }
                .setSleeper(sleeper),
        )
        request.ioExceptionHandler = HttpBackOffIOExceptionHandler(retryPolicy.newBackOff()).setSleeper(sleeper)
    }

    /**
     * Refreshes rejected tokens first, then retries transient failures.
     */
    private class RetryingResponseHandler(
        private val refreshHandler: HttpUnsuccessfulResponseHandler,
        private val backOffHandler: HttpUnsuccessfulResponseHandler,
    ) : HttpUnsuccessfulResponseHandler {
        override fun handleResponse(request: HttpRequest, response: HttpResponse, supportsRetry: Boolean): Boolean {
            if (refreshHandler.handleResponse(request, response, supportsRetry)) {
                // The credentials adapter reinitializes the request after a refresh, which replaces this handler.
                request.unsuccessfulResponseHandler = this
                return true
            }
            return backOffHandler.handleResponse(request, response, supportsRetry)
        }
    }

    private companion object {
        private const val QUOTA_PROJECT_HEADER = "X-Goog-User-Project"
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 60_000
    }
}
