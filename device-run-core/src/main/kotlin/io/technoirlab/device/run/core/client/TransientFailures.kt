package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpResponseException
import com.google.auth.Retryable
import java.io.IOException

/**
 * Returns whether a later attempt of the failed request can succeed: the request failed without a response, with a
 * [transient status][isTransientStatusCode], or while obtaining an access token for a reason that the credentials report as
 * retryable.
 */
internal fun IOException.isTransient(): Boolean = when (this) {
    is HttpResponseException -> isTransientStatusCode(statusCode)
    is Retryable -> isRetryable
    else -> true
}

/**
 * Returns whether a response with [statusCode] reports a temporary condition, such as an overloaded or unavailable service.
 */
internal fun isTransientStatusCode(statusCode: Int): Boolean = statusCode in TRANSIENT_STATUS_CODES

private val TRANSIENT_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
