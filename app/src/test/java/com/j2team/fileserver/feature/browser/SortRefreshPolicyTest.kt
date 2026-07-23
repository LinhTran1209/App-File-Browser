package com.j2team.fileserver.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SortRefreshPolicyTest {
    @Test
    fun everySortToggleCreatesANewScrollToTopRequest() {
        val first = nextSortRequest(SortRequest(ascending = true, generation = 0))
        val second = nextSortRequest(first)

        assertEquals(false, first.ascending)
        assertEquals(true, second.ascending)
        assertNotEquals(first.generation, second.generation)
        assertEquals(0, first.targetIndex)
        assertEquals(0, second.targetIndex)
    }
}
