package io.technoirlab.device.run.core.client

import com.google.api.client.json.GenericJson
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import org.intellij.lang.annotations.Language
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.toPath
import kotlin.io.path.walk

/**
 * Returns the text of a fixture under `src/testFixtures/resources/device-run`.
 */
internal fun deviceRunFixture(path: String): String = deviceRunFixtureUrl(path).readText()

/**
 * Returns the texts of the fixtures below [directory] of `src/testFixtures/resources/device-run`, by their path relative to it.
 */
internal fun deviceRunFixtures(directory: String): Map<String, String> {
    val uri = deviceRunFixtureUrl(directory).toURI()
    // Gradle puts the fixtures JAR on test classpaths, and IDEs often the resources directory.
    if (uri.scheme != "jar") {
        return readFiles(uri.toPath())
    }
    return FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { readFiles(it.provider().getPath(uri)) }
}

/**
 * Returns a Device Run fixture parsed as the client model [T].
 */
internal inline fun <reified T : GenericJson> parseDeviceRunFixture(path: String): T = parseDeviceRunFixture(path, T::class.java)

/**
 * Returns a Device Run fixture parsed as the client model [type].
 */
internal fun <T : GenericJson> parseDeviceRunFixture(path: String, type: Class<T>): T =
    GsonFactory.getDefaultInstance().fromString(deviceRunFixture(path), type)

/**
 * Queues a Device Run fixture as the response to the next request.
 */
internal fun RecordingHttpTransport.enqueueFixture(path: String, statusCode: Int = 200) {
    responses.add(MockLowLevelHttpResponse().setStatusCode(statusCode).setContent(deviceRunFixture(path)))
}

/**
 * Queues [response], by default a successful one, with the client model [content] as its JSON body as the response to the next
 * request.
 */
internal fun RecordingHttpTransport.enqueueJson(content: GenericJson, response: MockLowLevelHttpResponse = MockLowLevelHttpResponse()) {
    responses.add(response.setContent(GsonFactory.getDefaultInstance().toString(content)))
}

/**
 * Returns JSON [text] pretty-printed with sorted keys, so that equal documents compare equal regardless of key order.
 */
internal fun canonicalJson(
    @Language("json")
    text: String,
): String {
    val jsonFactory = GsonFactory.getDefaultInstance()
    return jsonFactory.toPrettyString(jsonFactory.fromString(text, GenericJson::class.java).sortedKeys())
}

/**
 * Returns the client model [content] as JSON pretty-printed with sorted keys, so that equal documents compare equal regardless of
 * key order.
 */
internal fun canonicalJson(content: GenericJson): String = canonicalJson(GsonFactory.getDefaultInstance().toString(content))

private fun deviceRunFixtureUrl(path: String): URL =
    checkNotNull(RecordingHttpTransport::class.java.getResource("/device-run/$path")) { "Missing Device Run fixture $path" }

@OptIn(ExperimentalPathApi::class)
private fun readFiles(directory: Path): Map<String, String> = directory.walk()
    .filter { it.isRegularFile() }
    .associate { it.relativeTo(directory).invariantSeparatorsPathString to it.readText() }

private fun Any?.sortedKeys(): Any? = when (this) {
    is Map<*, *> -> entries.associate { (key, value) -> key.toString() to value.sortedKeys() }.toSortedMap()
    is List<*> -> map { it.sortedKeys() }
    else -> this
}
