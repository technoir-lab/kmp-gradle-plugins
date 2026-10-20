package io.technoirlab.device.run.gradle.api

import org.gradle.api.provider.Property
import java.time.Duration

/**
 * Options of smart sharding.
 *
 * Device Run plans shards from a timing record, `automation/smart-sharding/<record name>.yaml` in the bucket, and assumes 30 seconds
 * per test without a record. When the tests finish, it adds their durations to the latest version of the record and keeps the
 * earlier ones, including those of tests that did not run; a missing record is created. Builds that run at the same time can
 * therefore share a record.
 */
@DeviceRunDsl
interface SmartShardingOptions {
    /**
     * The maximum number of shards: at most 20 for physical devices and 200 for virtual devices.
     *
     * Unset uses the Device Run limit of the device.
     */
    val maxShardCount: Property<Int>

    /**
     * The required target duration of each shard, between 2 minutes and 3 hours.
     *
     * Shards can take longer, so keep the target well below [DeviceRunExtension.instrumentationTimeout].
     */
    val targetDuration: Property<Duration>

    /**
     * The basename of the timing record, without a path: at most 200 letters, digits, `.`, `_`, and `-`, starting with a letter or
     * digit.
     *
     * Unset derives a stable name from the root project name, project path, variant, and catalog device. Without
     * `rootProject.name` in the settings, Gradle names the root project after its directory, so checkouts in directories of
     * different names use different records.
     */
    val timingRecordName: Property<String>
}
