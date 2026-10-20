package io.technoirlab.device.run.core.testrun.session

/**
 * The outcome of cancelling a session.
 *
 * @property settled whether the session is known not to run anymore.
 */
internal enum class CancellationResult(
    val description: String,
    val settled: Boolean,
) {
    NOT_FOUND("Device Run has no such session", settled = true),
    ALREADY_FINISHED("the session had already finished", settled = true),
    CANCELLED("Device Run cancelled the session", settled = true),
    UNCONFIRMED("cancellation was requested, but Device Run has not confirmed it", settled = false),
    FAILED("the session could not be cancelled", settled = false),
}
