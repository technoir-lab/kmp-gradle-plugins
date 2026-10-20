package io.technoirlab.device.run.core.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes a [GcsUri] as its `gs://<bucket>/<object name>` string.
 */
internal class GcsUriSerializer : KSerializer<GcsUri> {
    override val descriptor = PrimitiveSerialDescriptor("io.technoirlab.device.run.core.storage.GcsUri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: GcsUri) {
        encoder.encodeString(value.toString())
    }

    /**
     * @throws IllegalArgumentException when the string does not name a Cloud Storage object.
     */
    override fun deserialize(decoder: Decoder): GcsUri = GcsUri.parse(decoder.decodeString())
}
