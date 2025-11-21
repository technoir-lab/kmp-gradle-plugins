package io.technoirlab.vfsoverlay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class VfsOverlayJson(
    val version: Int = 0,
    val roots: List<VfsOverlayRootJson>
)

@Serializable
internal data class VfsOverlayRootJson(
    val name: String,
    val type: String,
    @SerialName("external-contents")
    val externalContents: String
)
