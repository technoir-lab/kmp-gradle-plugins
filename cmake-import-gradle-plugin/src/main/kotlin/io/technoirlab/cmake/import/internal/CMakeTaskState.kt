package io.technoirlab.cmake.import.internal

import kotlinx.serialization.Serializable

@Serializable
internal data class CMakeTaskState(
    val version: Int,
    val executionId: String,
    val resetIdentity: String?,
)
