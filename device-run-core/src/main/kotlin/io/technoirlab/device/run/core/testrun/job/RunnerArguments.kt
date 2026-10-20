package io.technoirlab.device.run.core.testrun.job

import io.technoirlab.device.run.core.InvalidOptionsException
import io.technoirlab.device.run.core.ValidationErrors

/**
 * The Device Run contract for instrumentation runner arguments: test targets and additional test options.
 */
internal class RunnerArguments {
    /**
     * Splits the instrumentation runner arguments of a test run into the test targets and options of Device Run.
     *
     * Runner arguments with a selector key become test targets, one per comma-separated value, and the others become options.
     *
     * @throws InvalidOptionsException when Device Run cannot honor a runner argument or the arguments exceed its limits.
     */
    fun split(runnerArguments: Map<String, String>, managedSharding: Boolean): Split {
        val errors = ValidationErrors(SUMMARY)
        val testTargets = mutableListOf<String>()
        val options = mutableMapOf<String, String>()
        for ((key, value) in runnerArguments.toSortedMap()) {
            val values = value.split(',').map { it.trim() }
            when {
                isTestFileKey(key) -> errors.add("$key=$value filters by test file, which Device Run does not support.")
                managedSharding && isShardingKey(key) -> errors.add("$key=$value conflicts with sharding managed by Device Run.")
                !isSelector(key) -> options[key] = value
                values.any(::isMalformedValue) -> errors.add("$key=$value must be a comma-separated list of values.")
                key == SIZE && values.any { it !in SIZES } -> errors.add("$key=$value must select size ${SIZES.joinToString()}.")
                else -> values.mapTo(testTargets) { "$key $it" }
            }
        }
        val split = Split(testTargets, options)
        errors.addLimitErrors(split)
        errors.throwIfAny()
        return split
    }

    /**
     * Returns whether [key] is a runner argument that Device Run accepts only as a test target.
     */
    private fun isSelector(key: String): Boolean = key in SELECTORS

    /**
     * Returns whether [key] filters tests by a file of test names, which Device Run does not support.
     */
    private fun isTestFileKey(key: String): Boolean = key.lowercase() in TEST_FILE_KEYS

    /**
     * Returns whether [key] shards tests within the runner, which conflicts with sharding managed by Device Run.
     */
    private fun isShardingKey(key: String): Boolean = key in SHARDING_KEYS

    private fun isMalformedValue(value: String): Boolean = value.isEmpty() || value.any(Char::isWhitespace)

    private fun ValidationErrors.addLimitErrors(split: Split) {
        if (split.testTargets.size > MAX_TEST_TARGETS) {
            add("The ${split.testTargets.size} test targets exceed the limit of $MAX_TEST_TARGETS.")
        }
        if (split.additionalTestOptions.size > MAX_OPTIONS) {
            add("The ${split.additionalTestOptions.size} options exceed the limit of $MAX_OPTIONS.")
        }
        for ((key, value) in split.additionalTestOptions) {
            if (key.utf8Size() > MAX_OPTION_KEY_BYTES || value.utf8Size() > MAX_OPTION_VALUE_BYTES) {
                add("The option $key is longer than $MAX_OPTION_KEY_BYTES bytes or its value longer than $MAX_OPTION_VALUE_BYTES bytes.")
            }
        }
    }

    /**
     * Test targets and options passed to the instrumentation runner.
     *
     * @property testTargets the test targets in the `<selector> <value>` format of the Device Run API, such as
     * `class com.example.LoginTest`.
     */
    data class Split(
        val testTargets: List<String>,
        val additionalTestOptions: Map<String, String>,
    )

    private companion object {
        private const val SUMMARY = "Invalid instrumentation runner arguments for Device Run"

        private const val MAX_TEST_TARGETS = 1024
        private const val MAX_OPTIONS = 32
        private const val MAX_OPTION_KEY_BYTES = 64
        private const val MAX_OPTION_VALUE_BYTES = 1024

        private const val SIZE = "size"
        private val SELECTORS = listOf("package", "notPackage", "class", "notClass", "annotation", "notAnnotation", SIZE)
        private val SIZES = listOf("small", "medium", "large")
        private val TEST_FILE_KEYS = setOf("testfile", "nottestfile")
        private val SHARDING_KEYS = setOf("numShards", "shardIndex")
    }
}
