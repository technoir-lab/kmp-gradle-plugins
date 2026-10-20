package io.technoirlab.device.run.fixture

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

internal const val ORCHESTRATOR_PACKAGE = "androidx.test.orchestrator"
internal const val TEST_SERVICES_PACKAGE = "androidx.test.services"

/**
 * Logs and returns the version name of [packageName] on the device, or null when the package is not installed.
 */
internal fun installedVersionName(packageName: String): String? {
    val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys package $packageName")
    val output = ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use { it.readText() }
    val versionName = Regex("versionName=(\\S+)").find(output)?.groupValues?.get(1)
    Log.i("DeviceRunProbe", "$packageName versionName=$versionName")
    return versionName
}
