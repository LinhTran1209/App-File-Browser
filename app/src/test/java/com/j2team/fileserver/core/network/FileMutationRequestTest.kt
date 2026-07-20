package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.ServerProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

class FileMutationRequestTest {
    @Test
    fun createDirectoryPostsEncodedPathAndAuthHeader() = runTest {
        withServer { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/resources/Media/New%20Folder", request.path)
            assertEquals("token", request.authHeader)
            201 to ""
        }.let { server -> try {
            val result = FileBrowserClient().createDirectory(profile(server), "token", "/Media/New Folder")

            assertTrue(result.isSuccess)
        } finally { server.stop(0) } }
    }

    @Test
    fun deleteStopsAtFirstRejectedPathAndKeepsSegmentsEncodedOnce() = runTest {
        var requests = 0
        withServer { request ->
            requests += 1
            when (requests) {
                1 -> {
                    assertEquals("DELETE", request.method)
                    assertEquals("/api/resources/Media/one%20file.txt", request.path)
                    204 to ""
                }
                2 -> {
                    assertEquals("/api/resources/Media/rejected.txt", request.path)
                    403 to "not allowed"
                }
                else -> error("Delete must stop after a rejected resource")
            }
        }.let { server -> try {
            val result = FileBrowserClient().delete(
                profile(server),
                "token",
                listOf("/Media/one file.txt", "/Media/rejected.txt", "/Media/not-requested.txt"),
            )

            assertFalse(result.isSuccess)
            assertEquals(2, requests)
        } finally { server.stop(0) } }
    }

    private fun profile(server: HttpServer) = ServerProfile(
        id = "test",
        displayName = "Test",
        scheme = "http",
        host = "127.0.0.1",
        port = server.address.port,
        basePath = "/",
    )

    private fun withServer(handle: (Request) -> Pair<Int, String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val response = handle(Request(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestHeaders["X-Auth"]?.firstOrNull()))
                exchange.sendResponseHeaders(response.first, response.second.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.second.toByteArray()) }
            }
            start()
        }

    private data class Request(val method: String, val path: String, val authHeader: String?)
}
