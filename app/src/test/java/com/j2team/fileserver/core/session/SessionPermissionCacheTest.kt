package com.j2team.fileserver.core.session

import com.j2team.fileserver.core.model.ResourcePermissions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPermissionCacheTest {
    @Test
    fun `loads permissions only once for the same profile session`() = runTest {
        val cache = SessionPermissionCache()
        var loads = 0

        repeat(3) {
            cache.getOrLoad("server-1") {
                loads += 1
                ResourcePermissions(canDownload = true)
            }
        }

        assertEquals(1, loads)
    }

    @Test
    fun `clear forces permissions to reload after login`() = runTest {
        val cache = SessionPermissionCache()
        var loads = 0
        val loader = {
            loads += 1
            ResourcePermissions(canDownload = true)
        }

        cache.getOrLoad("server-1", loader)
        cache.clear("server-1")
        cache.getOrLoad("server-1", loader)

        assertEquals(2, loads)
    }
}
