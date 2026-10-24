package io.technoirlab.device.run.core.testrun.session

import com.google.api.services.devicerun.v1alpha.model.Session

/**
 * The status types of a session, as `statusType` names them in its report.
 */
internal enum class StatusType {
    PENDING,
    RUNNING,
    SUSPENDED,
    DONE,
    ;

    companion object {
        /**
         * Returns the status type named [name], or null when it is missing or one that the runner does not know.
         */
        fun of(name: String?): StatusType? = entries.find { it.name == name }
    }
}

/**
 * Returns the status type of the session, or null when its report has none or one that the runner does not know.
 */
internal fun Session.statusType(): StatusType? = StatusType.of(sessionReport?.status?.statusType)
