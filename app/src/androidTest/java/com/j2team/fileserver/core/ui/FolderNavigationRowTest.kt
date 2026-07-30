package com.j2team.fileserver.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderNavigationRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun folderRowShowsNavigationAffordancesAndOpensWhenPressed() {
        var clickCount = 0

        composeRule.setContent {
            MaterialTheme {
                FolderNavigationRow(
                    name = "movies",
                    iconRes = AppIcons.FolderColor,
                    folderContentDescription = "Folder movies",
                    onClick = { clickCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("movies").assertIsDisplayed()
        composeRule.onNodeWithText("›").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Folder movies")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertEquals(1, clickCount)
    }
}
