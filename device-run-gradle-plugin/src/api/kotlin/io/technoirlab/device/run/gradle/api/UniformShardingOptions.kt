package io.technoirlab.device.run.gradle.api

import org.gradle.api.provider.Property

/**
 * Options of uniform sharding.
 */
@DeviceRunDsl
interface UniformShardingOptions {
    /**
     * The required number of shards: at most 20 for physical devices and 200 for virtual devices, and at most the number of tests.
     */
    val shardCount: Property<Int>
}
