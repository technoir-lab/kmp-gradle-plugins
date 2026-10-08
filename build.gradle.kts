plugins {
    id("io.technoirlab.conventions.root")
}

dependencies {
    dokka(project(":cmake-import-gradle-plugin"))
    dokka(project(":kotlin-native-utils"))
    dokka(project(":openapi-kotlin-generator-gradle-plugin"))
    dokka(project(":vfs-overlay-gradle-plugin"))

    nmcpAggregation(project(":cmake-import-gradle-plugin"))
    nmcpAggregation(project(":kotlin-native-utils"))
    nmcpAggregation(project(":openapi-kotlin-generator-gradle-plugin"))
    nmcpAggregation(project(":vfs-overlay-gradle-plugin"))
}
