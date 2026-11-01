package io.technoirlab.cmake.import

class HostInfo(
    val name: String = System.getProperty("os.name"),
    val arch: String = System.getProperty("os.arch"),
) {
    val isLinux: Boolean
        get() = name.startsWith("Linux", ignoreCase = true)

    val isMac: Boolean
        get() = name.startsWith("Mac", ignoreCase = true)

    val isWindows: Boolean
        get() = name.startsWith("Windows", ignoreCase = true)

    val isArm64: Boolean
        get() = arch in setOf("aarch64", "arm64")

    val isX64: Boolean
        get() = arch in setOf("amd64", "x86_64")
}
