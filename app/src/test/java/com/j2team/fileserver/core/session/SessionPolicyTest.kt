package com.j2team.fileserver.core.session

import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.model.ServerUser
import com.j2team.fileserver.core.network.FileBrowserClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {
    @Test
    fun rejectedTokenRelogsInExactlyOnce() = runTest {
        val transport = FakeTransport(responses = mutableListOf(401, 200, 200))
        val policy = SessionPolicy(transport)

        assertTrue(policy.execute("old-token") { transport.request(it) }.isSuccess)
        assertEquals(1, transport.loginCalls)
        assertEquals(2, transport.requestCalls)
    }

    @Test
    fun forbiddenTokenRelogsInExactlyOnce() = runTest {
        val transport = FakeTransport(responses = mutableListOf(403, 200, 200))
        val policy = SessionPolicy(transport)

        assertTrue(policy.execute("old-token") { transport.request(it) }.isSuccess)
        assertEquals(1, transport.loginCalls)
        assertEquals(2, transport.requestCalls)
    }

    @Test
    fun serverFailureDoesNotRelogIn() = runTest {
        val transport = FakeTransport(responses = mutableListOf(500))
        val policy = SessionPolicy(transport)

        assertTrue(policy.execute("old-token") { transport.request(it) }.isFailure)
        assertEquals(0, transport.loginCalls)
        assertEquals(1, transport.requestCalls)
    }

    @Test
    fun rejectedRenewedTokenDoesNotRelogInAgain() = runTest {
        val transport = FakeTransport(responses = mutableListOf(401, 200, 401))
        val policy = SessionPolicy(transport)

        assertTrue(policy.execute("old-token") { transport.request(it) }.isFailure)
        assertEquals(1, transport.loginCalls)
        assertEquals(2, transport.requestCalls)
    }

    @Test
    fun successfulOwnPasswordChangeRotatesProcessCredential() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.requestBody.close()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        val profile = ServerProfile(
            id = "profile",
            displayName = "Test",
            scheme = "http",
            host = "127.0.0.1",
            port = server.address.port,
            basePath = "/",
        )
        val secrets = ProcessSecretStore().apply {
            put(profile.id, StoredCredential("reader", "old-password".toCharArray()))
        }
        val repository = SessionRepository(
            secretStore = secrets,
            transport = FileBrowserClient(),
            tokenStore = mutableMapOf(profile.id to "token"),
        )
        try {
            val result = repository.saveUser(
                profile = profile,
                user = ServerUser(id = 7, username = "reader"),
                newPassword = "new-password",
                currentPassword = "old-password",
                profileOnly = true,
            )

            assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
            val stored = requireNotNull(secrets.get(profile.id))
            try {
                assertEquals("reader", stored.username)
                assertEquals("new-password", stored.password.concatToString())
            } finally {
                stored.password.fill('\u0000')
            }
        } finally {
            server.stop(0)
            secrets.clearAll()
        }
    }

    private class FakeTransport(private val responses: MutableList<Int>) : SessionPolicy.TokenRefresher {
        var loginCalls = 0
        var requestCalls = 0

        override suspend fun renew(): Result<String> {
            loginCalls++
            return if (responses.removeAt(0) in 200..299) Result.success("new-token")
            else Result.failure(IllegalStateException("Login failed"))
        }

        fun request(token: String): ApiResult<String> {
            requestCalls++
            return ApiResult(responses.removeAt(0), token)
        }
    }
}
