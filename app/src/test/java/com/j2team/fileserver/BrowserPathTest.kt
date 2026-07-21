package com.j2team.fileserver

import com.j2team.fileserver.feature.browser.BrowserPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserPathTest {
    @Test
    fun parentNeverEscapesRoot() {
        assertEquals("/", BrowserPath.parent("/"))
        assertEquals("/", BrowserPath.parent("/home"))
        assertEquals("/home", BrowserPath.parent("/home/linh3"))
    }

    @Test
    fun childEncodesNoNavigationSegments() {
        assertEquals("/home/linh3", BrowserPath.child("/home", "linh3"))
        assertEquals("/home", BrowserPath.child("/", "home"))
    }

    @Test
    fun childRejectsNamesThatAreNotExactlyOnePathSegment() {
        listOf(".", "..", "../other", "a/b", "a\\b").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { BrowserPath.child("/home", name) }
        }
    }
}
