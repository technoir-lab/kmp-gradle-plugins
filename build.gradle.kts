plugins {
    id("io.technoirlab.conventions.root")
}

dependencies {
    nmcpAggregation(project(":cmake-import-gradle-plugin"))
    nmcpAggregation(project(":vfs-overlay-gradle-plugin"))
}
