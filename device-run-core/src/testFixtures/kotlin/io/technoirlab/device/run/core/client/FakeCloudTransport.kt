package io.technoirlab.device.run.core.client

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.devicerun.v1alpha.model.CatalogListSoftwareVersionsResponse
import com.google.api.services.devicerun.v1alpha.model.CatalogSoftwareVersion
import com.google.api.services.devicerun.v1alpha.model.GoogleLongrunningOperation
import com.google.api.services.devicerun.v1alpha.model.Result
import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.devicerun.v1alpha.model.SessionReport
import com.google.api.services.devicerun.v1alpha.model.Status
import com.google.api.services.storage.model.Objects
import com.google.api.services.storage.model.StorageObject
import io.technoirlab.device.run.core.storage.GcsUri
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers the Device Run and Cloud Storage requests of test runs from memory, like a service that finishes each session as soon as
 * it is created. Requests that a test run does not send fail the test.
 *
 * A created session finishes with the report that [sessionReport] returns for its output directory, and Device Run uploads
 * [sessionOutputs] below that directory: at once, or after the number of listings that [outputUploadListings] sets, as when the
 * outputs are listed before Device Run has uploaded them. Listings return the objects in name order, in pages of at most
 * [listingPageSize] objects. Cloud Storage rejects requests for objects below [deniedPrefixes] with `403 PERMISSION_DENIED`.
 */
internal class FakeCloudTransport : MockHttpTransport() {
    private val json = GsonFactory.getDefaultInstance()
    private val objects = mutableMapOf<GcsUri, String>()
    private val pendingUploads = mutableMapOf<GcsUri, PendingUpload>()
    private val reports = mutableMapOf<String, SessionReport>()
    private var listings = 0

    /**
     * The requests received, in the order they arrived.
     */
    val requests = CopyOnWriteArrayList<Request>()

    /**
     * The sessions created, in the order they were created, named as Device Run names them.
     */
    val sessions = CopyOnWriteArrayList<Session>()

    var orchestratorVersions: List<String> = emptyList()
    var sessionOutputs: Map<String, String> = emptyMap()

    /**
     * The number of listings of any objects after which Device Run uploads each output of [sessionOutputs] that it names; the
     * other outputs exist when the session is created.
     */
    var outputUploadListings: Map<String, Int> = emptyMap()
    var sessionReport: (sessionDirectory: String) -> SessionReport = { SessionReport().setResult(Result().setResultType("PASSED")) }
    var listingPageSize = Int.MAX_VALUE
    val deniedPrefixes = mutableSetOf<String>()

    /**
     * Receives each request before it is answered, outside the lock that answers requests one at a time, so that a test can hold
     * a request back while others are answered.
     */
    var onRequest: (Request) -> Unit = {}

    /**
     * Returns the content of the object at the `gs://` [uri], or null when there is no such object.
     */
    fun objectContent(uri: String): String? = synchronized(this) { objects[GcsUri.parse(uri)] }

    /**
     * Uploads [content] to the object at [uri] once [afterListings] more listings of any objects have been answered.
     */
    fun upload(uri: GcsUri, content: String, afterListings: Int = 0) {
        synchronized(this) {
            if (afterListings > 0) {
                pendingUploads[uri] = PendingUpload(content, listings + afterListings)
            } else {
                objects[uri] = content
            }
        }
    }

    /**
     * Deletes the object at [uri].
     */
    fun delete(uri: GcsUri) {
        synchronized(this) { objects.remove(uri) }
    }

    override fun buildRequest(method: String, url: String): LowLevelHttpRequest = object : MockLowLevelHttpRequest(url) {
        override fun execute(): LowLevelHttpResponse {
            val genericUrl = GenericUrl(url)
            val request = Request(method, genericUrl.host, genericUrl.rawPath)
            requests += request
            onRequest(request)
            val response = synchronized(this@FakeCloudTransport) {
                when (genericUrl.host) {
                    DEVICE_RUN_HOST -> deviceRun(method, genericUrl, contentAsString)
                    STORAGE_HOST -> storage(method, genericUrl, contentAsString)
                    else -> unexpected(method, genericUrl)
                }
            }
            return response.apply { if (contentType == null) setContentType(JSON_CONTENT_TYPE) }
        }
    }

    private fun deviceRun(method: String, url: GenericUrl, body: String): MockLowLevelHttpResponse {
        val name = url.rawPath.removePrefix("/v1alpha/")
        return when (method) {
            "POST" if name.endsWith(SESSIONS) -> createSession(name.removeSuffix(SESSIONS), url, body)
            "GET" if name.substringBeforeLast('/').endsWith(SESSIONS) -> session(name, url.getFirst("view") as String)
            "GET" if name.endsWith("/softwareVersions") -> softwareVersions()
            else -> unexpected(method, url)
        }
    }

    private fun createSession(parent: String, url: GenericUrl, body: String): MockLowLevelHttpResponse {
        val sessionId = url.getFirst("sessionId") as String
        val session = json.fromString(body, Session::class.java).setName("$parent$SESSIONS/$sessionId")
        val sessionDirectory = "${session.sessionConfig.outputDirectoryConfig.gcsOutputDirectory.path}/$sessionId"
        val directory = GcsUri.parse(sessionDirectory)
        for ((relativePath, content) in sessionOutputs) {
            upload(directory / relativePath, content, outputUploadListings[relativePath] ?: 0)
        }
        sessions += session
        reports[session.name] = sessionReport(sessionDirectory)
        return jsonResponse(GoogleLongrunningOperation().setName("$parent/operations/create-$sessionId").setDone(false))
    }

    private fun session(name: String, view: String): MockLowLevelHttpResponse {
        val session = sessions.find { it.name == name } ?: return errorResponse(404, "Session not found", "NOT_FOUND")
        val status = Status().setStatusType("DONE")
        return when (view) {
            "SESSION_VIEW_BASIC" -> jsonResponse(Session().setName(name).setSessionReport(SessionReport().setStatus(status)))
            "SESSION_VIEW_FULL" -> jsonResponse(
                Session().setName(name).setSessionConfig(session.sessionConfig).setSessionReport(reports.getValue(name).setStatus(status)),
            )
            else -> throw AssertionError("Unexpected session view $view")
        }
    }

    private fun softwareVersions(): MockLowLevelHttpResponse {
        val versions = orchestratorVersions.map { CatalogSoftwareVersion().setVersion(it) }
        return jsonResponse(CatalogListSoftwareVersionsResponse().setSoftwareVersions(versions))
    }

    private fun storage(method: String, url: GenericUrl, body: String): MockLowLevelHttpResponse {
        // Requests address /storage/v1/b/<bucket>/o[/<object>], and uploads the same path below /upload with the object name as a
        // parameter. Path parts are decoded, so an object name is one part.
        val upload = url.pathParts[1] == "upload"
        val parts = url.pathParts.drop(if (upload) 2 else 1)
        val bucket = parts[3]
        val objectName = parts.getOrNull(5) ?: url.getFirst(if (upload) "name" else "prefix") as String? ?: ""
        if (deniedPrefixes.any { objectName.startsWith(it) }) {
            return errorResponse(403, "Permission denied", "PERMISSION_DENIED")
        }
        return when (method) {
            "GET" if parts.size == 6 -> objects[GcsUri(bucket, objectName)]?.let { content ->
                if (url.getFirst("alt") == "media") mediaResponse(content) else jsonResponse(storageObject(bucket, objectName, content))
            } ?: errorResponse(404, "No such object", "NOT_FOUND")
            "GET" if parts.size == 5 -> jsonResponse(listing(bucket, objectName, url.getFirst("pageToken") as String?))
            // The upload session continues at the URL that started it.
            "POST" if upload -> MockLowLevelHttpResponse().addHeader("Location", url.build())
            "PUT" if upload -> {
                objects[GcsUri(bucket, objectName)] = body
                jsonResponse(storageObject(bucket, objectName, body))
            }
            else -> unexpected(method, url)
        }
    }

    /**
     * Returns the page of the listing of the objects below [prefix] that [pageToken] selects, or else its first page, which first
     * completes the uploads that earlier listings preceded.
     */
    private fun listing(bucket: String, prefix: String, pageToken: String?): Objects {
        if (pageToken == null) {
            listings++
            val completed = pendingUploads.filterValues { it.afterListings < listings }
            completed.forEach { (uri, upload) -> objects[uri] = upload.content }
            pendingUploads.keys -= completed.keys
        }
        val names = objects.keys.filter { it.bucket == bucket && it.objectName.startsWith(prefix) }.map { it.objectName }.sorted()
        val start = pageToken?.toInt() ?: 0
        val page = names.drop(start).take(listingPageSize)
        val end = start + page.size
        return Objects()
            .setItems(page.map { StorageObject().setName(it) })
            .setNextPageToken(end.takeIf { it < names.size }?.toString())
    }

    private fun storageObject(bucket: String, objectName: String, content: String): StorageObject = StorageObject()
        .setBucket(bucket)
        .setName(objectName)
        .setSize(BigInteger.valueOf(content.encodeToByteArray().size.toLong()))

    private fun jsonResponse(content: Any): MockLowLevelHttpResponse = MockLowLevelHttpResponse().setContent(json.toString(content))

    private fun mediaResponse(content: String): MockLowLevelHttpResponse = MockLowLevelHttpResponse()
        .setContentType("application/octet-stream")
        .setContent(content)
        .setContentLength(content.encodeToByteArray().size.toLong())

    private fun unexpected(method: String, url: GenericUrl): Nothing = throw AssertionError("Unexpected request $method $url")

    /**
     * A request received by the transport.
     */
    data class Request(
        val method: String,
        val host: String,
        val path: String,
    )

    /**
     * The [content] of an object that is uploaded when the listing that follows listing number [afterListings] begins.
     */
    private data class PendingUpload(
        val content: String,
        val afterListings: Int,
    )

    companion object {
        const val DEVICE_RUN_HOST = "devicerun.googleapis.com"
        const val STORAGE_HOST = "storage.googleapis.com"

        private const val JSON_CONTENT_TYPE = "application/json"
        private const val SESSIONS = "/sessions"
    }
}
