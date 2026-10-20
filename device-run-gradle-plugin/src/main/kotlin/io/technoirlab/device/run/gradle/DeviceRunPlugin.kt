package io.technoirlab.device.run.gradle

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.ManagedDevices
import com.android.build.api.variant.AndroidComponentsExtension
import io.technoirlab.device.run.gradle.api.DeviceRunDevice
import io.technoirlab.device.run.gradle.api.DeviceRunExtension
import io.technoirlab.device.run.gradle.device.DeviceRunSetupConfigureAction
import io.technoirlab.device.run.gradle.device.DeviceRunSetupTaskAction
import io.technoirlab.device.run.gradle.files.PreparePushedFilesTask
import io.technoirlab.device.run.gradle.testrun.DeviceRunTestRunConfigureAction
import io.technoirlab.device.run.gradle.testrun.DeviceRunTestRunTaskAction
import io.technoirlab.gradle.whenPluginApplied
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType

/**
 * Adds devices that run the instrumentation tests of Android projects on Device Run.
 *
 * Projects apply this plugin as `io.technoirlab.device-run`, or [DeviceRunSettingsPlugin] applies it to each Android project of the
 * build. Projects that apply it themselves must load the same copy of the plugin, such as one declared in the settings.
 */
class DeviceRunPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // The settings plugin registers the service before any project; builds without it register the service here.
        DeviceRunBuildService.registerIfAbsent(project.gradle)

        val extension = project.extensions.create(
            publicType = DeviceRunExtension::class,
            name = DeviceRunExtensionImpl.NAME,
            instanceType = DeviceRunExtensionImpl::class,
        )

        project.whenPluginApplied("com.android.base") {
            project.registerDeviceType()
            @Suppress("UnstableApiUsage")
            extension.devices.syncWith(project.extensions.getByType<CommonExtension>().testOptions.managedDevices)
            PreparePushedFilesTask.register(project, extension)
        }
    }

    private fun Project.registerDeviceType() {
        extensions.configure(AndroidComponentsExtension::class) {
            @Suppress("UnstableApiUsage")
            managedDeviceRegistry.registerDeviceType(DeviceRunDevice::class.java) {
                dslImplementationClass = DeviceRunDevice::class.java
                setSetupActions(DeviceRunSetupConfigureAction::class.java, DeviceRunSetupTaskAction::class.java)
                setTestRunActions(DeviceRunTestRunConfigureAction::class.java, DeviceRunTestRunTaskAction::class.java)
            }
        }
    }

    /**
     * Adds the devices of this container, including those declared before the Android plugin was applied, to the Android plugin's
     * [managedDevices], and the Device Run devices added there to this container. Removals are synchronized in both directions too.
     */
    @Suppress("UnstableApiUsage")
    private fun NamedDomainObjectContainer<DeviceRunDevice>.syncWith(managedDevices: ManagedDevices) {
        val devices = this
        all { managedDevices.allDevices.add(this) }
        whenObjectRemoved { managedDevices.allDevices.remove(this) }
        managedDevices.allDevices.apply {
            whenObjectAdded {
                if (this is DeviceRunDevice) {
                    devices.add(this)
                }
            }
            whenObjectRemoved {
                if (this is DeviceRunDevice) {
                    devices.remove(this)
                }
            }
        }
    }
}
