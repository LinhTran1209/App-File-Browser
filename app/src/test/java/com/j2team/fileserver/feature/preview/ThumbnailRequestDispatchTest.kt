package com.j2team.fileserver.feature.preview

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
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
}
