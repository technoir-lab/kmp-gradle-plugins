package io.technoirlab.volksmoke

import volk.VK_ERROR_INITIALIZATION_FAILED
import volk.VK_SUCCESS
import volk.volkInitialize
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class VolkTest {
    @Test
    @OptIn(ExperimentalNativeApi::class)
    fun initializesVulkanLoader() {
        val expectedResult = when (Platform.osFamily) {
            OsFamily.IOS, OsFamily.TVOS -> VK_ERROR_INITIALIZATION_FAILED
            else -> VK_SUCCESS
        }
        assertEquals(expectedResult, volkInitialize())
    }
}
