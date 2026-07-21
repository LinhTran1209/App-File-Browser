package com.j2team.fileserver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.feature.servers.ServerStore
import java.io.Closeable
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SessionResumeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var server: FakeFileBrowserServer
    private lateinit var profile: ServerProfile
    private lateinit var store: ServerStore

    @Before
    fun setUp() {
        server = FakeFileBrowserServer()
        store = ServerStore(compose.activity)
        profile = store.create("Recreation test server", "http", "127.0.0.1", server.port, "/")
        val repository = (compose.activity.application as FileServerApp).sessionRepository
        val login = runBlocking { repository.login(profile, "instrumented", "password".toCharArray()) }
        assertTrue(login.exceptionOrNull()?.stackTraceToString() ?: "login failed", login.isSuccess)
        compose.activityRule.scenario.recreate()
    }

    @After
    fun tearDown() {
        (compose.activity.application as FileServerApp).sessionRepository.clear(profile.id)
        store.delete(profile.id)
        server.close()
    }

    @Test
    fun authenticatedBrowserRemainsVisibleAfterActivityRecreation() {
        compose.onNodeWithText(profile.displayName).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("resumable.txt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("resumable.txt").assertIsDisplayed()
        assertTrue("credentials were not reused", server.authenticatedRequests.get() >= 2)
        assertTrue("protected endpoint accepted a missing token", server.unauthorizedRequests.get() == 0)
        assertTrue("activity recreation triggered another login", server.loginRequests.get() == 1)
        compose.onNodeWithTag("resource-/resumable.txt").performTouchInput { longClick() }
        compose.onNodeWithTag("resource-/resumable.txt").assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.selected_count, 1)).assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("resumable.txt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("resumable.txt").assertIsDisplayed()
    }

    private class FakeFileBrowserServer : Closeable {
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        @Volatile private var open = true
        val loginRequests = AtomicInteger()
        val authenticatedRequests = AtomicInteger()
        val unauthorizedRequests = AtomicInteger()
        private val worker = thread(name = "fake-file-browser", isDaemon = true) {
            while (open) runCatching { socket.accept() }.getOrNull()?.use(::respond)
        }

        private fun respond(client: java.net.Socket) {
            val request = client.getInputStream().bufferedReader()
            val requestLine = request.readLine().orEmpty()
            val headers = buildMap {
                while (true) {
                    val line = request.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) put(line.substring(0, separator).trim().lowercase(), line.substring(separator + 1).trim())
                }
            }
            val isLogin = requestLine.contains("/api/login")
            val protected = requestLine.contains("/api/users/1") || requestLine.contains("/api/resources")
            val authenticated = headers["x-auth"] == EXPECTED_TOKEN
            if (isLogin) loginRequests.incrementAndGet()
            if (protected && authenticated) authenticatedRequests.incrementAndGet()
            if (protected && !authenticated) unauthorizedRequests.incrementAndGet()
            val code = if (protected && !authenticated) 401 else 200
            val body = when {
                code == 401 -> "{\"error\":\"missing token\"}"
                isLogin -> "\"$EXPECTED_TOKEN\""
                requestLine.contains("/api/users/1") -> "{\"perm\":{\"download\":true,\"create\":true,\"delete\":true}}"
                requestLine.contains("/api/resources") -> "{\"items\":[{\"name\":\"resumable.txt\",\"path\":\"/resumable.txt\",\"isDir\":false,\"size\":1}],\"permissions\":{\"canDownload\":true,\"canCreate\":true,\"canDelete\":true}}"
                else -> "[]"
            }
            val bytes = body.toByteArray()
            client.getOutputStream().apply {
                write("HTTP/1.1 $code ${if (code == 200) "OK" else "Unauthorized"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }

        override fun close() {
            open = false
            socket.close()
            worker.join(1_000)
        }

        private companion object {
            const val EXPECTED_TOKEN = "header.eyJpZCI6MX0.signature"
        }
    }
}
