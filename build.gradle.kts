plugins {
    id("io.technoirlab.conventions.root")
}

dependencies {
    dokka(project(":cmake-import-gradle-plugin"))
    dokka(project(":vfs-overlay-gradle-plugin"))

    nmcpAggregation(project(":cmake-import-gradle-plugin"))
    nmcpAggregation(project(":vfs-overlay-gradle-plugin"))
}
