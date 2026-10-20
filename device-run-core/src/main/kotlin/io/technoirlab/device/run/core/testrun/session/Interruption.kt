package io.technoirlab.device.run.core.testrun.session

/**
 * Throws [InterruptedException] and clears the interrupt flag when the thread is interrupted, with [cause] as the failure that
 * reported the interruption. A [cause] that is an [InterruptedException] itself is thrown as it is.
 *
 * Device Run requests do not throw [InterruptedException]: the JDK transport completes a request on an interrupted thread, and an
 * interrupted retry fails the request with the interrupt flag set. Checking the flag after a request ends the test run as an
 * interrupted sleep would.
 */
internal fun checkNotInterrupted(cause: Throwable? = null) {
    if (Thread.interrupted()) {
        throw cause as? InterruptedException ?: InterruptedException("The test run was interrupted").apply { cause?.let(::initCause) }
    }
}
