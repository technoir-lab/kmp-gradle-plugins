plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.technoirlab.cmake-import")
    id("maven-publish")
}

group = "com.example"
version = "1.0"

val hostTargetName = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "macosArm64"

    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64") -> "linuxX64"

    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64") -> "mingwX64"

    else -> error("No exact-host fixture target is available")
}

kotlin {
    linuxX64()
    macosArm64()
    mingwX64()

    sourceSets.named("${hostTargetName}Main") {
        kotlin.srcDir("src/hostMain/kotlin")
    }
}

cmakeImport {
    sourceDirectory = layout.projectDirectory.dir("../cmake")
    targetName = "hello"
    packageName = "kmp.library.cmake"
}

publishing {
    repositories {
        maven {
            name = "test"
            url = uri(layout.projectDirectory.dir("../build/repo"))
        }
    }
}
