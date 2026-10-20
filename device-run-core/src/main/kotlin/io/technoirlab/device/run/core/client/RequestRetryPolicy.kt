package io.technoirlab.device.run.core.client

import com.google.api.client.util.ExponentialBackOff
import java.time.Duration

/**
 * Bounds the retries of one HTTP request after transient failures.
 *
 * @property maxRetries the number of retries after the first attempt.
 * @property maxElapsed the time after which no further retry starts.
 */
internal data class RequestRetryPolicy(
    val maxRetries: Int,
    val initialInterval: Duration,
    val maxInterval: Duration,
    val maxElapsed: Duration,
) {
    /**
     * Creates the backoff state of one request.
     */
    fun newBackOff(): ExponentialBackOff = ExponentialBackOff.Builder()
        .setInitialIntervalMillis(initialInterval.toMillis().toInt())
        .setMaxIntervalMillis(maxInterval.toMillis().toInt())
        .setMaxElapsedTimeMillis(maxElapsed.toMillis().toInt())
        .build()

    internal companion object {
        /**
         * Retries of requests that a test run waits for.
         */
        val STANDARD = RequestRetryPolicy(
            maxRetries = 5,
            initialInterval = Duration.ofSeconds(1),
            maxInterval = Duration.ofSeconds(30),
            maxElapsed = Duration.ofMinutes(2),
        )

        /**
         * Retries of cleanup requests, such as cancellation, which must finish quickly.
         */
        val CLEANUP = RequestRetryPolicy(
            maxRetries = 2,
            initialInterval = Duration.ofSeconds(1),
            maxInterval = Duration.ofSeconds(5),
            maxElapsed = Duration.ofSeconds(15),
        )
    }
}
