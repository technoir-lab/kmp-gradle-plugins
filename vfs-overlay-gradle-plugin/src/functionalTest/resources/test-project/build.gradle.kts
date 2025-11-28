plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.technoirlab.vfs-overlay")
}

kotlin {
    androidNativeArm64 {
        compilations.named("main") {
            cinterops.register("sample") {
                packageName = "com.example.kmp"
            }
        }
    }
}
