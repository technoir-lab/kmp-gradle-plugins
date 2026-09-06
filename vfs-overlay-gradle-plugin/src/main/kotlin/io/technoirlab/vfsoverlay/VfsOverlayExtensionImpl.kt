package io.technoirlab.vfsoverlay

import io.technoirlab.kotlin.native.utils.KotlinNativeLayout
import io.technoirlab.vfsoverlay.api.VfsOverlayExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.io.File

internal abstract class VfsOverlayExtensionImpl(
    providerFactory: ProviderFactory,
    layout: ProjectLayout,
) : VfsOverlayExtension {
    private val nativeLayout = KotlinNativeLayout(providerFactory, layout)

    override val kotlinNativeDependenciesDir: Provider<Directory>
        get() = nativeLayout.dependenciesDirectory

    override fun mapping(source: File, target: File) {
        mappings.put(source, target)
    }

    override fun mapping(source: Directory, target: Directory) {
        mapping(source.asFile, target.asFile)
    }

    override fun mapping(source: Provider<File>, target: Provider<File>) {
        mappings.putAll(
            source.zip(target) { sourceDir, targetDir -> mapOf(sourceDir to targetDir) }
                .orElse(emptyMap()),
        )
    }
}
