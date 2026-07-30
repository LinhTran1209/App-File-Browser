package com.j2team.fileserver.feature.preview

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailRequestDispatchTest {
    @Test
    fun thumbnailRequestLeavesCallerThread() = runBlocking {
        val callerThread = Thread.currentThread()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "thumbnail-network") }
        executor.asCoroutineDispatcher().use { dispatcher ->
            var requestThread: Thread? = null

            requestVideoThumbnailOffMain(dispatcher) {
                requestThread = Thread.currentThread()
            }

            assertNotSame(callerThread, requestThread)
            assertTrue(requestThread?.name?.contains("thumbnail-network") == true)
        }
    }

    @Test
    fun sharedThumbnailQueuesOnceAndRetriesWithinBound() = runBlocking {
        var fetches = 0
        var queues = 0

        val ready = fetchSharedVideoThumbnail(
            maxFetchAttempts = 3,
            retryDelayMs = 0,
            fetch = {
                fetches += 1
                fetches == 3
            },
            queue = {
                queues += 1
                true
            },
        )

        assertTrue(ready)
        assertEquals(3, fetches)
        assertEquals(1, queues)
    }

    @Test
    fun sharedThumbnailStopsWhenQueueFails() = runBlocking {
        var fetches = 0

        val ready = fetchSharedVideoThumbnail(
            maxFetchAttempts = 4,
            retryDelayMs = 0,
            fetch = {
                fetches += 1
                false
            },
            queue = { false },
        )

        assertFalse(ready)
        assertEquals(1, fetches)
    }
}
