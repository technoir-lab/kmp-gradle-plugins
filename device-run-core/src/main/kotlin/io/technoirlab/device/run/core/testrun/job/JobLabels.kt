package io.technoirlab.device.run.core.testrun.job

/**
 * The label contract of Device Run jobs, which carry user labels and the labels that identify their test run.
 */
internal class JobLabels {
    /**
     * Returns the errors of the labels that identify a test run.
     */
    fun testRunLabelErrors(labels: Map<String, String>): List<String> = buildList {
        if (labels.size > MAX_LABELS) {
            add("has ${labels.size} labels, more than the limit of $MAX_LABELS")
        }
        addAll(entryErrors(labels, reservedKeys = emptySet()))
    }

    /**
     * Returns the errors of user-defined labels, which must leave room for [testRunLabels] and must not use their keys.
     */
    fun userLabelErrors(labels: Map<String, String>, testRunLabels: Map<String, String>): List<String> = buildList {
        val maxUserLabels = (MAX_LABELS - testRunLabels.size).coerceAtLeast(0)
        if (labels.size > maxUserLabels) {
            add("has ${labels.size} labels, more than the limit of $maxUserLabels besides the labels of the test run")
        }
        addAll(entryErrors(labels, testRunLabels.keys))
    }

    private fun entryErrors(labels: Map<String, String>, reservedKeys: Set<String>): List<String> = buildList {
        for ((key, value) in labels) {
            when {
                key.isBlank() -> add("has a blank key")
                key in reservedKeys -> add("key '$key' is reserved for the labels of the test run")
                key.utf8Size() > MAX_KEY_BYTES -> add("key '$key' is longer than $MAX_KEY_BYTES bytes")
            }
            if (value.utf8Size() > MAX_VALUE_BYTES) {
                add("value of '$key' is longer than $MAX_VALUE_BYTES bytes")
            }
        }
    }

    private companion object {
        private const val MAX_LABELS = 16
        private const val MAX_KEY_BYTES = 32
        private const val MAX_VALUE_BYTES = 1024
    }
}
