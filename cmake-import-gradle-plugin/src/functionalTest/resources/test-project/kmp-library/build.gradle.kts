plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.technoirlab.cmake-import")
    id("maven-publish")
}

group = "com.example"
version = "1.0"

kotlin {
    linuxX64()
    macosArm64()
    mingwX64()
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
