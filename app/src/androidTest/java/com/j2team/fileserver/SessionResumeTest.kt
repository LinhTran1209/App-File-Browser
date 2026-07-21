package com.j2team.fileserver

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionResumeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityRecreationKeepsProcessOwnedSessionRepository() {
        val before = (compose.activity.application as FileServerApp).sessionRepository

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        val after = (compose.activity.application as FileServerApp).sessionRepository
        assertSame(before, after)
    }
}
