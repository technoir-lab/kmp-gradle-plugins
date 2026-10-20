package io.technoirlab.device.run.gradle.files

import io.technoirlab.device.run.core.files.PushManifestSerializer
import io.technoirlab.device.run.core.files.PushedFileOptions
import io.technoirlab.device.run.gradle.DeviceRunBuildService
import io.technoirlab.device.run.gradle.api.DeviceRunExtension
import io.technoirlab.device.run.gradle.api.PushedFile
import io.technoirlab.device.run.gradle.reportingInvalidOptions
import io.technoirlab.gradle.asPath
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.problems.Problems
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

/**
 * Checks pushed Cloud Storage objects and uploads local files pushed to the device, then writes their manifest.
 *
 * The task always runs, because Gradle cannot track the objects in Cloud Storage. Test tasks consume the manifest, so they rerun
 * when a pushed object changes. Without pushed files, the task writes an empty manifest without accessing Cloud Storage.
 */
@Suppress("UnstableApiUsage")
@UntrackedTask(because = "Cloud Storage objects cannot be tracked by Gradle")
internal abstract class PreparePushedFilesTask @Inject constructor(
    private val problems: Problems,
) : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val bucket: Property<String>

    @get:Nested
    abstract val pushedFiles: ListProperty<PushedFileInput>

    @get:ServiceReference(DeviceRunBuildService.NAME)
    abstract val buildService: Property<DeviceRunBuildService>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val runner = buildService.get().runner
        val pushedFiles = pushedFiles.get().map { it.toOptions() }
        val preparedFiles = problems.reporter.reportingInvalidOptions { runner.preparePushedFiles(bucket.orNull, pushedFiles) }
        val manifestFile = manifestFile.get().asPath()
        PushManifestSerializer().write(preparedFiles, manifestFile)
    }

    private fun PushedFileInput.toOptions(): PushedFileOptions = PushedFileOptions(
        name = name.get(),
        gcsUri = gcsUri.orNull,
        localFile = localFile.orNull?.asPath(),
        destinationPath = destinationPath.orNull,
    )

    companion object {
        const val NAME = "deviceRunPreparePushedFiles"

        fun register(project: Project, extension: DeviceRunExtension): TaskProvider<PreparePushedFilesTask> =
            project.tasks.register<PreparePushedFilesTask>(NAME) {
                bucket.convention(extension.storage.bucket)
                val objects = project.objects
                val push = extension.files.push
                pushedFiles.convention(project.providers.provider { push.map { objects.newPushedFileInput(it) } })
                manifestFile.convention(project.layout.buildDirectory.file("device-run/push-manifest.json"))
            }

        private fun ObjectFactory.newPushedFileInput(pushedFile: PushedFile): PushedFileInput = newInstance<PushedFileInput>().apply {
            name.set(pushedFile.name)
            gcsUri.set(pushedFile.gcsUri)
            localFile.set(pushedFile.localFile)
            destinationPath.set(pushedFile.destinationPath)
        }
    }
}
