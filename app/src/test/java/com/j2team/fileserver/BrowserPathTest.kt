Exit code: 0
Wall time: 0.6 seconds
Output:
package com.j2team.fileserver

import com.j2team.fileserver.feature.browser.BrowserPath
import org.junit.Assert.assertEquals
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
}

