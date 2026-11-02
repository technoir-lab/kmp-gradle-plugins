package io.technoirlab.device.run.gradle

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Device
import com.android.build.api.dsl.TestExtension
import io.technoirlab.device.run.gradle.api.DeviceRunDevice
import io.technoirlab.device.run.gradle.api.DeviceRunExtension
import io.technoirlab.device.run.gradle.files.PreparePushedFilesTask
import io.technoirlab.gradle.test.kit.createRootProject
import io.technoirlab.gradle.test.kit.evaluate
import io.technoirlab.gradle.test.kit.subProject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.DefaultTask
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceRunPluginTest {
    private lateinit var project: Project
    private val plugin = DeviceRunPlugin()

    @BeforeEach
    fun setUp() {
        project = createRootProject("device-run-fixture") {
            apply(plugin = "com.android.application")
        }
    }

    @Test
    @Suppress("UnstableApiUsage")
    fun `registers the Device Run device type in the Android device container`() {
        plugin.apply(project)

        val device = project.managedDevices().create<DeviceRunDevice>("pixel")
        val extension = project.extensions.getByType<DeviceRunExtension>()

        assertThat(device).isInstanceOf(DeviceRunDevice::class.java)
        assertThat(device.name).isEqualTo("pixel")
        assertThat(extension.devices).containsExactly(device)
    }

    @Test
    fun `adds devices of the extension to Android managed devices`() {
        plugin.apply(project)
        val extension = project.extensions.getByType<DeviceRunExtension>()

        val device = extension.devices.create("pixel")

        assertThat(project.managedDevices()).containsExactly(device)
    }

    @Test
    fun `adds devices registered before the Android plugin to Android managed devices`() {
        val project = createRootProject("device-run-fixture")
        plugin.apply(project)
        project.extensions.getByType<DeviceRunExtension>().devices.register("pixel")

        project.apply(plugin = "com.android.application")

        assertThat(project.managedDevices().names).containsExactly("pixel")
    }

    @Test
    fun `drops devices of the extension removed from Android managed devices`() {
        plugin.apply(project)
        val extension = project.extensions.getByType<DeviceRunExtension>()
        val device = extension.devices.create("pixel")

        project.managedDevices().remove(device)

        assertThat(extension.devices).isEmpty()
    }

    @Test
    fun `fails the configuration of a project without an Android plugin`() {
        val project = createRootProject("device-run-fixture")
        plugin.apply(project)

        assertThatThrownBy { project.evaluate() }
            .hasRootCauseMessage("Plugin 'com.android.base' is not applied")
    }

    @Test
    fun `pushed file preparation receives the files with the tasks that produce them`() {
        plugin.apply(project)
        val extension = project.extensions.getByType<DeviceRunExtension>()
        val task = project.preparePushedFilesTask()
        val producer = project.tasks.register<GenerateInput>("generateInput") {
            output.set(project.layout.buildDirectory.file("input.txt"))
        }

        extension.storage.bucket.set("example-bucket")
        extension.files.push.register("generated") {
            localFile.set(producer.flatMap { it.output })
            destinationPath.set("/sdcard/input.txt")
        }
        extension.files.push.register("shared") {
            gcsUri.set("gs://example-bucket/shared.txt")
            destinationPath.set("/sdcard/shared.txt")
        }
        project.layout.buildDirectory.set(project.layout.projectDirectory.dir("output"))
        val pushedFiles = task.pushedFiles.get()

        assertThat(task.bucket.get()).isEqualTo("example-bucket")
        assertThat(pushedFiles).extracting<String> { it.name.get() }.containsExactly("generated", "shared")
        assertThat(pushedFiles.first().localFile.get()).isEqualTo(project.layout.projectDirectory.file("output/input.txt"))
        assertThat(pushedFiles.last().gcsUri.get()).isEqualTo("gs://example-bucket/shared.txt")
        assertThat(task.taskDependencies.getDependencies(task)).containsExactly(producer.get())
        assertThat(task.buildService.get()).isSameAs(DeviceRunBuildService.registerIfAbsent(project.gradle).get())
        assertThat(task.manifestFile.get()).isEqualTo(project.layout.projectDirectory.file("output/device-run/push-manifest.json"))
    }

    @Test
    fun `cloud tasks use the build service registered before the plugin`() {
        val service = project.gradle.registerStubDeviceRunService()

        plugin.apply(project)

        assertThat(project.preparePushedFilesTask().buildService.get()).isSameAs(service.get())
    }

    @Test
    fun `sibling projects share one build service`() {
        val root = createRootProject("device-run-fixture")
        val (first, second) = listOf("first", "second").map { name ->
            root.subProject(name) {
                apply(plugin = "com.android.library")
                apply<DeviceRunPlugin>()
            }
        }

        val secondService = second.preparePushedFilesTask().buildService.get()
        val firstService = first.preparePushedFilesTask().buildService.get()

        assertThat(firstService).isSameAs(secondService)
    }

    @Test
    fun `cloud tests run after pushed file preparation and device setup`() {
        project.configureAndroid()
        plugin.apply(project)
        project.registerSmallPhone()

        project.evaluate()

        project.assertCloudTestsRunAfterPreparation()
    }

    @Test
    fun `cloud tests of a library module run after pushed file preparation and device setup`() {
        val library = createRootProject("device-run-fixture") {
            apply(plugin = "com.android.library")
            configureAndroid()
        }
        plugin.apply(library)
        library.registerSmallPhone()

        library.evaluate()

        library.assertCloudTestsRunAfterPreparation()
    }

    @Test
    fun `cloud tests of a test module run after pushed file preparation and device setup`() {
        val root = createRootProject("device-run-fixture")
        val application = root.subProject("android-application") {
            apply(plugin = "com.android.application")
            configureAndroid()
        }
        val testModule = root.subProject("android-test") {
            apply(plugin = "com.android.test")
            configureAndroid()
            configure<TestExtension> {
                targetProjectPath = ":android-application"
            }
        }
        plugin.apply(testModule)
        testModule.registerSmallPhone()

        application.evaluate()
        testModule.evaluate()

        testModule.assertCloudTestsRunAfterPreparation()
    }

    private fun Project.configureAndroid() {
        extensions.configure<CommonExtension> {
            namespace = "io.technoirlab.device.run.fixture"
            compileSdk = 37
            defaultConfig.minSdk = 30
        }
    }

    private fun Project.registerSmallPhone() {
        extensions.getByType<DeviceRunExtension>().devices.register("smallPhone") {
            deviceId.set("smallphone-arm-35")
        }
    }

    private fun Project.assertCloudTestsRunAfterPreparation() {
        val testTask = tasks.named("smallPhoneDebugAndroidTest").get()
        val setupTask = tasks.named("smallPhoneSetup").get()
        assertThat(testTask.taskDependencies.getDependencies(testTask)).contains(preparePushedFilesTask(), setupTask)
    }

    @Suppress("UnstableApiUsage")
    private fun Project.managedDevices(): ExtensiblePolymorphicDomainObjectContainer<Device> =
        extensions.getByType<CommonExtension>().testOptions.managedDevices.allDevices

    private fun Project.preparePushedFilesTask(): PreparePushedFilesTask =
        tasks.named<PreparePushedFilesTask>(PreparePushedFilesTask.NAME).get()

    internal abstract class GenerateInput : DefaultTask() {
        @get:OutputFile
        abstract val output: RegularFileProperty
    }
}
