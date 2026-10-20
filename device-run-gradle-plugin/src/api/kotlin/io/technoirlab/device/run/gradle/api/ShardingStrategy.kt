package io.technoirlab.device.run.gradle.api

/**
 * How Device Run splits the tests into shards.
 */
enum class ShardingStrategy {
    /**
     * The tests run as a single shard.
     */
    NONE,

    /**
     * Device Run splits the tests into shards of a target duration, based on the test durations of earlier runs or, without them,
     * 30 seconds per test.
     */
    SMART,

    /**
     * Device Run splits the tests into a fixed number of shards with about the same number of tests.
     */
    UNIFORM,
}
