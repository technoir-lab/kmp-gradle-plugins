package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.konan.target.AndroidConfigurables
import org.jetbrains.kotlin.konan.target.AppleConfigurables
import org.jetbrains.kotlin.konan.target.Configurables
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.TargetTriple
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CMakeToolchainGeneratorTest {
    private val generator = CMakeToolchainGenerator()

    @ParameterizedTest
    @MethodSource("systemNames")
    fun `maps Kotlin Native families to CMake systems`(target: KonanTarget, expectedSystemName: String) {
        val toolchain = generator.generate(configurables(target))

        assertThat(toolchain).contains("set(CMAKE_SYSTEM_NAME [=[$expectedSystemName]=])")
    }

    @Test
    fun `uses target triple Clang commands arguments sysroots and LLVM archiver`() {
        val toolchain = generator.generate(FakeConfigurables(KonanTarget.LINUX_ARM64))

        assertThat(toolchain)
            .contains("set(CMAKE_SYSTEM_PROCESSOR [=[aarch64]=])")
            .contains("set(CMAKE_SYSROOT [=[/target sysroot]=])")
            .contains("set(CMAKE_FIND_ROOT_PATH [=[/target sysroot;/target toolchain]=])")
            .contains("set(CMAKE_C_COMPILER [=[/llvm home/bin/clang]=])")
            .contains("set(CMAKE_CXX_COMPILER [=[/llvm home/bin/clang++]=])")
            .contains("-target aarch64-unknown-linux-gnu")
            .contains("\"--sysroot=/target sysroot\"")
            .contains("set(CMAKE_AR [=[/llvm home/bin/llvm-ar]=] CACHE FILEPATH")
            .contains("set(CMAKE_RANLIB [=[:]=] CACHE FILEPATH")
            .contains("set(CMAKE_C_ARCHIVE_FINISH [=[]=])")
            .contains("set(CMAKE_CXX_ARCHIVE_FINISH [=[]=])")
            .contains("set(CMAKE_TRY_COMPILE_TARGET_TYPE [=[STATIC_LIBRARY]=])")
    }

    @Test
    fun `renders Apple architecture SDK sysroot and deployment settings`() {
        val toolchain = generator.generate(FakeAppleConfigurables(KonanTarget.IOS_SIMULATOR_ARM64))

        assertThat(toolchain)
            .contains("set(CMAKE_SYSTEM_NAME [=[iOS]=])")
            .contains("set(CMAKE_OSX_ARCHITECTURES [=[arm64]=])")
            .contains("set(CMAKE_OSX_SYSROOT [=[/target sysroot]=])")
            .contains("set(CMAKE_OSX_DEPLOYMENT_TARGET [=[13.2]=])")
            .contains("set(CMAKE_SYSTEM_VERSION [=[17.4]=])")
            .contains("-target arm64-apple-ios13.2-simulator")
            .contains("-stdlib=libc++")
    }

    @ParameterizedTest
    @MethodSource("androidAbis")
    fun `renders Android API and ABI settings`(target: KonanTarget, expectedAbi: String) {
        val toolchain = generator.generate(FakeAndroidConfigurables(target))

        assertThat(toolchain)
            .contains("set(CMAKE_SYSTEM_NAME [=[Android]=])")
            .contains("set(CMAKE_SYSTEM_VERSION [=[1]=])")
            .contains("set(CMAKE_ANDROID_API [=[21]=])")
            .contains("set(CMAKE_ANDROID_ARCH_ABI [=[$expectedAbi]=])")
            .contains("-D__ANDROID_API__=21")
    }

    @Test
    fun `escapes CMake values and compiler argument lists without changing them`() {
        val toolchain = CMakeToolchain(
            target = KonanTarget.LINUX_X64,
            systemName = "Linux",
            processor = "x86_64",
            sysroot = "C:\\SDK;root]=]tail",
            findRoots = listOf("C:\\SDK;root]=]tail"),
            cCompiler = "C:\\LLVM folder\\clang.exe",
            cCompilerArguments = listOf("-DVALUE=a;b", "C:\\include folder", "-DQUOTE=\"yes\""),
            cxxCompiler = "C:\\LLVM folder\\clang++.exe",
            cxxCompilerArguments = emptyList(),
            archiver = "C:\\LLVM folder\\llvm-ar.exe",
        )

        assertThat(generator.generate(toolchain))
            .contains("set(CMAKE_SYSROOT [==[C:\\SDK;root]=]tail]==])")
            .contains("set(CMAKE_C_COMPILER [=[C:\\LLVM folder\\clang.exe]=])")
            .contains("[=[\"-DVALUE=a;b\" \"C:/include folder\" \"-DQUOTE=\\\"yes\\\"\"]=]")
    }

    private fun configurables(target: KonanTarget): Configurables = when {
        target.family.isAppleFamily -> FakeAppleConfigurables(target)
        target.family == Family.ANDROID -> FakeAndroidConfigurables(target)
        else -> FakeConfigurables(target)
    }

    private open class FakeConfigurables(
        override val target: KonanTarget,
    ) : Configurables {
        override val targetTriple: TargetTriple = triples.getValue(target)
        override val absoluteTargetSysRoot: String = "/target sysroot"
        override val absoluteTargetToolchain: String = "/target toolchain"
        override val absoluteLlvmHome: String = "/llvm home"
        override val llvmVersion: String = "19"

        override fun targetString(key: String): String? = null
        override fun targetList(key: String): List<String> = emptyList()
        override fun hostString(key: String): String? = null
        override fun hostList(key: String): List<String> = emptyList()
        override fun hostTargetString(key: String): String? = null
        override fun hostTargetList(key: String): List<String> = emptyList()
        override fun absolute(value: String?): String = requireNotNull(value)
        override fun downloadDependencies() = Unit
    }

    private class FakeAppleConfigurables(
        target: KonanTarget,
    ) : FakeConfigurables(target),
        AppleConfigurables {
        override val osVersionMin: String = "13.2"
        override val sdkVersion: String = "17.4"
        override val absoluteAdditionalToolsDir: String = "/additional tools"
    }

    private class FakeAndroidConfigurables(
        target: KonanTarget,
    ) : FakeConfigurables(target),
        AndroidConfigurables

    private companion object {
        val triples = mapOf(
            KonanTarget.MACOS_X64 to TargetTriple.fromString("x86_64-apple-macos"),
            KonanTarget.IOS_ARM64 to TargetTriple.fromString("arm64-apple-ios"),
            KonanTarget.IOS_SIMULATOR_ARM64 to TargetTriple.fromString("arm64-apple-ios-simulator"),
            KonanTarget.TVOS_ARM64 to TargetTriple.fromString("arm64-apple-tvos"),
            KonanTarget.WATCHOS_DEVICE_ARM64 to TargetTriple.fromString("arm64-apple-watchos"),
            KonanTarget.LINUX_X64 to TargetTriple.fromString("x86_64-unknown-linux-gnu"),
            KonanTarget.LINUX_ARM64 to TargetTriple.fromString("aarch64-unknown-linux-gnu"),
            KonanTarget.MINGW_X64 to TargetTriple.fromString("x86_64-pc-windows-gnu"),
            KonanTarget.ANDROID_X86 to TargetTriple.fromString("i686-unknown-linux-android"),
            KonanTarget.ANDROID_X64 to TargetTriple.fromString("x86_64-unknown-linux-android"),
            KonanTarget.ANDROID_ARM32 to TargetTriple.fromString("arm-unknown-linux-androideabi"),
            KonanTarget.ANDROID_ARM64 to TargetTriple.fromString("aarch64-unknown-linux-android"),
        )

        @JvmStatic
        fun systemNames() = listOf(
            arrayOf(KonanTarget.MACOS_X64, "Darwin"),
            arrayOf(KonanTarget.IOS_ARM64, "iOS"),
            arrayOf(KonanTarget.TVOS_ARM64, "tvOS"),
            arrayOf(KonanTarget.WATCHOS_DEVICE_ARM64, "watchOS"),
            arrayOf(KonanTarget.LINUX_X64, "Linux"),
            arrayOf(KonanTarget.MINGW_X64, "Windows"),
            arrayOf(KonanTarget.ANDROID_ARM64, "Android"),
        )

        @JvmStatic
        fun androidAbis() = listOf(
            arrayOf(KonanTarget.ANDROID_X86, "x86"),
            arrayOf(KonanTarget.ANDROID_X64, "x86_64"),
            arrayOf(KonanTarget.ANDROID_ARM32, "armeabi-v7a"),
            arrayOf(KonanTarget.ANDROID_ARM64, "arm64-v8a"),
        )
    }
}
