package com.j2team.fileserver.feature.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RenderOwnerTest {
    @Test
    fun releasesOnlyAfterRenderPublicationWhenAlreadyDisposed() {
        val recycled = mutableListOf<String>()
        val owner = RenderOwner<String> { recycled += it }

        owner.release()

        assertFalse(owner.publishAfterRender("page"))
        assertEquals(listOf("page"), recycled)
    }
}
