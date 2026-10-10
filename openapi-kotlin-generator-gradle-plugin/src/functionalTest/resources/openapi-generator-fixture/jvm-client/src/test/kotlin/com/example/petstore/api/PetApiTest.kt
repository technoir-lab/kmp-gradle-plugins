package com.example.petstore.api

import com.example.petstore.model.AddPetNoteRequestMentions
import com.example.petstore.model.Cat
import com.example.petstore.model.Dog
import com.example.petstore.model.ListShelterPetsAgesParameter
import com.example.petstore.model.ListShelterPetsShelterParameter
import com.example.petstore.model.ListShelterPetsSpeciesParameter
import com.example.petstore.model.Pet
import com.example.petstore.model.PetReference
import com.example.petstore.model.Status
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PetApiTest {
    private val json = Json
    private lateinit var responseBody: String
    private lateinit var engine: MockEngine
    private lateinit var httpClient: HttpClient
    private lateinit var petApi: PetApi

    @BeforeEach
    fun setUp() {
        engine = MockEngine {
            respond(responseBody, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        httpClient = HttpClient(engine) {
            defaultRequest {
                url("https://petstore.example")
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
        petApi = PetApi(httpClient)
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        engine.close()
    }

    @ParameterizedTest
    @MethodSource("pets")
    fun `getPetById sends parameters and decodes the pet subtype`(input: String, pet: Pet) = runTest {
        responseBody = input
        val petId = 4294967296L

        val result = petApi.getPetById(petId, true, "request-123", "session-123")

        assertThat(result).isEqualTo(pet)
        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.method).isEqualTo(HttpMethod.Get)
        assertThat(request.url).isEqualTo(Url("https://petstore.example/pets/$petId?includeDetails=true"))
        assertThat(request.headers[HttpHeaders.Accept]).isEqualTo("application/json")
        assertThat(request.headers["X-Request-ID"]).isEqualTo("request-123")
        assertThat(request.headers[HttpHeaders.Cookie]).isEqualTo("sessionId=session-123")
    }

    @Test
    fun `getPetById omits optional parameters when absent`() = runTest {
        responseBody = """{"species":"cat","id":1,"name":"Mochi","livesRemaining":9}"""

        val result = petApi.getPetById(1L)

        assertThat(result).isEqualTo(Cat(1L, "Mochi", 9))
        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.url).isEqualTo(Url("https://petstore.example/pets/1"))
        assertThat(request.headers["X-Request-ID"]).isNull()
        assertThat(request.headers[HttpHeaders.Cookie]).isNull()
    }

    @Test
    fun `listPets sends the default page size`() = runTest {
        responseBody = "[]"

        val result = petApi.listPets()

        assertThat(result).isEmpty()
        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.url).isEqualTo(Url("https://petstore.example/pets?pageSize=20"))
    }

    @Test
    fun `listOwnerPets sends string parameters as text`() = runTest {
        responseBody = "[]"

        val result = petApi.listOwnerPets("Ann Lee", "Mo", listOf("friendly", "shy"))

        assertThat(result).isEmpty()
        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.url).isEqualTo(Url("https://petstore.example/owners/Ann%20Lee/pets?nickname=Mo&tags=friendly&tags=shy"))
    }

    @ParameterizedTest
    @MethodSource("shelters")
    fun `listShelterPets sends a union path parameter as its value`(shelter: Any, path: String) = runTest {
        responseBody = "[]"

        val result = petApi.listShelterPets(ListShelterPetsShelterParameter(shelter))

        assertThat(result).isEmpty()
        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.url).isEqualTo(Url("https://petstore.example$path"))
    }

    @Test
    fun `listShelterPets sends union query parameters as their values`() = runTest {
        responseBody = "[]"

        petApi.listShelterPets(
            shelter = ListShelterPetsShelterParameter("Happy Paws"),
            after = PetReference("Mochi"),
            species = ListShelterPetsSpeciesParameter("cat"),
            exclude = listOf(PetReference(4294967296L), PetReference("Rex")),
            status = Status.AVAILABLE,
        )

        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.url.parameters.entries()).containsExactly(
            entry("after", listOf("Mochi")),
            entry("species", listOf("cat")),
            entry("exclude", listOf("4294967296", "Rex")),
            entry("status", listOf("available")),
        )
    }

    @Test
    fun `listShelterPets sends union header and cookie parameters as their values`() = runTest {
        responseBody = "[]"

        petApi.listShelterPets(
            shelter = ListShelterPetsShelterParameter(1L),
            xReferringPet = PetReference(4294967296L),
            lastPet = PetReference("Momo"),
        )

        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.headers["X-Referring-Pet"]).isEqualTo("4294967296")
        assertThat(request.headers[HttpHeaders.Cookie]).isEqualTo("lastPet=Momo")
    }

    @Test
    fun `listShelterPets sends the list alternative of a union parameter as repeated fields`() = runTest {
        responseBody = "[]"

        petApi.listShelterPets(ListShelterPetsShelterParameter(1L), species = ListShelterPetsSpeciesParameter(listOf("cat", "dog")))

        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.url.parameters.getAll("species")).containsExactly("cat", "dog")
    }

    @Test
    fun `listShelterPets sends the list alternative of a union parameter that disables explode as comma-separated items`() = runTest {
        responseBody = "[]"

        petApi.listShelterPets(ListShelterPetsShelterParameter(1L), ages = ListShelterPetsAgesParameter(listOf(1L, 2L)))

        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.url.parameters.getAll("ages")).containsExactly("1,2")
    }

    @Test
    fun `addPetNote sends a URL-encoded form`() = runTest {
        responseBody = ""

        petApi.addPetNote(
            petId = 1L,
            text = "Likes naps",
            tags = listOf("calm", "quiet"),
            priority = 2,
            relatedPet = PetReference(4294967296L),
            mentions = AddPetNoteRequestMentions(listOf("Mochi", "Rex")),
        )

        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.body.contentType?.withoutParameters()).isEqualTo(ContentType.Application.FormUrlEncoded)
        assertThat(request.body.toByteArray().decodeToString())
            .isEqualTo("text=Likes+naps&tags=calm&tags=quiet&priority=2&relatedPet=4294967296&mentions=Mochi&mentions=Rex")
    }

    @Test
    fun `updatePetProfile sends a multipart form`() = runTest {
        responseBody = ""

        petApi.updatePetProfile(1L, "Sleeps a lot", listOf("Mo", "Momo"), 3, PetReference("Rex"))

        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.method).isEqualTo(HttpMethod.Put)
        assertThat(request.body.contentType?.withoutParameters()).isEqualTo(ContentType.MultiPart.FormData)
        val parts = MULTIPART_PART.findAll(request.body.toByteArray().decodeToString())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertThat(parts).containsExactly(
            "bio" to "Sleeps a lot",
            "nicknames" to "Mo",
            "nicknames" to "Momo",
            "age" to "3",
            "bestFriend" to "Rex",
        )
    }

    @ParameterizedTest
    @MethodSource("pets")
    fun `addPet sends the pet subtype as JSON and decodes the response`(input: String, pet: Pet) = runTest {
        responseBody = input
        val expectedJson = json.parseToJsonElement(input)

        val result = petApi.addPet(pet)

        assertThat(result).isEqualTo(pet)
        assertThat(engine.requestHistory).hasSize(1)
        val request = engine.requestHistory.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url).isEqualTo(Url("https://petstore.example/pets"))
        assertThat(request.headers[HttpHeaders.Accept]).isEqualTo("application/json")
        assertThat(request.body.contentType?.withoutParameters()).isEqualTo(ContentType.Application.Json)
        assertThat(json.parseToJsonElement(request.body.toByteArray().decodeToString())).isEqualTo(expectedJson)
    }

    private fun pets() = listOf(
        arguments("""{"species":"cat","id":4294967296,"name":"Mochi","livesRemaining":9}""", Cat(4294967296L, "Mochi", 9)),
        arguments("""{"species":"dog","id":4294967296,"name":"Rex","barkVolume":3}""", Dog(4294967296L, "Rex", 3)),
    )

    private fun shelters() = listOf(
        arguments(4294967296L, "/shelters/4294967296/pets"),
        arguments("Happy Paws", "/shelters/Happy%20Paws/pets"),
    )

    private companion object {
        private val MULTIPART_PART = Regex("name=\"([^\"]+)\"\r\n(?:.+\r\n)*\r\n(.*)\r\n")
    }
}
