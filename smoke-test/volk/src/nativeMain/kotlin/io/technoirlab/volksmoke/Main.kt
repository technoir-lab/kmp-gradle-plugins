package io.technoirlab.volksmoke

import volk.VK_SUCCESS
import volk.volkInitialize

fun main() {
    val result = volkInitialize()
    check(result == VK_SUCCESS) { "volkInitialize failed with VkResult=$result" }
    println("volkInitialize succeeded (VkResult=$result)")
}
