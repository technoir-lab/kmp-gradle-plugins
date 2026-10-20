package io.technoirlab.device.run.gradle.api

import org.gradle.api.Action
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/**
 * Selects how Device Run splits the tests into shards. The last selection applies.
 */
@DeviceRunDsl
interface ShardingOptions {
    /**
     * The selected sharding strategy. Defaults to [ShardingStrategy.NONE].
     */
    val strategy: Property<ShardingStrategy>

    /**
     * The options of smart sharding, which apply only when [strategy] is [ShardingStrategy.SMART].
     */
    @get:Nested
    val smart: SmartShardingOptions

    /**
     * The options of uniform sharding, which apply only when [strategy] is [ShardingStrategy.UNIFORM].
     */
    @get:Nested
    val uniform: UniformShardingOptions

    /**
     * Runs the tests as a single shard. This is the default.
     */
    fun none() {
        strategy.set(ShardingStrategy.NONE)
    }

    /**
     * Lets Device Run split the tests into shards of a target duration, based on the test durations of earlier runs or, without
     * them, 30 seconds per test.
     *
     * Each shard runs on its own device and is billed separately.
     */
    fun smart(action: Action<SmartShardingOptions>) {
        strategy.set(ShardingStrategy.SMART)
        action.execute(smart)
    }

    /**
     * Lets Device Run split the tests into a fixed number of shards with about the same number of tests.
     *
     * Each shard runs on its own device and is billed separately.
     */
    fun uniform(action: Action<UniformShardingOptions>) {
        strategy.set(ShardingStrategy.UNIFORM)
        action.execute(uniform)
    }
}
