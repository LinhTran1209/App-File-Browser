package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.ServerUser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerStorageCodecTest {
    @Test
    fun userDetailIncludesQuotaAndMissingScopeState() {
        val user = ServerStorageCodec.user(
            JSONObject(
                """
                {
                  "id":8,
                  "username":"test2",
                  "scope":"/media/usb_bitlocker/test2",
                  "quotaBytes":1000000000,
                  "quotaUsedBytes":0,
                  "quotaRemainingBytes":1000000000,
                  "quotaUnlimited":false,
                  "scopeMissing":true,
                  "perm":{"admin":false,"create":true,"delete":true}
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1_000_000_000L, user.quotaBytes)
        assertEquals(1_000_000_000L, user.quotaRemainingBytes)
        assertTrue(user.scopeMissing)
        assertTrue(user.permissions.create)
    }

    @Test
    fun directoryAndOwnerResponsesPreserveCapacityAndNames() {
        val directory = ServerStorageCodec.directory(
            JSONObject(
                """
                {
                  "path":"/media/usb",
                  "parent":"/media",
                  "directories":[{"name":"movies","path":"/media/usb/movies"}],
                  "total":320068382720,
                  "used":57690157056,
                  "free":262378225664,
                  "contentBytes":57533370393
                }
                """.trimIndent(),
            ),
        )
        val owners = ServerStorageCodec.owners(
            JSONObject("""{"usernames":["test2","test1","test2"]}"""),
        )

        assertEquals(320_068_382_720L, directory.total)
        assertEquals(57_533_370_393L, directory.contentBytes)
        assertEquals("/media/usb/movies", directory.directories.single().path)
        assertEquals(listOf("test2", "test1"), owners)
    }

    @Test
    fun createUserPayloadUsesExplicitFolderAndEmptyWhich() {
        val request = ServerStorageCodec.userMutation(
            ServerUser(
                id = 0,
                username = "alice",
                scope = "/media/users/alice",
                quotaBytes = 20_000_000_000L,
            ),
            newPassword = "secret",
            creating = true,
        )

        assertEquals(0, request.getJSONArray("which").length())
        assertFalse(request.getBoolean("createUserDir"))
        assertEquals(20_000_000_000L, request.getJSONObject("data").getLong("quotaBytes"))
        assertEquals("secret", request.getJSONObject("data").getString("password"))
    }

    @Test
    fun updateUserPayloadIncludesQuotaField() {
        val request = ServerStorageCodec.userMutation(
            ServerUser(id = 8, username = "test2", quotaBytes = 2_000_000_000L),
            newPassword = "",
            creating = false,
        )

        val fields = request.getJSONArray("which")
        assertTrue((0 until fields.length()).any { fields.getString(it) == "quotaBytes" })
        assertEquals(2_000_000_000L, request.getJSONObject("data").getLong("quotaBytes"))
    }
}
