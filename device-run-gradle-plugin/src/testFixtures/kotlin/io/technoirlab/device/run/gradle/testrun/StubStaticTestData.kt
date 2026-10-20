package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.instrumentation.StaticTestData
import com.android.build.api.variant.AndroidVersion
import com.android.builder.testing.api.DeviceConfigProvider
import java.io.File

@Suppress("UnstableApiUsage")
internal data class StubStaticTestData(
    override val testApk: File,
    override val applicationId: String = "testing_apk",
    override val testedApplicationId: String? = "app_under_test",
    override val instrumentationTargetPackageId: String = testedApplicationId ?: applicationId,
    override val instrumentationRunner: String = "instrumentation.class",
    override val instrumentationRunnerArguments: Map<String, String> = emptyMap(),
    override val animationsDisabled: Boolean = false,
    override val isTestCoverageEnabled: Boolean = false,
    override val minSdkVersion: AndroidVersion = StubAndroidVersion(),
    override val isLibrary: Boolean = false,
    override val flavorName: String = "",
    override val testDirectories: List<File?> = emptyList(),
    override val testedApkFinder: (DeviceConfigProvider) -> List<File> = { emptyList() },
) : StaticTestData
