package io.technoirlab.device.run.core.testrun.apk

import io.technoirlab.device.run.core.storage.GcsUri

/**
 * The uploaded packages of a test run.
 *
 * @property testApk the APK that contains the tests.
 * @property targetApp the APKs of the application under test, which install together; empty when the test APK instruments itself.
 */
internal data class TestPackages(
    val testApk: GcsUri,
    val targetApp: List<GcsUri>,
)
