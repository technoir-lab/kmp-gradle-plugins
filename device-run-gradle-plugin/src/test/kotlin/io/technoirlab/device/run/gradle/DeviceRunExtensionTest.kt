package io.technoirlab.device.run.gradle

import io.technoirlab.device.run.gradle.api.CapturePolicy
import io.technoirlab.device.run.gradle.api.RetryScope
import io.technoirlab.device.run.gradle.api.ShardingStrategy
import io.technoirlab.gradle.test.kit.createRootProject
import org.assertj.core.api.Assertions.assertThat
import org.gradle.kotlin.dsl.newInstance
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class DeviceRunExtensionTest {
    private lateinit var extension: DeviceRunExtensionImpl

    @BeforeEach
    fun setUp() {
        val project = createRootProject("device-run-fixture")
        extension = project.objects.newInstance<DeviceRunExtensionImpl>()
    }

    @Test
    fun `defaults bound execution and retries and leave optional values unset`() {
        assertThat(extension.instrumentationTimeout.get()).isEqualTo(Duration.ofMinutes(5))
        assertThat(extension.sharding.strategy.get()).isEqualTo(ShardingStrategy.NONE)
        assertThat(extension.retries.maxAttempts.get()).isEqualTo(1)
        assertThat(extension.retries.parallel.get()).isFalse()
        assertThat(extension.retries.scope.get()).isEqualTo(RetryScope.SHARD)
        assertThat(extension.capture.logcat.get()).isEqualTo(CapturePolicy.ON_FAILURE)
        assertThat(extension.capture.video.get()).isEqualTo(CapturePolicy.DISABLED)
        assertThat(
            listOf(
                extension.storage.bucket,
                extension.waitTimeout,
                extension.sharding.uniform.shardCount,
                extension.sharding.smart.targetDuration,
                extension.sharding.smart.timingRecordName,
                extension.sharding.smart.maxShardCount,
            ),
        ).extracting<Any?> { it.orNull }.containsOnlyNulls()
        assertThat(extension.labels.get()).isEmpty()
        assertThat(extension.files.push).isEmpty()
        assertThat(extension.files.pathsToPull.get()).isEmpty()
    }

    @Test
    fun `uniform sharding replaces another selection and sets the shard count`() {
        extension.sharding.smart { targetDuration.set(Duration.ofMinutes(4)) }

        extension.sharding.uniform { shardCount.set(3) }

        assertThat(extension.sharding.strategy.get()).isEqualTo(ShardingStrategy.UNIFORM)
        assertThat(extension.sharding.uniform.shardCount.get()).isEqualTo(3)
    }

    @Test
    fun `none in the nested DSL replaces an earlier sharding selection`() {
        extension.sharding.smart { targetDuration.set(Duration.ofMinutes(4)) }

        extension.sharding { none() }

        assertThat(extension.sharding.strategy.get()).isEqualTo(ShardingStrategy.NONE)
    }

    @Test
    fun `selecting a sharding strategy again keeps its earlier options`() {
        extension.sharding.smart { targetDuration.set(Duration.ofMinutes(4)) }
        extension.sharding.none()

        extension.sharding.smart { maxShardCount.set(2) }

        assertThat(extension.sharding.strategy.get()).isEqualTo(ShardingStrategy.SMART)
        assertThat(extension.sharding.smart.targetDuration.get()).isEqualTo(Duration.ofMinutes(4))
        assertThat(extension.sharding.smart.maxShardCount.get()).isEqualTo(2)
    }

    @Test
    @Suppress("UnstableApiUsage")
    fun `device container creates devices without options`() {
        val device = extension.devices.create("pixel")

        assertThat(device.name).isEqualTo("pixel")
        assertThat(listOf(device.deviceId, device.locale, device.orientation)).extracting<Any?> { it.orNull }.containsOnlyNulls()
    }
}
