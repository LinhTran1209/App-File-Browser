package com.j2team.fileserver.feature.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.j2team.fileserver.R
import com.j2team.fileserver.formatBytes
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.feature.transfers.TransferStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val INLINE_TEXT_LIMIT_BYTES = 2L * 1024 * 1024

internal fun requiresDownloadedFile(kind: PreviewKind): Boolean =
    kind == PreviewKind.Pdf || kind == PreviewKind.Image

@Composable
internal fun PreviewScreen(
    profile: ServerProfile,
    item: RemoteResource,
    @Suppress("UNUSED_PARAMETER") transferStore: TransferStore,
    sessionRepository: SessionRepository,
    imageSiblings: List<RemoteResource> = emptyList(),
    onNavigateImage: (RemoteResource) -> Unit = {},
    comicSiblings: List<RemoteResource> = emptyList(),
    onNavigateComic: (RemoteResource) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember(item.path) { mutableStateOf<PreviewKind?>(null) }
    val temporaryFile = remember(item.path) { File(context.cacheDir, "preview-${UUID.randomUUID()}.tmp") }
    var downloadedFile by remember(item.path) { mutableStateOf<File?>(null) }
    var resolvedMimeType by remember(item.path) { mutableStateOf<String?>(null) }
    var error by remember(item.path) { mutableStateOf<String?>(null) }
    var openLargeText by remember(item.path) { mutableStateOf(false) }
    val imageIndex = remember(item.path, imageSiblings) { imageSiblings.indexOfFirst { it.path == item.path } }

    var showHud by remember { mutableStateOf(true) }

    // Text Editor state
    var isEditingText by remember(item.path) { mutableStateOf(false) }
    var textDraft by remember(item.path) { mutableStateOf("") }
    var isSavingText by remember { mutableStateOf(false) }
    var saveNotification by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(temporaryFile) {
        onDispose { temporaryFile.delete() }
    }
    LaunchedEffect(profile.id, item.path) {
        withContext(Dispatchers.IO) { sessionRepository.previewProbe(profile, item.path) }
            .onSuccess { probe ->
                val mimeType = PreviewRouter.preferredMimeType(item.mimeType, probe.mimeType)
                val previewKind = PreviewRouter.kind(item.name, probe.sample, mimeType)
                resolvedMimeType = PreviewRouter.resolvedMimeType(item.name, previewKind, mimeType)
                kind = previewKind
            }
            .onFailure { error = it.message ?: "Unable to inspect preview" }
    }
    LaunchedEffect(profile.id, item.path, kind, temporaryFile) {
        if (kind == null || !requiresDownloadedFile(kind!!)) return@LaunchedEffect
        withContext(Dispatchers.IO) { sessionRepository.download(profile, item.path, temporaryFile) }
            .onSuccess { downloadedFile = it }
            .onFailure {
                temporaryFile.delete()
                error = it.message ?: "Unable to download preview"
            }
    }
    LaunchedEffect(saveNotification) {
        if (saveNotification != null) {
            delay(2200)
            saveNotification = null
        }
    }
    LaunchedEffect(saveError) {
        if (saveError != null) {
            delay(4000)
            saveError = null
        }
    }

    if (kind == PreviewKind.Comic) {
        ComicPreview(
            profile = profile,
            item = item,
            sessionRepository = sessionRepository,
            comicSiblings = comicSiblings,
            onNavigateComic = onNavigateComic,
            onBack = onBack,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // 1. Content Viewport
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { showHud = !showHud })
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onBack) {
                            Text(stringResource(R.string.back))
                        }
                    }
                }
                kind == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF2196F3))
                        Text(stringResource(R.string.loading), color = Color.White)
                    }
                }
                kind == PreviewKind.Unsupported -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.preview_unsupported), color = Color.White)
                    }
                }
                kind == PreviewKind.Text && item.size > INLINE_TEXT_LIMIT_BYTES && !openLargeText -> {
                    Button(onClick = { openLargeText = true }) {
                        Text(stringResource(R.string.preview_open_anyway))
                    }
                }
                kind == PreviewKind.Text -> TextPreview(
                    profile = profile,
                    item = item,
                    sessionRepository = sessionRepository,
                    isEditing = isEditingText,
                    textDraft = textDraft,
                    onTextDraftChange = { textDraft = it },
                    onTap = { showHud = !showHud },
                    onError = { error = it },
                )
                kind == PreviewKind.Video || kind == PreviewKind.Audio -> MediaPreview(
                    profile = profile,
                    item = item,
                    declaredMimeType = resolvedMimeType,
                    sessionRepository = sessionRepository,
                )
                requiresDownloadedFile(kind!!) && downloadedFile == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF2196F3))
                        Text(stringResource(R.string.loading), color = Color.White)
                    }
                }
                kind == PreviewKind.Pdf -> PdfPreview(
                    file = downloadedFile!!,
                    onTap = { showHud = !showHud }
                )
                kind == PreviewKind.Image -> ImagePreview(
                    file = downloadedFile!!,
                    description = item.name,
                    onTap = { showHud = !showHud },
                    onSwipeUp = imageSiblings.getOrNull(imageIndex + 1)?.let { next -> { onNavigateImage(next) } },
                    onSwipeDown = imageSiblings.getOrNull(imageIndex - 1)?.let { previous -> { onNavigateImage(previous) } },
                )
            }
        }

        // 2. FLOATING TOP BAR (Animates on tap)
        AnimatedVisibility(
            visible = showHud,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
        ) {
            Surface(
                color = Color(0xF2121212),
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Text("‹", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = item.name + if (isEditingText) " *" else "",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )

                    // Text Editor Actions (Sửa / Lưu)
                    if (kind == PreviewKind.Text) {
                        if (!isEditingText) {
                            Button(
                                onClick = { isEditingText = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp).padding(end = 8.dp)
                            ) {
                                Text(stringResource(R.string.edit), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Button(
                                    onClick = { isEditingText = false },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FFFFFF)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(stringResource(R.string.cancel), color = Color.LightGray, fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isSavingText = true
                                            val result = withContext(Dispatchers.IO) {
                                                sessionRepository.saveResource(profile, item.path, textDraft)
                                            }
                                            result
                                                .onSuccess {
                                                    isSavingText = false
                                                    saveError = null
                                                    saveNotification = context.getString(R.string.saved_successfully)
                                                    isEditingText = false
                                                }
                                                .onFailure {
                                                    isSavingText = false
                                                    saveError = it.localizedMessage ?: it.message ?: it.javaClass.simpleName
                                                }
                                        }
                                    },
                                    enabled = !isSavingText,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    if (isSavingText) {
                                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    } else {
                                        Text(stringResource(R.string.save), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. FLOATING BOTTOM BAR (Animates on tap)
        // CRITICAL FIX: Hide when kind == Video or Audio so it NEVER covers the video timeline!
        AnimatedVisibility(
            visible = showHud && kind != PreviewKind.Video && kind != PreviewKind.Audio && !isEditingText,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Surface(
                color = Color(0xF2121212),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (kind == PreviewKind.Image && imageSiblings.size > 1 && imageIndex >= 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { imageSiblings.getOrNull(imageIndex - 1)?.let(onNavigateImage) },
                                enabled = imageIndex > 0,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0x33FFFFFF),
                                    disabledContainerColor = Color(0x11FFFFFF)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Text(
                                    stringResource(R.string.preview_prev_item),
                                    fontSize = 12.sp,
                                    color = if (imageIndex > 0) Color.White else Color.Gray
                                )
                            }

                            Surface(
                                color = Color(0x33FFFFFF),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "${imageIndex + 1} / ${imageSiblings.size}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }

                            Button(
                                onClick = { imageSiblings.getOrNull(imageIndex + 1)?.let(onNavigateImage) },
                                enabled = imageIndex < imageSiblings.size - 1,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0x33FFFFFF),
                                    disabledContainerColor = Color(0x11FFFFFF)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) {
                                Text(
                                    stringResource(R.string.preview_next_item),
                                    fontSize = 12.sp,
                                    color = if (imageIndex < imageSiblings.size - 1) Color.White else Color.Gray
                                )
                            }
                        }
                    }

                    Text(
                        text = buildString {
                            append(formatBytes(item.size))
                            append("  •  ")
                            append(resolvedMimeType ?: item.mimeType ?: PreviewRouter.mimeType(item.name))
                        },
                        color = Color(0xFFB0B0B8),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // Save Error Floating Notification Banner
        AnimatedVisibility(
            visible = saveError != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)
        ) {
            Surface(
                color = Color(0xFFC62828),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = "⚠️ " + (saveError ?: ""),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // 4. Save Success Floating Notification Banner
        AnimatedVisibility(
            visible = saveNotification != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)
        ) {
            Surface(
                color = Color(0xFF2E7D32),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = saveNotification ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
