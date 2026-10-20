package io.technoirlab.device.run.core.client

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpStatusCodes

/**
 * Returns the status code, the status when the error names one, and the most specific message of an API error, such as
 * `403 PERMISSION_DENIED: Permission denied`.
 */
internal fun GoogleJsonResponseException.reason(): String {
    val status = details?.get("status")?.let { "$it: " }.orEmpty()
    return "$statusCode $status${details?.message ?: statusMessage}"
}

/**
 * Executes this request, or returns null when the requested resource does not exist.
 *
 * Other failures, such as missing permissions, are thrown instead of being reported as a missing resource.
 */
internal fun <T> AbstractGoogleClientRequest<T>.executeOrNull(): T? = try {
    execute()
} catch (e: GoogleJsonResponseException) {
    if (e.statusCode == HttpStatusCodes.STATUS_CODE_NOT_FOUND) null else throw e
}
