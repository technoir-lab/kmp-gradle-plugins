package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.kotlin.konan.target.AndroidConfigurables
import org.jetbrains.kotlin.konan.target.AppleConfigurables
import org.jetbrains.kotlin.konan.target.Configurables
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.TargetTriple
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CMakeToolchainGeneratorTest {
    private val generator = CMakeToolchainGenerator(
        executableSuffix = "",
        commandScriptSuffix = "",
        pathExists = { true },
    )

    @ParameterizedTest
    @MethodSource("systemNames")
    fun `maps Kotlin Native families to CMake systems`(target: KonanTarget, expectedSystemName: String) {
        val toolchain = generator.generate(configurables(target))

        assertThat(toolchain).contains("set(CMAKE_SYSTEM_NAME [=[$expectedSystemName]=])")
    }

    @Test
    fun `uses target triple Clang commands arguments sysroots and LLVM archiver`() {
        val toolchain = CMakeToolchainGenerator(
            executableSuffix = "",
            hostTarget = KonanTarget.MACOS_ARM64,
            pathSeparator = ":",
            pathExists = { true },
        ).generate(FakeConfigurables(KonanTarget.LINUX_ARM64))

        assertThat(toolchain)
            .contains("set(CMAKE_SYSTEM_PROCESSOR [=[aarch64]=])")
            .contains("set(CMAKE_SYSROOT [=[/target-sysroot]=])")
            .contains("set(CMAKE_FIND_ROOT_PATH [=[/target-sysroot;/target-toolchain]=])")
            .contains("set(CMAKE_C_COMPILER [=[/llvm-home/bin/clang]=])")
            .contains("set(CMAKE_CXX_COMPILER [=[/llvm-home/bin/clang++]=])")
            .contains("-target aarch64-unknown-linux-gnu")
            .contains("--sysroot=/target-sysroot")
            .contains("set(CMAKE_AR [=[/llvm-home/bin/llvm-ar]=] CACHE FILEPATH")
            .contains("set(CMAKE_RANLIB [=[:]=] CACHE FILEPATH")
            .contains("set(CMAKE_C_ARCHIVE_FINISH [=[]=])")
            .contains("set(CMAKE_CXX_ARCHIVE_FINISH [=[]=])")
            .contains("if(NOT DEFINED CMAKE_LINKER_TYPE)")
            .contains("set(CMAKE_LINKER_TYPE [=[kotlin_native]=])")
            .contains("if(CMAKE_LINKER_TYPE STREQUAL [=[kotlin_native]=])")
            .contains("set(CMAKE_C_USING_LINKER_kotlin_native [=[--ld-path=/host-linker]=])")
            .contains("set(CMAKE_CXX_USING_LINKER_kotlin_native [=[--ld-path=/host-linker]=])")
            .doesNotContain("CMAKE_TRY_COMPILE_TARGET_TYPE")
            .contains("set(ENV{PKG_CONFIG_PATH} [=[]=])")
            .contains("set(ENV{PKG_CONFIG_SYSROOT_DIR} [=[/target-sysroot]=])")
            .contains(
                "set(ENV{PKG_CONFIG_LIBDIR} " +
                    "[=[/target-sysroot/usr/lib/aarch64-unknown-linux-gnu/pkgconfig:" +
                    "/target-sysroot/usr/lib/pkgconfig:" +
                    "/target-sysroot/usr/share/pkgconfig:" +
                    "/target-sysroot/lib/aarch64-unknown-linux-gnu/pkgconfig:" +
                    "/target-sysroot/lib/pkgconfig:" +
                    "/target-sysroot/share/pkgconfig:" +
                    "/target-toolchain/usr/lib/aarch64-unknown-linux-gnu/pkgconfig:" +
                    "/target-toolchain/usr/lib/pkgconfig:" +
                    "/target-toolchain/usr/share/pkgconfig:" +
                    "/target-toolchain/lib/aarch64-unknown-linux-gnu/pkgconfig:" +
                    "/target-toolchain/lib/pkgconfig:" +
                    "/target-toolchain/share/pkgconfig]=])",
            )
            .contains("set(PKG_CONFIG_USE_CMAKE_PREFIX_PATH [=[FALSE]=])")
            .contains("set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM [=[NEVER]=])")
    }

    @Test
    fun `preserves pkg-config environment for native host targets`() {
        val toolchain = CMakeToolchainGenerator(
            executableSuffix = "",
            hostTarget = KonanTarget.LINUX_ARM64,
            pathSeparator = ":",
            pathExists = { true },
        ).generate(FakeConfigurables(KonanTarget.LINUX_ARM64))

        assertThat(toolchain)
            .doesNotContain("PKG_CONFIG_PATH")
            .doesNotContain("PKG_CONFIG_LIBDIR")
            .doesNotContain("PKG_CONFIG_SYSROOT_DIR")
            .doesNotContain("PKG_CONFIG_USE_CMAKE_PREFIX_PATH")
    }

    @Test
    fun `isolated pkg-config finds target metadata without inherited host metadata`(@TempDir temporaryDirectory: Path) {
        assumeTrue(commandSucceeds("cmake", "--version"), "CMake is not available")
        assumeTrue(commandSucceeds("pkg-config", "--version"), "pkg-config is not available")

        val hostPkgConfigDirectory = temporaryDirectory.resolve("host/lib/pkgconfig").createDirectories()
        val sysroot = temporaryDirectory.resolve("target-sysroot")
        val targetPkgConfigDirectory = sysroot.resolve("usr/lib/pkgconfig").createDirectories()
        hostPkgConfigDirectory.resolve("host-only.pc").writeText(pkgConfigFile("host-only"))
        targetPkgConfigDirectory.resolve("target-only.pc").writeText(pkgConfigFile("target-only"))

        val toolchainFile = temporaryDirectory.resolve("toolchain.cmake")
        toolchainFile.writeText(
            CMakeToolchainGenerator(
                executableSuffix = "",
                hostTarget = KonanTarget.MACOS_ARM64,
                pathSeparator = File.pathSeparator,
                pathExists = { true },
            ).generate(
                CMakeToolchain(
                    target = KonanTarget.LINUX_X64,
                    targetTriple = "x86_64-unknown-linux-gnu",
                    systemName = "Linux",
                    processor = "x86_64",
                    sysroot = sysroot.toString(),
                    findRoots = listOf(sysroot.toString()),
                    cCompiler = CMakeCompilerSettings(
                        command = "clang",
                        arguments = emptyList(),
                    ),
                    cxxCompiler = CMakeCompilerSettings(
                        command = "clang++",
                        arguments = emptyList(),
                    ),
                    archiver = "llvm-ar",
                ),
            ),
        )
        val script = temporaryDirectory.resolve("verify-pkg-config.cmake")
        script.writeText(
            $$"""
            include($${toolchainFile.cmakeArgument()})
            find_package(PkgConfig REQUIRED)
            pkg_check_modules(HOST_ONLY QUIET host-only)
            if(HOST_ONLY_FOUND)
                message(FATAL_ERROR "inherited host pkg-config metadata leaked into cross configuration")
            endif()
            pkg_check_modules(TARGET_ONLY REQUIRED target-only)
            if(NOT TARGET_ONLY_INCLUDE_DIRS STREQUAL $${sysroot.resolve("usr/include").cmakeArgument()})
                message(FATAL_ERROR "unexpected target include directories: ${TARGET_ONLY_INCLUDE_DIRS}")
            endif()
            """.trimIndent(),
        )

        val process = ProcessBuilder("cmake", "-P", script.toString())
            .redirectErrorStream(true)
            .apply { environment()["PKG_CONFIG_PATH"] = hostPkgConfigDirectory.toString() }
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        assertThat(process.waitFor())
            .describedAs("CMake output:%n%s", output)
            .isZero()
    }

    @Test
    fun `preserves a user selected CMake linker type`(@TempDir temporaryDirectory: Path) {
        assumeTrue(commandSucceeds("cmake", "--version"), "CMake is not available")
        val toolchainFile = temporaryDirectory.resolve("toolchain.cmake")
        toolchainFile.writeText(
            generator.generate(
                CMakeToolchain(
                    target = KonanTarget.LINUX_X64,
                    targetTriple = "x86_64-unknown-linux-gnu",
                    systemName = "Linux",
                    processor = "x86_64",
                    sysroot = "/target-sysroot",
                    findRoots = listOf("/target-sysroot"),
                    cCompiler = CMakeCompilerSettings(
                        command = "clang",
                        arguments = emptyList(),
                    ),
                    cxxCompiler = CMakeCompilerSettings(
                        command = "clang++",
                        arguments = emptyList(),
                    ),
                    archiver = "llvm-ar",
                    compilerDriverLinker = "/host-linker",
                ),
            ),
        )
        val script = temporaryDirectory.resolve("verify-linker-override.cmake")
        script.writeText(
            $$"""
            set(CMAKE_LINKER_TYPE user_selected)
            include($${toolchainFile.cmakeArgument()})
            if(NOT CMAKE_LINKER_TYPE STREQUAL user_selected)
                message(FATAL_ERROR "toolchain replaced user linker type: ${CMAKE_LINKER_TYPE}")
            endif()
            if(DEFINED CMAKE_C_USING_LINKER_kotlin_native)
                message(FATAL_ERROR "toolchain configured its linker flags for a user-selected type")
            endif()
            """.trimIndent(),
        )

        val process = ProcessBuilder("cmake", "-P", script.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        assertThat(process.waitFor())
            .describedAs("CMake output:%n%s", output)
            .isZero()
    }

    @Test
    fun `uses Windows executable suffix for Kotlin Native tools and linker before validation`() {
        val toolchain = CMakeToolchainGenerator(
            executableSuffix = ".exe",
            pathExists = { it.endsWith(".exe") },
        ).generate(FakeConfigurables(KonanTarget.LINUX_X64, linker = "/llvm-home/bin/ld.gold"))

        assertThat(toolchain)
            .contains("set(CMAKE_C_COMPILER [=[/llvm-home/bin/clang.exe]=])")
            .contains("set(CMAKE_CXX_COMPILER [=[/llvm-home/bin/clang++.exe]=])")
            .contains("set(CMAKE_AR [=[/llvm-home/bin/llvm-ar.exe]=] CACHE FILEPATH")
            .contains(
                "set(CMAKE_C_USING_LINKER_kotlin_native " +
                    "[=[--ld-path=/llvm-home/bin/ld.gold.exe]=])",
            )
    }

    @ParameterizedTest
    @MethodSource("appleTargets")
    fun `renders Apple architecture SDK sysroot and deployment settings`(
        target: KonanTarget,
        expectedSystemName: String,
        expectedTargetTriple: String,
    ) {
        val toolchain = generator.generate(FakeAppleConfigurables(target))

        assertThat(toolchain)
            .contains("set(CMAKE_SYSTEM_NAME [=[$expectedSystemName]=])")
            .contains("set(CMAKE_OSX_ARCHITECTURES [=[arm64]=])")
            .contains("set(CMAKE_OSX_SYSROOT [=[/target-sysroot]=])")
            .contains("set(CMAKE_OSX_DEPLOYMENT_TARGET [=[13.2]=])")
            .contains("set(CMAKE_SYSTEM_VERSION [=[17.4]=])")
            .contains("-target $expectedTargetTriple")
            .contains("-stdlib=libc++")
            .doesNotContain("CMAKE_LINKER_TYPE")
            .doesNotContain("CMAKE_TRY_COMPILE_TARGET_TYPE")
    }

    @ParameterizedTest
    @MethodSource("androidAbis")
    fun `renders Android API ABI and executable linker settings`(
        target: KonanTarget,
        expectedAbi: String,
        expectedCompilerDriver: String,
    ) {
        val toolchain = generator.generate(FakeAndroidConfigurables(target))
        val cCompileRule = toolchain.lineSequence().single { it.startsWith("set(CMAKE_C_COMPILE_OBJECT") }
        val cxxCompileRule = toolchain.lineSequence().single { it.startsWith("set(CMAKE_CXX_COMPILE_OBJECT") }

        assertThat(toolchain)
            .contains("set(CMAKE_SYSTEM_NAME [=[Android]=])")
            .contains("set(CMAKE_SYSTEM_VERSION [=[1]=])")
            .contains("set(CMAKE_ANDROID_API [=[21]=])")
            .contains("set(CMAKE_ANDROID_ARCH_ABI [=[$expectedAbi]=])")
            .contains("-D__ANDROID_API__=21")
            .contains("set(CMAKE_C_COMPILE_OBJECT")
            .contains("set(CMAKE_CXX_COMPILE_OBJECT")
            .contains(
                "set(CMAKE_C_LINK_EXECUTABLE " +
                    "[=[$expectedCompilerDriver <FLAGS> <CMAKE_C_LINK_FLAGS> <LINK_FLAGS> " +
                    "<OBJECTS> -o <TARGET> " +
                    "<LINK_LIBRARIES> -lm -lc++_static -lc++abi -landroid -llog -latomic]=])",
            )
            .contains("set(CMAKE_CXX_LINK_EXECUTABLE")
            .doesNotContain("CMAKE_C_FLAGS_INIT")
            .doesNotContain("CMAKE_CXX_FLAGS_INIT")
            .doesNotContain("CMAKE_TRY_COMPILE_TARGET_TYPE")
            .doesNotContain("CMAKE_LINKER_TYPE")
        assertThat(cCompileRule)
            .containsSubsequence("<DEFINES>", "<INCLUDES>", "-D__ANDROID_API__=21", "<FLAGS>")
        assertThat(cxxCompileRule)
            .containsSubsequence("<DEFINES>", "<INCLUDES>", "-D__ANDROID_API__=21", "<FLAGS>")
    }

    @Test
    fun `uses Windows command script suffix for Android linker driver before validation`() {
        val toolchain = CMakeToolchainGenerator(
            executableSuffix = ".exe",
            commandScriptSuffix = ".cmd",
            pathExists = { it.endsWith(".cmd") },
        ).generate(FakeAndroidConfigurables(KonanTarget.ANDROID_ARM64))

        assertThat(toolchain)
            .contains("set(CMAKE_C_COMPILER [=[/llvm-home/bin/clang.exe]=])")
            .contains("/target-toolchain/bin/aarch64-linux-android21-clang.cmd <FLAGS>")
    }

    @Test
    fun `normalizes Windows Kotlin Native paths before rendering CMake settings`() {
        val toolchain = CMakeToolchainGenerator(
            executableSuffix = ".exe",
            commandScriptSuffix = ".cmd",
            pathExists = { true },
        ).generate(
            FakeAndroidConfigurables(
                target = KonanTarget.ANDROID_ARM64,
                targetSysRoot = "C:\\Users\\user1\\.konan\\dependencies\\target-sysroot-1-android_ndk",
                targetToolchain = "C:\\Users\\user1\\.konan\\dependencies\\target-toolchain-2-android_ndk",
                llvmHome = "C:\\Users\\user1\\.konan\\dependencies\\llvm-19",
            ),
        )

        assertThat(toolchain)
            .contains(
                "set(CMAKE_SYSROOT " +
                    "[=[C:/Users/user1/.konan/dependencies/target-sysroot-1-android_ndk/" +
                    "android-21/arch-arm64]=])",
            )
            .contains(
                "set(CMAKE_C_LINK_EXECUTABLE " +
                    "[=[C:/Users/user1/.konan/dependencies/target-toolchain-2-android_ndk/bin/" +
                    "aarch64-linux-android21-clang.cmd <FLAGS>",
            )
            .doesNotContain("C:\\Users")
    }

    @Test
    fun `escapes CMake values and compiler argument lists without changing them`() {
        val toolchain = CMakeToolchain(
            target = KonanTarget.LINUX_X64,
            targetTriple = "x86_64-unknown-linux-gnu",
            systemName = "Linux",
            processor = "x86_64",
            sysroot = "C:\\SDK;root]=]tail",
            findRoots = listOf("C:\\SDK;root]=]tail"),
            cCompiler = CMakeCompilerSettings(
                command = "C:\\LLVM folder\\clang.exe",
                arguments = listOf("-DVALUE=a;b", "C:\\include folder", "-DQUOTE=\"yes\""),
            ),
            cxxCompiler = CMakeCompilerSettings(
                command = "C:\\LLVM folder\\clang++.exe",
                arguments = emptyList(),
            ),
            archiver = "C:\\LLVM folder\\llvm-ar.exe",
            compilerDriverLinker = "C:\\LLVM folder\\ld.lld.exe",
        )

        assertThat(generator.generate(toolchain))
            .contains("set(CMAKE_SYSROOT [==[C:\\SDK;root]=]tail]==])")
            .contains("set(CMAKE_C_COMPILER [=[C:\\LLVM folder\\clang.exe]=])")
            .contains("[=[\"-DVALUE=a;b\" \"C:/include folder\" \"-DQUOTE=\\\"yes\\\"\"]=]")
            .contains(
                "set(CMAKE_C_USING_LINKER_kotlin_native " +
                    "[=[--ld-path=C:/LLVM folder/ld.lld.exe]=])",
            )
    }

    @Test
    fun `escapes Android linker paths without changing placeholders`() {
        val toolchain = CMakeToolchain(
            target = KonanTarget.ANDROID_ARM64,
            targetTriple = "aarch64-unknown-linux-android",
            systemName = "Android",
            processor = "aarch64",
            sysroot = "C:\\Android SDK\\sysroot",
            findRoots = listOf("C:\\Android SDK\\sysroot"),
            cCompiler = CMakeCompilerSettings(
                command = "C:\\LLVM folder\\clang.exe",
                arguments = emptyList(),
            ),
            cxxCompiler = CMakeCompilerSettings(
                command = "C:\\LLVM folder\\clang++.exe",
                arguments = emptyList(),
            ),
            archiver = "C:\\LLVM folder\\llvm-ar.exe",
            androidExecutableLinker = CMakeExecutableLinker(
                compilerDriver = "C:\\Android SDK\\bin\\aarch64-linux-android21-clang.cmd",
                libraries = listOf("-lm"),
            ),
            androidApi = "21",
            androidAbi = "arm64-v8a",
        )

        assertThat(generator.generate(toolchain))
            .contains(
                "[=[\"C:/Android SDK/bin/aarch64-linux-android21-clang.cmd\" " +
                    "<FLAGS> <CMAKE_C_LINK_FLAGS> <LINK_FLAGS> " +
                    "<OBJECTS> -o <TARGET> <LINK_LIBRARIES> -lm]=]",
            )
    }

    @Test
    fun `reports a missing Kotlin Native linker`() {
        assertThatThrownBy {
            CMakeToolchainGenerator(executableSuffix = "", pathExists = { true })
                .generate(FakeConfigurables(KonanTarget.LINUX_X64, linker = null))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Kotlin/Native did not configure a linker for target linux_x64")
    }

    @Test
    fun `reports a nonexistent Kotlin Native linker`() {
        assertThatThrownBy {
            CMakeToolchainGenerator(executableSuffix = "", pathExists = { false })
                .generate(FakeConfigurables(KonanTarget.MINGW_X64, linker = "/missing/ld.lld"))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Kotlin/Native linker for target mingw_x64 does not exist at /missing/ld.lld",
            )
    }

    @Test
    fun `reports a nonexistent Kotlin Native Android linker driver`() {
        assertThatThrownBy {
            CMakeToolchainGenerator(
                executableSuffix = "",
                commandScriptSuffix = "",
                pathExists = { false },
            ).generate(FakeAndroidConfigurables(KonanTarget.ANDROID_ARM64))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Kotlin/Native Android linker driver for target android_arm64 does not exist at " +
                    "/target-toolchain/bin/aarch64-linux-android21-clang",
            )
    }

    private fun configurables(target: KonanTarget): Configurables = when {
        target.family.isAppleFamily -> FakeAppleConfigurables(target)
        target.family == Family.ANDROID -> FakeAndroidConfigurables(target)
        else -> FakeConfigurables(target)
    }

    private fun commandSucceeds(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrDefault(false)

    private fun pkgConfigFile(name: String): String = $$"""
        prefix=/usr
        includedir=${prefix}/include

        Name: $$name
        Description: Test metadata for $$name
        Version: 1.0
        Cflags: -I${includedir}
    """.trimIndent()

    private fun Path.cmakeArgument(): String = "[=[$this]=]"

    private open class FakeConfigurables(
        override val target: KonanTarget,
        private val linker: String? = "/host-linker",
        targetSysRoot: String = "/target-sysroot",
        targetToolchain: String = "/target-toolchain",
        llvmHome: String = "/llvm-home",
    ) : Configurables {
        override val targetTriple: TargetTriple = triples.getValue(target)
        override val absoluteTargetSysRoot: String = targetSysRoot
        override val absoluteTargetToolchain: String = targetToolchain
        override val absoluteLlvmHome: String = llvmHome
        override val llvmVersion: String = "19"

        override fun targetString(key: String): String? = null
        override fun targetList(key: String): List<String> = when {
            target.family == Family.ANDROID && key == "linkerKonanFlags" -> ANDROID_LINKER_FLAGS
            else -> emptyList()
        }

        override fun hostString(key: String): String? = null
        override fun hostList(key: String): List<String> = emptyList()
        override fun hostTargetString(key: String): String? = linker.takeIf { key == "linker" }
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
        override val absoluteAdditionalToolsDir: String = "/additional-tools"
    }

    private class FakeAndroidConfigurables(
        target: KonanTarget,
        targetSysRoot: String = "/target-sysroot",
        targetToolchain: String = "/target-toolchain",
        llvmHome: String = "/llvm-home",
    ) : FakeConfigurables(
        target = target,
        targetSysRoot = targetSysRoot,
        targetToolchain = targetToolchain,
        llvmHome = llvmHome,
    ),
        AndroidConfigurables

    private companion object {
        val triples = mapOf(
            KonanTarget.MACOS_X64 to TargetTriple.fromString("x86_64-apple-macos"),
            KonanTarget.IOS_ARM64 to TargetTriple.fromString("arm64-apple-ios"),
            KonanTarget.IOS_SIMULATOR_ARM64 to TargetTriple.fromString("arm64-apple-ios-simulator"),
            KonanTarget.TVOS_ARM64 to TargetTriple.fromString("arm64-apple-tvos"),
            KonanTarget.TVOS_SIMULATOR_ARM64 to TargetTriple.fromString("arm64-apple-tvos-simulator"),
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
        fun appleTargets() = listOf(
            arrayOf(KonanTarget.IOS_ARM64, "iOS", "arm64-apple-ios13.2"),
            arrayOf(KonanTarget.IOS_SIMULATOR_ARM64, "iOS", "arm64-apple-ios13.2-simulator"),
            arrayOf(KonanTarget.TVOS_ARM64, "tvOS", "arm64-apple-tvos13.2"),
            arrayOf(KonanTarget.TVOS_SIMULATOR_ARM64, "tvOS", "arm64-apple-tvos13.2-simulator"),
        )

        @JvmStatic
        fun androidAbis() = listOf(
            arrayOf(
                KonanTarget.ANDROID_X86,
                "x86",
                "/target-toolchain/bin/i686-linux-android21-clang",
            ),
            arrayOf(
                KonanTarget.ANDROID_X64,
                "x86_64",
                "/target-toolchain/bin/x86_64-linux-android21-clang",
            ),
            arrayOf(
                KonanTarget.ANDROID_ARM32,
                "armeabi-v7a",
                "/target-toolchain/bin/armv7a-linux-androideabi21-clang",
            ),
            arrayOf(
                KonanTarget.ANDROID_ARM64,
                "arm64-v8a",
                "/target-toolchain/bin/aarch64-linux-android21-clang",
            ),
        )

        val ANDROID_LINKER_FLAGS = listOf(
            "-lm",
            "-lc++_static",
            "-lc++abi",
            "-landroid",
            "-llog",
            "-latomic",
        )
    }
}
