package com.j2team.fileserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ResourcePermissions
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.ui.FileServerTheme
import com.j2team.fileserver.feature.browser.ResourceRow
import com.j2team.fileserver.feature.preview.TextPage
import com.j2team.fileserver.feature.preview.TextPreviewPageList
import com.j2team.fileserver.feature.settings.AppLanguage
import com.j2team.fileserver.feature.settings.AppSettings
import com.j2team.fileserver.feature.settings.SettingsScreen
import com.j2team.fileserver.feature.settings.SettingsStore
import com.j2team.fileserver.feature.transfers.TransferDirection
import com.j2team.fileserver.feature.transfers.TransferStore
import com.j2team.fileserver.feature.transfers.TransfersScreen
import org.junit.Assert.assertEquals
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
    fun languageChoiceUpdatesSettingsWithoutOpeningSafPicker() {
        val english = context.getString(R.string.language_english)
        var selected by mutableStateOf(AppSettings())

        compose.setContent {
            FileServerTheme {
                SettingsScreen(selected, onBack = {}, onTransfers = {}, onChanged = { selected = it })
            }
        }

        compose.onNodeWithText(english).performClick()
        assertEquals(AppLanguage.English, selected.language)
    }

    @Test
    fun selectedDirectoryUriRoundTripsThroughSettingsStateWithoutSaf() {
        val store = SettingsStore(context)
        val original = store.read()
        val selectedDirectory = "content://com.example.documents/tree/instrumented"
        try {
            store.save(original.copy(downloadTreeUri = selectedDirectory))

            assertEquals(selectedDirectory, store.read().downloadTreeUri)
        } finally {
            store.save(original)
        }
    }

    @Test
    fun longPressSelectsResourceWithoutMakingAFileRequest() {
        var selected = false
        val resource = RemoteResource(
            name = "local-selection.txt",
            path = "/local-selection.txt",
            isDirectory = false,
            size = 1,
            permissions = ResourcePermissions(canDownload = true),
        )

        compose.setContent {
            FileServerTheme {
                ResourceRow(resource, AppSettings(), selected, onClick = {}, onLongClick = { selected = true }, onDownload = {})
            }
        }

        compose.onNodeWithText(resource.name).performTouchInput { longClick() }
        assertTrue(selected)
    }

    @Test
    fun transferTabsFilterDurableLocalTasks() {
        val store = TransferStore(context)
        store.all().forEach { store.remove(it.id) }
        val download = store.enqueue("instrumented-download", "/download", TransferDirection.Download)
        val upload = store.enqueue("instrumented-upload", "/upload", TransferDirection.Upload)
        try {
            compose.setContent { FileServerTheme { TransfersScreen(store, coordinator = null, onBack = {}) } }

            compose.onNodeWithText(download.name).assertIsDisplayed()
            compose.onNodeWithText("Uploads").performClick()
            compose.onNodeWithText(upload.name).assertIsDisplayed()
        } finally {
            store.remove(download.id)
            store.remove(upload.id)
        }
    }

    @Test
    fun longTextPagesScrollToLaterContent() {
        val pages = (0..20).map { TextPage("page $it") }

        compose.setContent { FileServerTheme { TextPreviewPageList(pages) } }

        compose.onNodeWithTag("text-preview-pages").performScrollToNode(hasText("page 20"))
        compose.onNodeWithText("page 20").assertIsDisplayed()
    }
}
