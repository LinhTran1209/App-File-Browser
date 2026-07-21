package com.j2team.fileserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.net.Uri
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.ui.FileServerTheme
import com.j2team.fileserver.feature.preview.RenderedTextPage
import com.j2team.fileserver.feature.preview.TextPage
import com.j2team.fileserver.feature.preview.TextPreviewPageList
import com.j2team.fileserver.feature.settings.AppSettings
import com.j2team.fileserver.feature.settings.SettingsScreen
import com.j2team.fileserver.feature.settings.TreeSelection
import com.j2team.fileserver.feature.transfers.TransferDirection
import com.j2team.fileserver.feature.transfers.TransferStore
import com.j2team.fileserver.feature.transfers.TransfersScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndSelectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun passwordEyeSwitchesBetweenShowAndHideSemantics() {
        val profile = ServerProfile("test", "Test server", "https", "example.test", 443)
        val sessionRepository = (context.applicationContext as FileServerApp).sessionRepository
        val showPassword = context.getString(R.string.show_password)
        val hidePassword = context.getString(R.string.hide_password)

        compose.setContent {
            FileServerTheme { LoginScreen(profile, sessionRepository, onBack = {}, onConnected = {}) }
        }

        compose.onNodeWithContentDescription(showPassword).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(hidePassword).assertIsDisplayed()
    }

    @Test
    fun selectedDirectoryUriUsesProductionGrantHandlerAndUpdatesVisibleSetting() {
        val selectedDirectory = Uri.parse("content://com.example.documents/tree/instrumented")
        val selection = TreeSelection(selectedDirectory, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        compose.setContent {
            FileServerTheme {
                var settings by remember { mutableStateOf(AppSettings()) }
                SettingsScreen(
                    settings,
                    onBack = {},
                    onTransfers = {},
                    onChanged = { settings = it },
                    directoryPicker = { onResult -> { onResult(selection) } },
                    persistTreeGrant = { true },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.choose_download_directory)).performClick()
        compose.onNodeWithText(selectedDirectory.toString()).assertIsDisplayed()
    }

    @Test
    fun directorySelectionShowsGrantErrorWhenWritePermissionIsMissing() {
        val selection = TreeSelection(Uri.parse("content://com.example.documents/tree/denied"), Intent.FLAG_GRANT_READ_URI_PERMISSION)

        compose.setContent {
            FileServerTheme {
                SettingsScreen(
                    AppSettings(),
                    onBack = {},
                    onTransfers = {},
                    onChanged = {},
                    directoryPicker = { onResult -> { onResult(selection) } },
                    persistTreeGrant = { true },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.choose_download_directory)).performClick()
        compose.onNodeWithText(context.getString(R.string.download_directory_permission_error)).assertIsDisplayed()
    }

    @Test
    fun transferTabsFilterDurableLocalTasks() {
        val preferencesName = "transfer_queue_instrumentation_${System.nanoTime()}"
        val store = TransferStore(context, preferencesName)
        val download = store.enqueue("instrumented-download", "/download", TransferDirection.Download)
        val upload = store.enqueue("instrumented-upload", "/upload", TransferDirection.Upload)
        try {
            compose.setContent { FileServerTheme { TransfersScreen(store, coordinator = null, onBack = {}) } }

            compose.onNodeWithText(download.name).assertIsDisplayed()
            compose.onNodeWithText("Uploads").performClick()
            compose.onNodeWithText(upload.name).assertIsDisplayed()
            val reloaded = TransferStore(context, preferencesName)
            assertTrue(reloaded.all().map { it.id }.containsAll(listOf(download.id, upload.id)))
        } finally {
            store.remove(download.id)
            store.remove(upload.id)
        }
    }

    @Test
    fun longTextPagesScrollToLaterContent() {
        var pages by mutableStateOf((0..7).map { RenderedTextPage(it.toLong(), TextPage("page $it")) })

        compose.setContent { FileServerTheme { TextPreviewPageList(pages) } }

        pages = pages.drop(1) + RenderedTextPage(8, TextPage("page 8"))
        compose.waitForIdle()
        compose.onAllNodesWithTag("text-page-0").assertCountEquals(0)
        compose.onNodeWithTag("text-preview-pages").performScrollToNode(hasText("page 8"))
        compose.onNodeWithTag("text-page-8").assertIsDisplayed()
    }
}
