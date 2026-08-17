package kmp.library

import kotlinx.cinterop.ExperimentalForeignApi
import kmp.library.cmake.hello as nativeHello

@OptIn(ExperimentalForeignApi::class)
fun helloFromCMake() {
    nativeHello()
}
