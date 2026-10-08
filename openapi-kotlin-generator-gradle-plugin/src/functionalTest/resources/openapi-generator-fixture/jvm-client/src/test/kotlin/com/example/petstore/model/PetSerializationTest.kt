package com.example.petstore.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PetSerializationTest {
    private val json = Json

    @Nested
    inner class Models {
        @Test
        fun `standalone enum uses the schema value`() {
            val decoded = json.decodeFromString<Status>("\"available\"")
            val encoded = json.encodeToString(Status.AVAILABLE)

            assertThat(decoded).isEqualTo(Status.AVAILABLE)
            assertThat(encoded).isEqualTo("\"available\"")
        }
    }

    @Nested
    inner class DiscriminatorUnions {
        @Test
        fun `cat uses the mapped discriminator without a wrapper`() {
            val pet = Cat(1L, "Mochi", 9)
            val input = """{"species":"cat","id":1,"name":"Mochi","livesRemaining":9}"""
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<Pet>(input)
            val encoded = json.encodeToJsonElement<Pet>(pet)

            assertThat(decoded).isEqualTo(pet)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @Test
        fun `dog uses the mapped discriminator and preserves a long identifier`() {
            val pet = Dog(4294967296L, "Rex", 3)
            val input = """{"species":"dog","id":4294967296,"name":"Rex","barkVolume":3}"""
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<Pet>(input)
            val encoded = json.encodeToJsonElement<Pet>(pet)

            assertThat(decoded).isEqualTo(pet)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @Test
        fun `dispatch follows the discriminator when subtype fields overlap`() {
            val permissiveJson = Json { ignoreUnknownKeys = true }
            val input = """{"species":"cat","id":1,"name":"Mochi","livesRemaining":9,"barkVolume":3}"""

            val decoded = permissiveJson.decodeFromString<Pet>(input)

            assertThat(decoded).isEqualTo(Cat(1L, "Mochi", 9))
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                """{"id":1,"name":"Mochi","livesRemaining":9}""",
                """{"species":"rabbit","id":1,"name":"Mochi","livesRemaining":9}""",
                """{"species":null,"id":1,"name":"Mochi","livesRemaining":9}""",
                """{"species":"cat","id":1,"name":"Mochi"}""",
                """{"species":"dog","id":1,"name":"Mochi","livesRemaining":9}""",
                "null",
            ],
        )
        fun `rejects missing or invalid discriminators and invalid subtype payloads`(input: String) {
            assertThatThrownBy { json.decodeFromString<Pet>(input) }
                .isInstanceOf(SerializationException::class.java)
        }
    }
}
