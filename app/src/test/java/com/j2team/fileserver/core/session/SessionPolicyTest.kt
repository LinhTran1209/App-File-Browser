package com.j2team.fileserver.core.session

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
