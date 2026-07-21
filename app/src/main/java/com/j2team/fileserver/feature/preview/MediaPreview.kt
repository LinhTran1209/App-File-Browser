package com.j2team.fileserver.feature.preview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.PlayerSurface
import com.j2team.fileserver.R
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

internal data class MediaStreamConfiguration(
    val headers: Map<String, String>,
    val minBufferMs: Int = 15_000,
    val maxBufferMs: Int = 50_000,
    val bufferForPlaybackMs: Int = 2_500,
    val bufferForPlaybackAfterRebufferMs: Int = 5_000,
)

internal enum class StreamFailureAction { RefreshAndReprepare, ShowLocalError, IgnoreWhileRefreshing }

internal data class StreamRetryState(
    val retryUsed: Boolean = false,
    val refreshInFlight: Boolean = false,
    val reprepareGeneration: Int = 0,
) {
    fun beginRefresh(): StreamRetryState = copy(retryUsed = true, refreshInFlight = true)

    fun renewedSuccessfully(): StreamRetryState = copy(
        refreshInFlight = false,
        reprepareGeneration = reprepareGeneration + 1,
    )

    fun renewalFailed(): StreamRetryState = copy(refreshInFlight = false)
}

internal fun mediaStreamConfiguration(token: String): MediaStreamConfiguration =
    MediaStreamConfiguration(headers = mapOf("X-Auth" to token))

internal fun mediaSessionStateKey(profileId: String, remotePath: String): String = "$profileId\u0000$remotePath"

internal fun streamFailureAction(
    isAuthenticationFailure: Boolean,
    retryUsed: Boolean,
    refreshInFlight: Boolean,
): StreamFailureAction = when {
    refreshInFlight -> StreamFailureAction.IgnoreWhileRefreshing
    isAuthenticationFailure && !retryUsed -> StreamFailureAction.RefreshAndReprepare
    else -> StreamFailureAction.ShowLocalError
}

internal fun fallbackCleanupFiles(destination: File): List<File> = listOf(
    destination,
    File(destination.parentFile, ".${destination.name}.part"),
)

private fun cleanupFallbackFiles(destination: File) {
    fallbackCleanupFiles(destination).forEach { it.delete() }
}

/** MIME mapping remains complete; the probe-aware router protects TypeScript source files. */
internal fun mediaMimeType(name: String, declaredMimeType: String? = null): String? = when (PreviewRouter.extension(name)) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "wmv" -> "video/x-ms-wmv"
    "avi" -> "video/x-msvideo"
    "m2ts", "mts", "ts" -> "video/mp2t"
    "flv" -> "video/x-flv"
    "3gp", "3g2" -> "video/3gpp"
    "mpeg", "mpg" -> "video/mpeg"
    "vob" -> "video/dvd"
    "ogv" -> "video/ogg"
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "ogg", "opus" -> "audio/ogg"
    "wma" -> "audio/x-ms-wma"
    "amr" -> "audio/amr"
    "aiff", "aif" -> "audio/aiff"
    "mka" -> "audio/x-matroska"
    else -> null
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun createMediaPlayer(
    context: Context,
    rawUrl: String,
    token: String,
    mimeType: String,
    initialPositionMs: Long,
): ExoPlayer {
    val configuration = mediaStreamConfiguration(token)
    val dataSourceFactory = OkHttpDataSource.Factory(mediaHttpClient())
        .setDefaultRequestProperties(configuration.headers)
    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(
        MediaItem.Builder().setUri(rawUrl).setMimeType(mimeType).build(),
    )
    val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(
        configuration.minBufferMs,
        configuration.maxBufferMs,
        configuration.bufferForPlaybackMs,
        configuration.bufferForPlaybackAfterRebufferMs,
    ).build()
    return ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
        setMediaSource(mediaSource)
        if (initialPositionMs > 0) seekTo(initialPositionMs)
        prepare()
        playWhenReady = true
    }
}

internal fun mediaHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MediaPreview(
    profile: ServerProfile,
    item: RemoteResource,
    declaredMimeType: String?,
    sessionRepository: SessionRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authError = stringResource(R.string.media_preview_auth_error)
    val playbackError = stringResource(R.string.media_preview_error)
    val loadingDescription = stringResource(R.string.media_loading)
    val mimeType = remember(item.name, declaredMimeType) { mediaMimeType(item.name, declaredMimeType) }
    val sessionStateKey = remember(profile.id, item.path) { mediaSessionStateKey(profile.id, item.path) }
    var token by remember(sessionStateKey) { mutableStateOf<String?>(null) }
    var localError by remember(sessionStateKey) { mutableStateOf<String?>(null) }
    var retryState by remember(sessionStateKey) { mutableStateOf(StreamRetryState()) }
    var savedPositionMs by rememberSaveable(sessionStateKey) { mutableLongStateOf(0L) }
    LaunchedEffect(profile.id, item.path) {
        sessionRepository.streamingToken(profile)
            .onSuccess { token = it }
            .onFailure { localError = authError }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (token == null || mimeType == null) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loadingDescription })
        } else {
            MediaPlayerContent(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                context = context,
                rawUrl = sessionRepository.rawUrl(profile, item.path),
                token = token!!,
                mimeType = mimeType,
                initialPositionMs = savedPositionMs,
                reprepareGeneration = retryState.reprepareGeneration,
                onPositionChanged = { savedPositionMs = it },
                onFailure = { authenticationFailure ->
                    when (streamFailureAction(authenticationFailure, retryState.retryUsed, retryState.refreshInFlight)) {
                        StreamFailureAction.RefreshAndReprepare -> {
                            retryState = retryState.beginRefresh()
                            scope.launch {
                                sessionRepository.renewStreamingToken(profile)
                                    .onSuccess {
                                        token = it
                                        retryState = retryState.renewedSuccessfully()
                                    }
                                    .onFailure {
                                        localError = authError
                                        retryState = retryState.renewalFailed()
                                    }
                            }
                        }
                        StreamFailureAction.ShowLocalError -> localError = playbackError
                        StreamFailureAction.IgnoreWhileRefreshing -> Unit
                    }
                },
            )
        }
        localError?.let { Text(it, modifier = Modifier.padding(16.dp)) }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MediaPlayerContent(
    modifier: Modifier,
    context: Context,
    rawUrl: String,
    token: String,
    mimeType: String,
    initialPositionMs: Long,
    reprepareGeneration: Int,
    onPositionChanged: (Long) -> Unit,
    onFailure: (Boolean) -> Unit,
) {
    val loadingDescription = stringResource(R.string.media_loading)
    val seekDescription = stringResource(R.string.media_seek)
    val player = remember(token, rawUrl, mimeType, reprepareGeneration) {
        createMediaPlayer(context, rawUrl, token, mimeType, initialPositionMs)
    }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var isLoading by remember(player) { mutableStateOf(true) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var seekPositionMs by remember(player) { mutableLongStateOf(initialPositionMs) }
    var videoAspectRatio by remember(player) { mutableStateOf(16f / 9f) }
    var fullscreen by rememberSaveable(player) { mutableStateOf(false) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var controlsVersion by remember(player) { mutableLongStateOf(0L) }
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                isLoading = state == Player.STATE_BUFFERING
                durationMs = player.duration.coerceAtLeast(0L)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
            override fun onPlayerError(error: PlaybackException) { onFailure(error.isAuthenticationFailure()) }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (currentCoroutineContext().isActive) {
            val position = player.currentPosition.coerceAtLeast(0L)
            seekPositionMs = position
            onPositionChanged(position)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    LaunchedEffect(controlsVisible, controlsVersion, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    if (fullscreen) {
        DisposableEffect(activity) {
            val previousOrientation = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {
                activity?.window?.let { window ->
                    WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
                if (previousOrientation != null) activity.requestedOrientation = previousOrientation
            }
        }
    }

    fun showControls() {
        controlsVisible = true
        controlsVersion++
    }

    val playerContent: @Composable (Modifier) -> Unit = { contentModifier ->
        Box(
            modifier = contentModifier.background(Color.Black).clickable {
                controlsVisible = !controlsVisible
                if (controlsVisible) controlsVersion++
            },
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val containerRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                val videoModifier = if (containerRatio > videoAspectRatio) {
                    Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
                }
                PlayerSurface(player = player, modifier = videoModifier)
            }
            if (isLoading) CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = loadingDescription },
            )
            if (controlsVisible) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))) {
                    Row(
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 36.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            player.seekTo((player.currentPosition - 5_000L).coerceAtLeast(0L))
                            showControls()
                        }, modifier = Modifier.size(52.dp)) { SeekFiveIcon(backward = true) }
                        IconButton(onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            showControls()
                        }, modifier = Modifier.size(64.dp)) { PlayPauseIcon(isPlaying) }
                        IconButton(onClick = {
                            val upperBound = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            player.seekTo((player.currentPosition + 5_000L).coerceAtMost(upperBound))
                            showControls()
                        }, modifier = Modifier.size(52.dp)) { SeekFiveIcon(backward = false) }
                    }
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${formatMediaTime(seekPositionMs)} / ${formatMediaTime(durationMs)}",
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { fullscreen = !fullscreen; showControls() }) {
                                FullscreenIcon(exit = fullscreen)
                            }
                        }
                        Slider(
                            value = seekPositionMs.coerceAtMost(durationMs.coerceAtLeast(1L)).toFloat(),
                            onValueChange = { seekPositionMs = it.toLong(); showControls() },
                            onValueChangeFinished = {
                                player.seekTo(seekPositionMs)
                                onPositionChanged(seekPositionMs)
                                showControls()
                            },
                            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekDescription },
                        )
                    }
                }
            }
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val dialogView = LocalView.current
            DisposableEffect(dialogView) {
                val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
                dialogWindow?.let { window ->
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    WindowCompat.getInsetsController(window, dialogView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
                onDispose {
                    dialogWindow?.let { window ->
                        WindowCompat.getInsetsController(window, dialogView).show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
            playerContent(Modifier.fillMaxSize())
        }
    } else {
        playerContent(modifier)
    }
}

@Composable
private fun PlayPauseIcon(isPlaying: Boolean) {
    Canvas(Modifier.size(44.dp)) {
        if (isPlaying) {
            drawRect(Color.White, topLeft = Offset(size.width * .25f, size.height * .18f), size = androidx.compose.ui.geometry.Size(size.width * .16f, size.height * .64f))
            drawRect(Color.White, topLeft = Offset(size.width * .59f, size.height * .18f), size = androidx.compose.ui.geometry.Size(size.width * .16f, size.height * .64f))
        } else {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .28f, size.height * .15f)
                lineTo(size.width * .82f, size.height * .5f)
                lineTo(size.width * .28f, size.height * .85f)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}

@Composable
private fun SeekFiveIcon(backward: Boolean) {
    Box(
        Modifier.size(36.dp).background(Color.Black.copy(alpha = .45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val edgeX = size.width * if (backward) .17f else .83f
            val innerX = size.width * if (backward) .31f else .69f
            val stroke = 1.8.dp.toPx()
            drawLine(Color.White, Offset(edgeX, size.height * .5f), Offset(innerX, size.height * .35f), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(edgeX, size.height * .5f), Offset(innerX, size.height * .65f), stroke, StrokeCap.Round)
        }
        Text("5", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FullscreenIcon(exit: Boolean) {
    Canvas(Modifier.size(28.dp)) {
        val inset = if (exit) size.width * .28f else 0f
        val reach = size.width * .32f
        val stroke = 2.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(Color.White, Offset(x, y), Offset(x + dx * reach, y), stroke, StrokeCap.Square)
            drawLine(Color.White, Offset(x, y), Offset(x, y + dy * reach), stroke, StrokeCap.Square)
        }
        if (exit) {
            corner(inset, inset, 1f, 1f); corner(size.width - inset, inset, -1f, 1f)
            corner(inset, size.height - inset, 1f, -1f); corner(size.width - inset, size.height - inset, -1f, -1f)
        } else {
            corner(0f, 0f, 1f, 1f); corner(size.width, 0f, -1f, 1f)
            corner(0f, size.height, 1f, -1f); corner(size.width, size.height, -1f, -1f)
        }
    }
}

private fun formatMediaTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun PlaybackException.isAuthenticationFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException && current.responseCode in setOf(401, 403)) return true
        current = current.cause
    }
    return false
}
