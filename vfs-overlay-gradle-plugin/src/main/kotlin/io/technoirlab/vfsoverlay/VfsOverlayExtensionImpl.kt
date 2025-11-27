package io.technoirlab.vfsoverlay

import io.technoirlab.vfsoverlay.api.VfsOverlayExtension
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import java.io.File

internal abstract class VfsOverlayExtensionImpl(private val providerFactory: ProviderFactory) : VfsOverlayExtension {
    override val kotlinNativeDependenciesDir: Provider<File>
        get() = providerFactory.environmentVariable("KONAN_DATA_DIR").map { File(it) }
            .orElse(providerFactory.systemProperty("user.home").map { File(it, ".konan") })
            .map { File(it, "dependencies") }

    override fun mapping(source: File, target: File) {
        mappings.put(source, target)
    }

    override fun mapping(source: Provider<File>, target: Provider<File>) {
        mappings.putAll(
            source.zip(target) { sourceDir, targetDir -> mapOf(sourceDir to targetDir) }
                .orElse(emptyMap())
        )
    }
}
