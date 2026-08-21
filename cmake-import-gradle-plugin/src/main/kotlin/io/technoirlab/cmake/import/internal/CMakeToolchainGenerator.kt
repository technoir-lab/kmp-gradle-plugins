package io.technoirlab.cmake.import.internal

import org.jetbrains.kotlin.konan.target.AndroidConfigurables
import org.jetbrains.kotlin.konan.target.AppleConfigurables
import org.jetbrains.kotlin.konan.target.ClangArgs
import org.jetbrains.kotlin.konan.target.Configurables
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import kotlin.io.path.Path

/**
 * Renders a CMake toolchain using the same Clang configuration as Kotlin/Native.
 */
internal class CMakeToolchainGenerator(
    private val executableSuffix: String = if (HostManager.hostIsMingw) ".exe" else "",
    private val hostTarget: KonanTarget = HostManager.host,
    private val pathSeparator: String = File.pathSeparator,
) {
    fun generate(configurables: Configurables): String {
        val clang = ClangArgs.Native(configurables)
        val cCommand = clang.clangC()
        val cxxCommand = clang.clangCXX()
        val sysroot = compilerSysroot(cCommand.drop(1)) ?: configurables.absoluteTargetSysRoot
        val toolchain = CMakeToolchain(
            target = configurables.target,
            targetTriple = configurables.targetTriple.toString(),
            systemName = configurables.target.family.cmakeSystemName,
            processor = configurables.targetTriple.architecture,
            sysroot = sysroot,
            findRoots = listOf(sysroot, configurables.absoluteTargetToolchain).distinct(),
            cCompiler = cCommand.first().withExecutableSuffix(),
            cCompilerArguments = cCommand.drop(1),
            cxxCompiler = cxxCommand.first().withExecutableSuffix(),
            cxxCompilerArguments = cxxCommand.drop(1),
            archiver = clang.llvmAr().first().withExecutableSuffix(),
            appleDeploymentTarget = (configurables as? AppleConfigurables)?.osVersionMin,
            appleSdkVersion = (configurables as? AppleConfigurables)?.sdkVersion,
            androidApi = (configurables as? AndroidConfigurables)?.let {
                cCommand.androidApi()
            },
            androidAbi = (configurables as? AndroidConfigurables)?.let {
                configurables.target.androidAbi
            },
        )
        return generate(toolchain)
    }

    fun generate(toolchain: CMakeToolchain): String = buildString {
        setting("CMAKE_SYSTEM_NAME", toolchain.systemName)
        setting("CMAKE_SYSTEM_PROCESSOR", toolchain.processor)
        setting("CMAKE_SYSROOT", toolchain.sysroot)
        setting("CMAKE_FIND_ROOT_PATH", toolchain.findRoots.joinToString(";"))
        setting("CMAKE_FIND_ROOT_PATH_MODE_PROGRAM", "NEVER")
        setting("CMAKE_FIND_ROOT_PATH_MODE_LIBRARY", "ONLY")
        setting("CMAKE_FIND_ROOT_PATH_MODE_INCLUDE", "ONLY")
        setting("CMAKE_FIND_ROOT_PATH_MODE_PACKAGE", "ONLY")
        setting("CMAKE_TRY_COMPILE_TARGET_TYPE", "STATIC_LIBRARY")

        if (toolchain.target != hostTarget) {
            appendLine()
            environmentSetting("PKG_CONFIG_PATH", "")
            environmentSetting("PKG_CONFIG_LIBDIR", toolchain.pkgConfigDirectories().joinToString(pathSeparator))
            environmentSetting("PKG_CONFIG_SYSROOT_DIR", toolchain.sysroot)
            setting("PKG_CONFIG_USE_CMAKE_PREFIX_PATH", "FALSE")
        }

        appendLine()
        setting("CMAKE_C_COMPILER", toolchain.cCompiler)
        setting("CMAKE_CXX_COMPILER", toolchain.cxxCompiler)
        setting("CMAKE_C_FLAGS_INIT", toolchain.cCompilerArguments.commandLine())
        setting("CMAKE_CXX_FLAGS_INIT", toolchain.cxxCompilerArguments.commandLine())

        appendLine()
        cacheSetting("CMAKE_AR", toolchain.archiver, "Kotlin/Native LLVM archiver")
        cacheSetting("CMAKE_C_COMPILER_AR", toolchain.archiver, "Kotlin/Native LLVM archiver")
        cacheSetting("CMAKE_CXX_COMPILER_AR", toolchain.archiver, "Kotlin/Native LLVM archiver")
        cacheSetting("CMAKE_RANLIB", ":", "Disabled for Kotlin/Native cross-platform archives")
        setting("CMAKE_C_ARCHIVE_FINISH", "")
        setting("CMAKE_CXX_ARCHIVE_FINISH", "")

        if (toolchain.target.family.isAppleFamily) {
            appendLine()
            setting("CMAKE_OSX_ARCHITECTURES", toolchain.processor)
            setting("CMAKE_OSX_SYSROOT", toolchain.sysroot)
            setting("CMAKE_OSX_DEPLOYMENT_TARGET", requireNotNull(toolchain.appleDeploymentTarget))
            setting("CMAKE_SYSTEM_VERSION", requireNotNull(toolchain.appleSdkVersion))
        }

        if (toolchain.target.family == Family.ANDROID) {
            appendLine()
            // Kotlin/Native ships split compiler and sysroot dependencies rather than a complete
            // Android NDK. Version 1 tells CMake not to replace that prepared configuration.
            setting("CMAKE_SYSTEM_VERSION", "1")
            setting("CMAKE_ANDROID_API", requireNotNull(toolchain.androidApi))
            setting("CMAKE_ANDROID_ARCH_ABI", requireNotNull(toolchain.androidAbi))
        }
    }

    private fun StringBuilder.setting(name: String, value: String) {
        appendLine("set($name ${value.cmakeArgument()})")
    }

    private fun StringBuilder.environmentSetting(name: String, value: String) {
        appendLine("set(ENV{$name} ${value.cmakeArgument()})")
    }

    private fun StringBuilder.cacheSetting(name: String, value: String, description: String) {
        appendLine("set($name ${value.cmakeArgument()} CACHE FILEPATH ${description.cmakeArgument()} FORCE)")
    }

    private fun compilerSysroot(arguments: List<String>): String? {
        arguments.firstOrNull { it.startsWith(SYSROOT_ARGUMENT) }
            ?.removePrefix(SYSROOT_ARGUMENT)
            ?.let { return it }
        val index = arguments.indexOf(ISYSROOT_ARGUMENT)
        return arguments.getOrNull(index + 1).takeIf { index >= 0 }
    }

    private fun List<String>.androidApi(): String = firstNotNullOfOrNull { argument ->
        argument.removePrefix(ANDROID_API_ARGUMENT).takeIf { it != argument }
    } ?: error("Kotlin/Native did not configure an Android API level")

    private fun List<String>.commandLine(): String = joinToString(" ") { argument ->
        val normalized = argument.replace('\\', '/')
        if (normalized.isNotEmpty() && normalized.all { it.isLetterOrDigit() || it in SAFE_COMMAND_LINE_CHARACTERS }) {
            normalized
        } else {
            "\"${normalized.replace("\"", "\\\"")}\""
        }
    }

    private fun String.withExecutableSuffix(): String =
        takeIf { executableSuffix.isEmpty() || endsWith(executableSuffix, ignoreCase = true) }
            ?: "$this$executableSuffix"

    private fun CMakeToolchain.pkgConfigDirectories(): List<String> = findRoots.flatMap { root ->
        listOf(
            "usr/lib/$targetTriple/pkgconfig",
            "usr/lib/pkgconfig",
            "usr/share/pkgconfig",
            "lib/$targetTriple/pkgconfig",
            "lib/pkgconfig",
            "share/pkgconfig",
        ).map { relativePath ->
            Path(root).resolve(relativePath).portablePathString()
        }
    }.distinct()

    private fun String.cmakeArgument(): String {
        var separator = "="
        while (contains("]$separator]")) {
            separator += "="
        }
        return "[$separator[$this]$separator]"
    }

    private val Family.cmakeSystemName: String
        get() = when (this) {
            Family.OSX -> "Darwin"
            Family.IOS -> "iOS"
            Family.TVOS -> "tvOS"
            Family.WATCHOS -> "watchOS"
            Family.LINUX -> "Linux"
            Family.MINGW -> "Windows"
            Family.ANDROID -> "Android"
        }

    private val KonanTarget.androidAbi: String
        get() = when (this) {
            KonanTarget.ANDROID_X86 -> "x86"
            KonanTarget.ANDROID_X64 -> "x86_64"
            KonanTarget.ANDROID_ARM32 -> "armeabi-v7a"
            KonanTarget.ANDROID_ARM64 -> "arm64-v8a"
            else -> error("Not an Android target: $this")
        }

    private companion object {
        const val SYSROOT_ARGUMENT = "--sysroot="
        const val ISYSROOT_ARGUMENT = "-isysroot"
        const val ANDROID_API_ARGUMENT = "-D__ANDROID_API__="
        const val SAFE_COMMAND_LINE_CHARACTERS = "_@%+=:,./-"
    }
}
