package io.technoirlab.device.run.gradle

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * OAuth token endpoint that records the refresh token of each request and rejects it, so API requests fail before they are sent.
 */
internal class RejectingTokenServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val refreshTokens = ConcurrentLinkedQueue<String>()

    @Suppress("HttpUrlsUsage")
    val tokenUri = URI("http://${server.address.hostString}:${server.address.port}/token")

    init {
        server.createContext("/token", ::reject)
        server.start()
    }

    /**
     * Returns the refresh tokens received since the previous call.
     */
    fun takeRefreshTokens(): List<String> = generateSequence { refreshTokens.poll() }.toList()

    override fun close() {
        server.stop(0)
    }

    private fun reject(exchange: HttpExchange) {
        exchange.use {
            val form = exchange.requestBody.readAllBytes().decodeToString()
            form.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.first() == "refresh_token" }
                ?.let { refreshTokens.add(URLDecoder.decode(it.last(), Charsets.UTF_8)) }
            val response = """{"error": "invalid_grant", "error_description": "Token has been revoked."}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, response.size.toLong())
            exchange.responseBody.write(response)
        }
    }
}
