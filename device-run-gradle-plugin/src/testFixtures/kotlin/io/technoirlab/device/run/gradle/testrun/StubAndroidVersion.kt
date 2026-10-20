package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.variant.AndroidVersion

internal class StubAndroidVersion(
    override val apiLevel: Int = 21,
    override val codename: String? = null,
) : AndroidVersion
