package io.technoirlab.device.run.core.testrun.sharding

import io.technoirlab.device.run.core.storage.GcsUri
import java.time.Duration

/**
 * How the job is split into shards.
 */
internal sealed interface ShardingSpec {
    /**
     * The job runs as a single shard.
     */
    data object None : ShardingSpec

    /**
     * Device Run splits the job into shards of roughly [targetedShardDuration] using a timing record from earlier runs.
     *
     * @property timingRecord the timing record in the bucket of the test run.
     * @property maxShardCount the shard limit, or null to use the service limit for the device.
     */
    data class Smart(
        val targetedShardDuration: Duration,
        val timingRecord: GcsUri,
        val maxShardCount: Int?,
    ) : ShardingSpec

    /**
     * Device Run splits the job into [shardCount] shards with about the same number of tests.
     */
    data class Uniform(
        val shardCount: Int,
    ) : ShardingSpec
}
