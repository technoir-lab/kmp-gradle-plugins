package io.technoirlab.device.run.gradle.testrun

import io.technoirlab.device.run.core.testrun.job.ArtifactCapture
import io.technoirlab.device.run.core.testrun.job.TestReductionMode
import io.technoirlab.device.run.gradle.api.CapturePolicy
import io.technoirlab.device.run.gradle.api.DeviceOrientation
import io.technoirlab.device.run.gradle.api.RetryScope
import io.technoirlab.device.run.core.device.DeviceOrientation as CoreDeviceOrientation

internal fun ArtifactCapture.toCapturePolicy(): CapturePolicy = when (this) {
    ArtifactCapture.DISABLED -> CapturePolicy.DISABLED
    ArtifactCapture.ON_FAILURE -> CapturePolicy.ON_FAILURE
    ArtifactCapture.ALWAYS -> CapturePolicy.ALWAYS
}

internal fun CapturePolicy.toArtifactCapture(): ArtifactCapture = when (this) {
    CapturePolicy.DISABLED -> ArtifactCapture.DISABLED
    CapturePolicy.ON_FAILURE -> ArtifactCapture.ON_FAILURE
    CapturePolicy.ALWAYS -> ArtifactCapture.ALWAYS
}

internal fun DeviceOrientation.toCoreDeviceOrientation(): CoreDeviceOrientation = when (this) {
    DeviceOrientation.PORTRAIT -> CoreDeviceOrientation.PORTRAIT
    DeviceOrientation.LANDSCAPE -> CoreDeviceOrientation.LANDSCAPE
}

internal fun RetryScope.toTestReductionMode(): TestReductionMode = when (this) {
    RetryScope.SHARD -> TestReductionMode.NO_REDUCTION
    RetryScope.FAILED_TEST_CASES -> TestReductionMode.REDUCE_TO_FAILED_TEST_CASES
}

internal fun TestReductionMode.toRetryScope(): RetryScope = when (this) {
    TestReductionMode.NO_REDUCTION -> RetryScope.SHARD
    TestReductionMode.REDUCE_TO_FAILED_TEST_CASES -> RetryScope.FAILED_TEST_CASES
}
