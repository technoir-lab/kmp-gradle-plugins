package io.technoirlab.device.run.core.testrun.session

import java.util.concurrent.ConcurrentHashMap

/**
 * The sessions that this runner submitted and that may still run, including submissions whose creation is unconfirmed.
 *
 * Only these sessions are ever cancelled, so a runner never cancels a finished session or a session of another runner.
 */
internal class SessionRegistry {
    private val activeSessions = ConcurrentHashMap.newKeySet<String>()

    fun add(sessionName: String) {
        activeSessions.add(sessionName)
    }

    fun remove(sessionName: String) {
        activeSessions.remove(sessionName)
    }

    operator fun contains(sessionName: String): Boolean = sessionName in activeSessions

    /**
     * Returns the names of the sessions that may still run.
     */
    fun activeSessions(): Set<String> = activeSessions.toSet()
}
