package kmp.application

import kotlinx.cinterop.ExperimentalForeignApi
import kmp.application.cmake.hello as nativeHello

@OptIn(ExperimentalForeignApi::class)
fun main() {
    nativeHello()
}
