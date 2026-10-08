package com.example.petstore.api

import com.example.petstore.model.Cat
import com.example.petstore.model.Dog
import com.example.petstore.model.Pet
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
}
