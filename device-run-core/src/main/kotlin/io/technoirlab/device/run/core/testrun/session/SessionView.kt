package io.technoirlab.device.run.core.testrun.session

/**
 * The view of a session returned by Device Run. Requests always name one because the documented default is ambiguous.
 */
internal enum class SessionView(
    val apiValue: String,
) {
    /**
     * Configuration and progress of the session.
     */
    BASIC("SESSION_VIEW_BASIC"),

    /**
     * The complete session, including report details and output file references.
     */
    FULL("SESSION_VIEW_FULL"),
}
