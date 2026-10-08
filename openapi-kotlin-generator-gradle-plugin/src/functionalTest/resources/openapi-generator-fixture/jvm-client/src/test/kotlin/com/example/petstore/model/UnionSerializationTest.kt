package com.example.petstore.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Instant

class UnionSerializationTest {
    private val json = Json

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class References {
        @ParameterizedTest
        @MethodSource("references")
        fun `string and long alternatives preserve their JSON types and values`(input: String, value: Any) {
            val reference = PetReference(value)
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<PetReference>(input)
            val encoded = json.encodeToJsonElement(reference)

            assertThat(decoded).isEqualTo(reference)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @ParameterizedTest
        @MethodSource("references")
        fun `nullable union still supports every nonnull alternative`(input: String, value: Any) {
            val reference = OptionalPetReference(value)
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<OptionalPetReference>(input)
            val encoded = json.encodeToJsonElement(reference)

            assertThat(decoded).isEqualTo(reference)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @Test
        fun `nullable union reads and writes null`() {
            val reference = OptionalPetReference()

            val decoded = json.decodeFromString<OptionalPetReference>("null")
            val encoded = json.encodeToString(reference)

            assertThat(decoded.actualInstance).isNull()
            assertThat(encoded).isEqualTo("null")
        }

        @ParameterizedTest
        @ValueSource(strings = ["true", "1.5", "9223372036854775808", "-9223372036854775809", "[]", "{}"])
        fun `rejects JSON that matches neither string nor long`(input: String) {
            assertThatThrownBy { json.decodeFromString<PetReference>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
        }

        @Test
        fun `nonnullable union rejects null on read and write`() {
            val reference = PetReference()

            assertThatThrownBy { json.decodeFromString<PetReference>("null") }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("does not allow null")
            assertThatThrownBy { json.encodeToString(reference) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("does not allow null")
        }

        @ParameterizedTest
        @MethodSource("unsupportedValues")
        fun `rejects Kotlin values outside the declared alternatives`(value: Any) {
            val reference = PetReference(value)

            assertThatThrownBy { json.encodeToString(reference) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("Unsupported value")
        }

        private fun references() = listOf(
            arguments("\"\"", ""),
            arguments("\"42\"", "42"),
            arguments("\"true\"", "true"),
            arguments("42", 42L),
            arguments("4294967296", 4294967296L),
            arguments("-9223372036854775808", Long.MIN_VALUE),
            arguments("9223372036854775807", Long.MAX_VALUE),
        )

        private fun unsupportedValues() = listOf(
            arguments(true),
            arguments(42),
            arguments(1.5),
            arguments(listOf("Mochi")),
            arguments(PetSummary("Mochi")),
        )
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class ObjectAlternatives {
        @Test
        fun `oneOf accepts an object matching exactly one alternative`() {
            val profile = PetProfileChoice(PetSummary("Mochi"))
            val input = """{"name":"Mochi"}"""
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<PetProfileChoice>(input)
            val encoded = json.encodeToJsonElement(profile)

            assertThat(decoded).isEqualTo(profile)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @Test
        fun `oneOf rejects overlapping object alternatives on read and write`() {
            val input = """{"name":"Mochi","microchipId":"chip-123"}"""
            val profile = PetProfileChoice(RegisteredPet("Mochi", "chip-123"))

            assertThatThrownBy { json.decodeFromString<PetProfileChoice>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
            assertThatThrownBy { json.encodeToString(profile) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
        }

        @ParameterizedTest
        @MethodSource("profiles")
        fun `anyOf selects an alternative that retains every input field`(input: String, value: Any) {
            val profile = PetProfileMatch(value)
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<PetProfileMatch>(input)
            val encoded = json.encodeToJsonElement(profile)

            assertThat(decoded).isEqualTo(profile)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @Test
        fun `matching retains optional fields that hold their default values`() {
            val profile = PetProfileMatch(PetSummary("Mochi"))
            val input = """{"name":"Mochi","nickname":null}"""

            val decoded = json.decodeFromString<PetProfileMatch>(input)

            assertThat(decoded).isEqualTo(profile)
        }

        @Test
        fun `oneOf rejects fields that its only matching alternative would discard`() {
            val input = """{"name":"Mochi","unknown":true}"""

            assertThatThrownBy { json.decodeFromString<PetProfileChoice>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("losing fields")
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                """{"name":"Mochi","unknown":true}""",
                """{"name":"Mochi","microchipId":"chip-123","unknown":true}""",
                """{"name":"Mochi","microchipId":123}""",
            ],
        )
        fun `anyOf rejects fields that no matching alternative can retain`(input: String) {
            assertThatThrownBy { json.decodeFromString<PetProfileMatch>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("losing fields")
        }

        @ParameterizedTest
        @ValueSource(strings = ["{}", """{"microchipId":"chip-123"}""", """{"name":null}""", "\"Mochi\"", "[]"])
        fun `oneOf rejects objects with no matching alternative`(input: String) {
            assertThatThrownBy { json.decodeFromString<PetProfileChoice>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
        }

        @ParameterizedTest
        @ValueSource(strings = ["{}", """{"microchipId":"chip-123"}""", """{"name":null}""", "\"Mochi\"", "[]"])
        fun `anyOf rejects objects with no matching alternative`(input: String) {
            assertThatThrownBy { json.decodeFromString<PetProfileMatch>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("at least one")
        }

        private fun profiles() = listOf(
            arguments("""{"name":"Mochi"}""", PetSummary("Mochi")),
            arguments("""{"name":"Mochi","microchipId":"chip-123"}""", RegisteredPet("Mochi", "chip-123")),
        )
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Collections {
        @ParameterizedTest
        @MethodSource("lists")
        fun `lists serialize their elements without wrappers`(input: String, value: List<*>) {
            val pets = PetList(value)
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<PetList>(input)
            val encoded = json.encodeToJsonElement(pets)

            assertThat(decoded).isEqualTo(pets)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @Test
        fun `empty lists are ambiguous on read and write`() {
            val pets = PetList(emptyList<String>())

            assertThatThrownBy { json.decodeFromString<PetList>("[]") }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
            assertThatThrownBy { json.encodeToString(pets) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
        }

        @ParameterizedTest
        @ValueSource(strings = ["[42]", "[null]", """["Mochi",{"name":"Rex"}]""", "[{}]", "{}"])
        fun `lists reject invalid elements and mixed alternatives`(input: String) {
            assertThatThrownBy { json.decodeFromString<PetList>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
        }

        @Test
        fun `field preservation includes objects nested inside collections`() {
            val input = """[{"name":"Mochi"},{"name":"Rex","unknown":true}]"""

            assertThatThrownBy { json.decodeFromString<PetList>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("losing fields")
        }

        @ParameterizedTest
        @MethodSource("invalidLists")
        fun `list encoding checks erased element types`(value: List<*>) {
            val pets = PetList(value)

            assertThatThrownBy { json.encodeToString(pets) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("Unsupported value")
        }

        @ParameterizedTest
        @MethodSource("inventories")
        fun `inventory supports a note or a map of integer counts`(input: String, value: Any) {
            val inventory = PetInventory(value)
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<PetInventory>(input)
            val encoded = json.encodeToJsonElement(inventory)

            assertThat(decoded).isEqualTo(inventory)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @ParameterizedTest
        @ValueSource(strings = ["""{"cat":"2"}""", """{"cat":1.5}""", """{"cat":2147483648}""", """{"cat":null}""", "[]"])
        fun `inventory rejects values that are not integer counts`(input: String) {
            assertThatThrownBy { json.decodeFromString<PetInventory>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
        }

        @ParameterizedTest
        @MethodSource("invalidInventories")
        fun `map encoding checks erased key and value types without narrowing numbers`(value: Map<*, *>) {
            val inventory = PetInventory(value)

            assertThatThrownBy { json.encodeToString(inventory) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("Unsupported value")
        }

        private fun lists() = listOf(
            arguments("""["Mochi","42"]""", listOf("Mochi", "42")),
            arguments("""[{"name":"Mochi"},{"name":"Rex"}]""", listOf(PetSummary("Mochi"), PetSummary("Rex"))),
        )

        private fun invalidLists() = listOf(
            arguments(listOf(42)),
            arguments(listOf(null)),
            arguments(listOf("Mochi", PetSummary("Rex"))),
        )

        private fun inventories() = listOf(
            arguments("\"No pets available\"", "No pets available"),
            arguments("{}", emptyMap<String, Int>()),
            arguments("""{"cat":2,"dog":2147483647}""", mapOf("cat" to 2, "dog" to Int.MAX_VALUE)),
        )

        private fun invalidInventories() = listOf(
            arguments(mapOf("cat" to 2L)),
            arguments(mapOf("cat" to 4294967296L)),
            arguments(mapOf("cat" to "2")),
            arguments(mapOf("cat" to null)),
            arguments(mapOf(1 to 2)),
        )
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class FormattedAlternatives {
        @ParameterizedTest
        @MethodSource("arrivals")
        fun `arrival uses integer and instant serializers`(input: String, value: Any) {
            val arrival = PetArrival(value)
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<PetArrival>(input)
            val encoded = json.encodeToJsonElement(arrival)

            assertThat(decoded).isEqualTo(arrival)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @ParameterizedTest
        @ValueSource(strings = ["\"tomorrow\"", "\"3\"", "3.5", "2147483648", "true"])
        fun `arrival rejects invalid timestamps and noninteger day counts`(input: String) {
            assertThatThrownBy { json.decodeFromString<PetArrival>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("exactly one")
        }

        @Test
        fun `arrival requires an instant value rather than a timestamp string`() {
            val arrival = PetArrival("2026-09-26T12:00:00Z")

            assertThatThrownBy { json.encodeToString(arrival) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("Unsupported value")
        }

        @Test
        fun `URI union alternative is a string`() {
            val location = PetLocation("https://example.org/pets")
            val input = "\"https://example.org/pets\""
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<PetLocation>(input)
            val encoded = json.encodeToJsonElement(location)

            assertThat(decoded).isEqualTo(location)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        @Test
        fun `URI collection union alternative is a list of strings`() {
            val locations = PetLocation(listOf("https://example.org/pets"))
            val input = """["https://example.org/pets"]"""
            val expectedJson = json.parseToJsonElement(input)

            val decoded = json.decodeFromString<PetLocation>(input)
            val encoded = json.encodeToJsonElement(locations)

            assertThat(decoded).isEqualTo(locations)
            assertThat(encoded).isEqualTo(expectedJson)
        }

        private fun arrivals() = listOf(
            arguments("3", 3),
            arguments("\"2026-09-26T12:00:00Z\"", Instant.parse("2026-09-26T12:00:00Z")),
        )
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class PermissiveJson {
        private val permissiveJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        @ParameterizedTest
        @ValueSource(strings = ["""{"name":42}""", """{"name":true}""", """{"name":null}"""])
        fun `union matching rejects coerced object properties`(input: String) {
            assertThatThrownBy { permissiveJson.decodeFromString<PetProfileMatch>(input) }
                .isInstanceOf(SerializationException::class.java)
                .hasMessageContaining("at least one")
        }

        @Test
        fun `oneOf drops fields that its only matching alternative does not declare`() {
            val profile = PetProfileChoice(PetSummary("Mochi"))
            val input = """{"name":"Mochi","unknown":true}"""

            val decoded = permissiveJson.decodeFromString<PetProfileChoice>(input)
            val encoded = permissiveJson.encodeToString(decoded)

            assertThat(decoded).isEqualTo(profile)
            assertThat(encoded).isEqualTo("""{"name":"Mochi"}""")
        }

        @ParameterizedTest
        @MethodSource("profilesWithUnknownFields")
        fun `anyOf selects the matching alternative that retains the most fields`(input: String, value: Any) {
            val profile = PetProfileMatch(value)

            val decoded = permissiveJson.decodeFromString<PetProfileMatch>(input)

            assertThat(decoded).isEqualTo(profile)
        }

        @Test
        fun `objects nested inside collections drop unknown fields`() {
            val pets = PetList(listOf(PetSummary("Mochi"), PetSummary("Rex")))
            val input = """[{"name":"Mochi"},{"name":"Rex","unknown":true}]"""

            val decoded = permissiveJson.decodeFromString<PetList>(input)
            val encoded = permissiveJson.encodeToString(decoded)

            assertThat(decoded).isEqualTo(pets)
            assertThat(encoded).isEqualTo("""[{"name":"Mochi"},{"name":"Rex"}]""")
        }

        private fun profilesWithUnknownFields() = listOf(
            arguments("""{"name":"Mochi","unknown":true}""", PetSummary("Mochi")),
            arguments("""{"name":"Mochi","microchipId":"chip-123","unknown":true}""", RegisteredPet("Mochi", "chip-123")),
            arguments("""{"name":"Mochi","microchipId":123}""", PetSummary("Mochi")),
        )
    }
}
