package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpResponseException
import com.google.auth.Retryable
import java.io.IOException

/**
 * Returns whether a later attempt of the failed request can succeed.
 *
 * A failure with a response can succeed later only with a [transient status][isTransientStatusCode], including a token response that
 * the credentials wrap in their own failure. A failure without a response can succeed later unless the credentials report it as not
 * retryable.
 */
internal fun IOException.isTransient(): Boolean {
    val response = generateSequence<Throwable>(this) { it.cause }.filterIsInstance<HttpResponseException>().firstOrNull()
    return when {
        response != null -> isTransientStatusCode(response.statusCode)
        this is Retryable -> isRetryable
        else -> true
    }
}

/**
 * Returns whether a response with [statusCode] reports a temporary condition, such as an overloaded or unavailable service.
 */
internal fun isTransientStatusCode(statusCode: Int): Boolean = statusCode in TRANSIENT_STATUS_CODES

private val TRANSIENT_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
