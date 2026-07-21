package com.j2team.fileserver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.j2team.fileserver.feature.settings.AppLanguage
import com.j2team.fileserver.feature.settings.AppSettings
import com.j2team.fileserver.feature.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguagePersistenceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun languageChoicePersistsAndAppliesAcrossActivityRecreation() {
        val store = SettingsStore(compose.activity)
        store.save(AppSettings(language = AppLanguage.Vietnamese))
        compose.activityRule.scenario.recreate()

        compose.onNodeWithContentDescription("Cài đặt").performClick()
        compose.onNodeWithText("English").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Settings").fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        assertEquals(AppLanguage.English, SettingsStore(compose.activity).read().language)

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        SettingsStore(compose.activity).save(AppSettings(language = AppLanguage.Vietnamese))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Cài đặt").assertIsDisplayed()
    }
}
