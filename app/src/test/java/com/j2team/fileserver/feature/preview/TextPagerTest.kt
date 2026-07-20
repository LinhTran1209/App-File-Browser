package com.j2team.fileserver.feature.preview

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPagerTest {
    @Test
    fun readsLongTextInBoundedPagesWithoutTruncating() {
        val pager = TextPager(ByteArrayInputStream("x".repeat(400_000).toByteArray()), 128_000)

        val pages = generateSequence { pager.loadNextBlocking() }.toList()

        assertEquals(400_000, pages.sumOf { it.text.length })
        assertTrue(pages.size > 1)
    }

    @Test
    fun preservesUtf8CharactersSplitAtPageBoundaries() {
        val expected = "A🙂é中".repeat(50)
        val pager = TextPager(ByteArrayInputStream(expected.toByteArray()), 7)

        val actual = generateSequence { pager.loadNextBlocking() }.joinToString(separator = "") { it.text }

        assertEquals(expected, actual)
    }
}
