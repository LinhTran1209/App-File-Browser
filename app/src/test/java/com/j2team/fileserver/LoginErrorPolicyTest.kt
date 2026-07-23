package com.j2team.fileserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginErrorPolicyTest {
    @Test
    fun authenticationFailuresUseFriendlyCredentialsMessage() {
        assertTrue(isInvalidCredentialsError(IllegalStateException("Login failed (401)")))
        assertTrue(isInvalidCredentialsError(IllegalStateException("Login failed (403)")))
    }

    @Test
    fun networkFailuresKeepTheirOriginalMessage() {
        assertFalse(isInvalidCredentialsError(IllegalStateException("Connection timed out")))
    }
}
