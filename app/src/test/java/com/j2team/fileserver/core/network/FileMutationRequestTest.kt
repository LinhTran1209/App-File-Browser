package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.ServerProfile
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class FileMutationRequestTest {
    @Test
    fun authenticatedRequestsDoNotFollowRedirects() = runTest {
        val redirectedRequests = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                redirectedRequests.incrementAndGet()
                exchange.sendResponseHeaders(200, 0)
                exchange.close()
            }
            start()
        }
        val redirector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/stolen")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }
        try {
            val result = FileBrowserClient().list(profile(redirector), "secret-token", "/")

            assertFalse(result.isSuccess)
            assertEquals(0, redirectedRequests.get())
        } finally {
            redirector.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun cancellationBeforePublicationClosesTheLateRequestOwner() {
        var closed = 0
        val owner = CancellableRequestOwner()

        assertEquals(null, owner.cancel())
        assertFalse(owner.publish { closed += 1 })
        assertEquals(1, closed)
    }

    @Test
    fun downloadCancellationInterruptsBlockedReadAndRethrowsCancellation() = runTest {
        val firstByteCopied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                // A chunked body lets the client obtain an InputStream, then blocks its first read.
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                release.await(5, TimeUnit.SECONDS)
                exchange.close()
            }
            start()
        }
        try {
            val outcome = AtomicReference<Throwable?>()
            val completed = CountDownLatch(1)
            val download = launch(Dispatchers.Default) {
                try {
                    FileBrowserClient().downloadToResult(
                        profile = profile(server),
                        token = "token",
                        remotePath = "/blocked",
                        openDestination = { ByteArrayOutputStream() },
                        onProgress = { copied, _ -> if (copied > 0) firstByteCopied.countDown() },
                    )
                    outcome.set(null)
                } catch (error: Throwable) {
                    outcome.set(error)
                } finally {
                    completed.countDown()
                }
            }

            assertTrue("The client never started its second, blocked read", firstByteCopied.await(2, TimeUnit.SECONDS))
            val cancellationStarted = System.nanoTime()
            download.cancel()
            assertTrue("Cancellation waited for the peer", completed.await(2, TimeUnit.SECONDS))
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancellationStarted)
            assertTrue(outcome.get() is CancellationException)
            assertTrue("Cancellation waited ${elapsedMillis}ms for the peer", elapsedMillis < 2_000)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun previewProbeUsesAuthenticatedBoundedRangeAndPreservesContentType() = runTest {
        withServer { request ->
            assertEquals("GET", request.method)
            assertEquals("token", request.authHeader)
            assertEquals("bytes=0-65535", request.rangeHeader)
            206 to "preview text"
        }.let { server -> try {
            val probe = FileBrowserClient().previewProbeResult(profile(server), "token", "/notes").toResult().getOrThrow()

            assertEquals("text/plain", probe.mimeType)
            assertEquals("preview text", probe.sample.toString(Charsets.UTF_8))
        } finally { server.stop(0) } }
    }
    @Test
    fun currentUserPermissionsUseOfficialPermShapeAndSelfEndpoint() = runTest {
        withServer { request ->
            assertEquals("GET", request.method)
            assertEquals("/api/users/7", request.path)
            assertEquals(tokenForUser(7), request.authHeader)
            200 to """{"id":7,"username":"reader","perm":{"download":true,"create":true,"delete":false}}"""
        }.let { server -> try {
            val result = FileBrowserClient().currentPermissionsResult(profile(server), tokenForUser(7)).toResult()

            assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
            result.getOrThrow().let { permissions ->
                assertTrue(permissions.canDownload)
                assertTrue(permissions.canCreate)
                assertTrue(permissions.canUpload)
                assertFalse(permissions.canDelete)
            }
        } finally { server.stop(0) } }
    }

    @Test
    fun malformedPermissionShapeFailsClosed() = runTest {
        withServer { request ->
            assertEquals("/api/users/7", request.path)
            200 to """{"id":7,"perm":["download", "create", "delete"]}"""
        }.let { server -> try {
            val result = FileBrowserClient().currentPermissionsResult(profile(server), tokenForUser(7)).toResult()

            assertTrue(result.isSuccess)
            result.getOrThrow().let { permissions ->
                assertFalse(permissions.canDownload)
                assertFalse(permissions.canUpload)
                assertFalse(permissions.canCreate)
                assertFalse(permissions.canDelete)
            }
        } finally { server.stop(0) } }
    }

    @Test
    fun listEncodesEachPathSegmentExactlyOnce() = runTest {
        withServer { request ->
            assertEquals("GET", request.method)
            assertEquals("/api/resources/Media/50%25%20off/%23tag", request.path)
            403 to "forbidden"
        }.let { server -> try {
            val result = FileBrowserClient().list(profile(server), "token", "/Media/50% off/#tag")

            assertFalse(result.isSuccess)
        } finally { server.stop(0) } }
    }
    @Test
    fun createDirectoryPostsEncodedPathAndAuthHeader() = runTest {
        withServer { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/resources/Media/New%20Folder/", request.path)
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
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                val response = handle(Request(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestHeaders["X-Auth"]?.firstOrNull(), exchange.requestHeaders["Range"]?.firstOrNull()))
                exchange.sendResponseHeaders(response.first, response.second.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.second.toByteArray()) }
            }
            start()
        }

    private data class Request(val method: String, val path: String, val authHeader: String?, val rangeHeader: String?)

    private fun tokenForUser(id: Int): String = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"user\":{\"id\":$id}}".toByteArray()) + ".signature"
}
