package com.j2team.fileserver.feature.preview

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.j2team.fileserver.AppBar
import com.j2team.fileserver.R
import com.j2team.fileserver.formatBytes
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.feature.transfers.TransferStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val INLINE_TEXT_LIMIT_BYTES = 2L * 1024 * 1024

@Composable
internal fun PreviewScreen(
    profile: ServerProfile,
    item: RemoteResource,
    @Suppress("UNUSED_PARAMETER") transferStore: TransferStore,
    sessionRepository: SessionRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var kind by remember(item.path) { mutableStateOf<PreviewKind?>(null) }
    val temporaryFile = remember(item.path) { File(context.cacheDir, "preview-${UUID.randomUUID()}.tmp") }
    var downloadedFile by remember(item.path) { mutableStateOf<File?>(null) }
    var error by remember(item.path) { mutableStateOf<String?>(null) }
    var openLargeText by remember(item.path) { mutableStateOf(false) }

    DisposableEffect(temporaryFile) {
        onDispose { temporaryFile.delete() }
    }
    LaunchedEffect(profile.id, item.path) {
        withContext(Dispatchers.IO) { sessionRepository.previewProbe(profile, item.path) }
            .onSuccess { probe ->
                val mimeType = PreviewRouter.preferredMimeType(item.mimeType, probe.mimeType)
                kind = PreviewRouter.kind(item.name, probe.sample, mimeType)
            }
            .onFailure { error = it.message ?: "Unable to inspect preview" }
    }
    LaunchedEffect(profile.id, item.path, kind, temporaryFile) {
        if (kind == null || kind == PreviewKind.Unsupported || kind == PreviewKind.Text) return@LaunchedEffect
        withContext(Dispatchers.IO) { sessionRepository.download(profile, item.path, temporaryFile) }
            .onSuccess { downloadedFile = it }
            .onFailure {
                temporaryFile.delete()
                error = it.message ?: "Unable to download preview"
            }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF030712))) {
        Surface(color = Color(0xFF030712)) { AppBar(item.name, onBack) }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                kind == null -> CircularProgressIndicator()
                kind == PreviewKind.Unsupported -> Text(stringResource(R.string.preview_unsupported), color = Color.White)
                kind == PreviewKind.Text && item.size > INLINE_TEXT_LIMIT_BYTES && !openLargeText -> Button(onClick = { openLargeText = true }) { Text(stringResource(R.string.preview_open_anyway)) }
                kind == PreviewKind.Text -> TextPreview(
                    profile = profile,
                    item = item,
                    sessionRepository = sessionRepository,
                    onError = { error = it },
                )
                downloadedFile == null -> CircularProgressIndicator()
                kind == PreviewKind.Pdf -> PdfPreview(downloadedFile!!)
                kind == PreviewKind.Image -> ImagePreview(downloadedFile!!, item.name)
                kind == PreviewKind.Video || kind == PreviewKind.Audio -> LegacyMediaPreview(downloadedFile!!)
            }
        }
        Text(
            "${formatBytes(item.size)}  •  ${item.mimeType ?: PreviewRouter.mimeType(item.name)}",
            color = Color.White,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Temporary local media route until the authenticated Media3 stream is introduced. */
@Composable
private fun LegacyMediaPreview(file: File) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { start() }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
