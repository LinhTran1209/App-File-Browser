package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.ServerProfile
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAuthenticationRequestTest {
    @Test fun syncCredentialUsesRestrictedHeader() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/resources/recursive/Photos") { exchange ->
            assertEquals("device-secret", exchange.requestHeaders.getFirst("X-Sync-Token"))
            assertEquals(null, exchange.requestHeaders.getFirst("X-Auth"))
            val body = "[]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val result = FileBrowserClient().recursiveListResult(profile(server), "sync:device-secret", "/Photos")
            assertTrue(result.toResult().isSuccess)
        } finally { server.stop(0) }
    }

    @Test fun issuingSyncCredentialUsesUiSession() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/sync/token") { exchange ->
            assertEquals("ui-jwt", exchange.requestHeaders.getFirst("X-Auth"))
            val body = "{\"token\":\"issued-token\"}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            assertEquals("issued-token", FileBrowserClient().createSyncTokenResult(profile(server), "ui-jwt", "/Photos").toResult().getOrThrow())
        } finally { server.stop(0) }
    }

    private fun profile(server: HttpServer) = ServerProfile("sync-test", "Sync", "http", "127.0.0.1", server.address.port)
}
