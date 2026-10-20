package io.technoirlab.device.run.core.testrun.job

/**
 * Which tests a retry attempt runs.
 */
enum class TestReductionMode {
    /**
     * Retries run every test of the failed shard.
     */
    NO_REDUCTION,

    /**
     * Retries run only the failed tests.
     */
    REDUCE_TO_FAILED_TEST_CASES,
}
