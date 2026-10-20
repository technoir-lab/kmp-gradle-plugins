package io.technoirlab.device.run.core.testrun.session

/**
 * How waiting for a session ended.
 */
internal sealed interface WaitResult {
    /**
     * The session finished; its result decides whether the tests passed.
     */
    data object Finished : WaitResult

    /**
     * The wait deadline passed before the session finished.
     */
    data object TimedOut : WaitResult

    /**
     * The creation operation failed, so the session did not run to completion.
     */
    data class OperationFailed(
        val code: Int?,
        val message: String?,
    ) : WaitResult
}
