package com.j2team.fileserver.feature.preview
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private val sharedMediaHttpClient: OkHttpClient by lazy { OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build() }

internal fun mediaHttpClient(): OkHttpClient = sharedMediaHttpClient

internal suspend fun requestVideoThumbnailOffMain(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    request: suspend () -> Unit,
) = withContext(dispatcher) { request() }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal data class AppAudioTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val name: String,
    val isSelected: Boolean,
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun extractAudioTracks(
    tracks: Tracks,
    originalLabel: String,
    dubbingLabel: String,
): List<AppAudioTrack> {
    val result = mutableListOf<AppAudioTrack>()
    var audioIdx = 0
    for (group in tracks.groups) {
        if (group.type == C.TRACK_TYPE_AUDIO) {
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language ?: ""
                    val label = format.label
                    val name = when {
                        !label.isNullOrBlank() -> label
                        lang.equals("vie", ignoreCase = true) || lang.equals("vi", ignoreCase = true) -> "$dubbingLabel (${lang.uppercase()})"
                        audioIdx == 0 -> originalLabel
                        lang.isNotBlank() -> "$originalLabel (${lang.uppercase()})"
                        else -> "Audio ${audioIdx + 1}"
                    }
                    result.add(
                        AppAudioTrack(
                            group = group,
                            trackIndex = i,
                            name = name,
                            isSelected = group.isTrackSelected(i),
                        )
                    )
                    audioIdx++
                }
            }
        }
    }
    return result
}

internal data class AppSubtitleTrack(
    val id: String,
    val name: String,
    val type: String, // "embedded" or "external"
    val url: String = "",
    val path: String = "",
)

internal enum class SubtitlePosition {
    LOW,
    MEDIUM,
    HIGH,
}

internal data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

private fun parseTimestampMs(timeStr: String): Long {
    val parts = timeStr.trim().replace(',', '.').split(':')
    return if (parts.size == 3) {
        val hours = parts[0].toLongOrNull() ?: 0L
        val minutes = parts[1].toLongOrNull() ?: 0L
        val seconds = (parts[2].toDoubleOrNull() ?: 0.0) * 1000.0
        hours * 3600_000L + minutes * 60_000L + seconds.toLong()
    } else if (parts.size == 2) {
        val minutes = parts[0].toLongOrNull() ?: 0L
        val seconds = (parts[1].toDoubleOrNull() ?: 0.0) * 1000.0
        minutes * 60_000L + seconds.toLong()
    } else {
        0L
    }
}

private fun parseSubtitles(content: String): List<SubtitleCue> {
    val cues = mutableListOf<SubtitleCue>()
    val blocks = content.replace("\r\n", "\n").replace('\r', '\n').split("\n\n")
    val arrowRegex = Regex("""(\d{1,2}:\d{2}:\d{2}[,\.]\d{2,3}|\d{2}:\d{2}[,\.]\d{2,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,\.]\d{2,3}|\d{2}:\d{2}[,\.]\d{2,3})""")

    for (block in blocks) {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val timeLineIndex = lines.indexOfFirst { arrowRegex.containsMatchIn(it) }
        if (timeLineIndex != -1) {
            val match = arrowRegex.find(lines[timeLineIndex])
            if (match != null) {
                val start = parseTimestampMs(match.groupValues[1])
                val end = parseTimestampMs(match.groupValues[2])
                val text = lines.drop(timeLineIndex + 1)
                    .joinToString("\n")
                    .replace(Regex("<[^>]*>"), "")
                if (text.isNotBlank() && end > start) {
                    cues.add(SubtitleCue(start, end, text))
                }
            }
        }
    }
    return cues
}

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
    LaunchedEffect(profile.id, item.path, mimeType) {
        if (mimeType?.startsWith("video/") == true) {
            requestVideoThumbnailOffMain {
                sessionRepository.requestVideoThumbnail(profile, item.path)
            }
        }
    }

    var availableSubtitles by remember(profile.id, item.path) { mutableStateOf<List<AppSubtitleTrack>>(emptyList()) }
    var selectedSubtitle by remember(profile.id, item.path) { mutableStateOf<AppSubtitleTrack?>(null) }
    var subtitleCues by remember(selectedSubtitle) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var subtitlesEnabled by remember(profile.id, item.path) { mutableStateOf(true) }
    var subtitlePosition by rememberSaveable(profile.id, item.path) { mutableStateOf(SubtitlePosition.LOW) }

    // Auto-detect subtitles: first try server /api/video-subtitles, then fallback to sibling files
    LaunchedEffect(profile.id, item.path, mimeType) {
        if (mimeType?.startsWith("video/") == true) {
            withContext(Dispatchers.IO) {
                var foundSubtitles = emptyList<AppSubtitleTrack>()
                // 1. Try server API
                sessionRepository.fetchVideoSubtitles(profile, item.path).onSuccess { jsonStr ->
                    try {
                        val json = org.json.JSONObject(jsonStr)
                        val subArray = json.optJSONArray("subtitles")
                        if (subArray != null && subArray.length() > 0) {
                            val list = mutableListOf<AppSubtitleTrack>()
                            for (i in 0 until subArray.length()) {
                                val itemObj = subArray.getJSONObject(i)
                                val name = itemObj.optString("name", "Phụ đề")
                                val type = itemObj.optString("type", "embedded")
                                val url = itemObj.optString("url", "")
                                val index = itemObj.optInt("index", i)
                                val id = if (url.isNotBlank()) url else "${type}_$index"
                                list.add(AppSubtitleTrack(id = id, name = name, type = type, url = url))
                            }
                            foundSubtitles = list
                        }
                    } catch (_: Exception) {}
                }

                // 2. Fallback to sibling files if no subtitles from server API
                if (foundSubtitles.isEmpty()) {
                    val parentDir = item.path.substringBeforeLast('/', "/").ifEmpty { "/" }
                    val baseName = item.name.substringBeforeLast('.')
                    sessionRepository.list(profile, parentDir).onSuccess { siblings ->
                        val subs = siblings.filter { sib ->
                            !sib.isDirectory &&
                            (sib.name.endsWith(".srt", ignoreCase = true) || sib.name.endsWith(".vtt", ignoreCase = true)) &&
                            (sib.name.startsWith(baseName, ignoreCase = true) || siblings.count { it.name.endsWith(".srt", true) || it.name.endsWith(".vtt", true) } == 1)
                        }.map { sib ->
                            AppSubtitleTrack(
                                id = sib.path,
                                name = sib.name,
                                type = "external",
                                path = sib.path
                            )
                        }
                        foundSubtitles = subs
                    }
                }

                availableSubtitles = foundSubtitles
                if (foundSubtitles.isNotEmpty() && selectedSubtitle == null) {
                    selectedSubtitle = foundSubtitles.first()
                }
            }
        }
    }

    // Load selected subtitle content
    LaunchedEffect(selectedSubtitle) {
        val sub = selectedSubtitle ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val contentResult = if (sub.url.isNotBlank()) {
                sessionRepository.fetchUrlContent(profile, sub.url)
            } else if (sub.path.isNotBlank()) {
                sessionRepository.readText(profile, sub.path)
            } else {
                null
            }
            contentResult?.onSuccess { content ->
                subtitleCues = parseSubtitles(content)
            }
        }
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
                availableSubtitles = availableSubtitles,
                selectedSubtitle = selectedSubtitle,
                onSelectSubtitle = { selectedSubtitle = it },
                subtitleCues = subtitleCues,
                subtitlesEnabled = subtitlesEnabled,
                onToggleSubtitles = { subtitlesEnabled = !subtitlesEnabled },
                subtitlePosition = subtitlePosition,
                onSubtitlePositionChanged = { subtitlePosition = it },
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
    availableSubtitles: List<AppSubtitleTrack> = emptyList(),
    selectedSubtitle: AppSubtitleTrack? = null,
    onSelectSubtitle: (AppSubtitleTrack) -> Unit = {},
    subtitleCues: List<SubtitleCue> = emptyList(),
    subtitlesEnabled: Boolean = true,
    onToggleSubtitles: () -> Unit = {},
    subtitlePosition: SubtitlePosition = SubtitlePosition.LOW,
    onSubtitlePositionChanged: (SubtitlePosition) -> Unit = {},
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
    var currentSpeed by remember(player) { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    val originalAudioLabel = stringResource(R.string.audio_original)
    val dubbingAudioLabel = stringResource(R.string.audio_dubbing)
    var audioTracks by remember(player) {
        mutableStateOf(extractAudioTracks(player.currentTracks, originalAudioLabel, dubbingAudioLabel))
    }
    var showAudioMenu by remember { mutableStateOf(false) }
    var isLongPressing by remember { mutableStateOf(false) }
    var doubleTapFeedback by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val speedOptions = remember { listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f) }
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
            override fun onTracksChanged(tracks: Tracks) {
                audioTracks = extractAudioTracks(tracks, originalAudioLabel, dubbingAudioLabel)
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
            modifier = contentModifier
                .background(Color.Black)
                .pointerInput(player, currentSpeed) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) controlsVersion++
                        },
                        onDoubleTap = { offset ->
                            val isLeft = offset.x < size.width / 2f
                            if (isLeft) {
                                val newPos = (player.currentPosition - 5_000L).coerceAtLeast(0L)
                                player.seekTo(newPos)
                                seekPositionMs = newPos
                                onPositionChanged(newPos)
                                doubleTapFeedback = "rewind"
                            } else {
                                val upperBound = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                val newPos = (player.currentPosition + 5_000L).coerceAtMost(upperBound)
                                player.seekTo(newPos)
                                seekPositionMs = newPos
                                onPositionChanged(newPos)
                                doubleTapFeedback = "forward"
                            }
                            showControls()
                            coroutineScope.launch {
                                delay(700)
                                if (doubleTapFeedback != null) doubleTapFeedback = null
                            }
                        },
                        onPress = {
                            val job = coroutineScope.launch {
                                delay(400)
                                isLongPressing = true
                                player.setPlaybackSpeed(1.5f)
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                job.cancel()
                                if (isLongPressing) {
                                    isLongPressing = false
                                    player.setPlaybackSpeed(currentSpeed)
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Active Subtitle Text Overlay
            val activeCueText = remember(seekPositionMs, subtitleCues, subtitlesEnabled) {
                if (subtitlesEnabled && subtitleCues.isNotEmpty()) {
                    subtitleCues.find { seekPositionMs in it.startMs..it.endMs }?.text
                } else null
            }

            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val containerRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                val videoModifier = if (containerRatio > videoAspectRatio) {
                    Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
                }
                Box(modifier = videoModifier) {
                    PlayerSurface(
                        player = player,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Active Subtitle Text Overlay (Anchored directly inside the video frame!)
                    if (activeCueText != null) {
                        val bottomPadding = when (subtitlePosition) {
                            SubtitlePosition.LOW -> 8.dp
                            SubtitlePosition.MEDIUM -> 32.dp
                            SubtitlePosition.HIGH -> 56.dp
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = bottomPadding)
                                .padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = activeCueText,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                style = TextStyle(
                                    shadow = Shadow(
                                        color = Color.Black,
                                        offset = Offset(1.5f, 1.5f),
                                        blurRadius = 3f
                                    )
                                ),
                                modifier = Modifier
                                    .background(Color(0xB3000000), shape = RoundedCornerShape(4.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.semantics { contentDescription = loadingDescription },
                )
            }

            // Long-press 1.5x Speed Pill Overlay (Top Center)
            AnimatedVisibility(
                visible = isLongPressing,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
            ) {
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${stringResource(R.string.fast_forward_boost)} ⏩",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Double Tap Rewind Overlay (Left Center)
            AnimatedVisibility(
                visible = doubleTapFeedback == "rewind",
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Surface(
                    color = Color(0x99000000),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("« 5s", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.rewind_5s), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    }
                }
            }

            // Double Tap Forward Overlay (Right Center)
            AnimatedVisibility(
                visible = doubleTapFeedback == "forward",
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 48.dp)
            ) {
                Surface(
                    color = Color(0x99000000),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("5s »", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.forward_5s), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    }
                }
            }

            // Controls Overlay with Smooth Fade
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    // Center Play/Pause & 5s Seek Buttons
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                val newPos = (player.currentPosition - 5_000L).coerceAtLeast(0L)
                                player.seekTo(newPos)
                                seekPositionMs = newPos
                                onPositionChanged(newPos)
                                showControls()
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            SeekFiveIcon(backward = true)
                        }

                        IconButton(
                            onClick = {
                                if (player.isPlaying) player.pause() else player.play()
                                showControls()
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            PlayPauseIcon(isPlaying)
                        }

                        IconButton(
                            onClick = {
                                val upperBound = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                val newPos = (player.currentPosition + 5_000L).coerceAtMost(upperBound)
                                player.seekTo(newPos)
                                seekPositionMs = newPos
                                onPositionChanged(newPos)
                                showControls()
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            SeekFiveIcon(backward = false)
                        }
                    }

                    // Bottom Bar: YouTube Red Timeline, Time, Speed Selector & Fullscreen
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        // YouTube Red Scrubber Slider
                        Slider(
                            value = seekPositionMs.coerceAtMost(durationMs.coerceAtLeast(1L)).toFloat(),
                            onValueChange = {
                                seekPositionMs = it.toLong()
                                showControls()
                            },
                            onValueChangeFinished = {
                                player.seekTo(seekPositionMs)
                                onPositionChanged(seekPositionMs)
                                showControls()
                            },
                            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekDescription },
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFF0000), // YouTube Red
                                activeTrackColor = Color(0xFFFF0000),
                                inactiveTrackColor = Color(0x55FFFFFF)
                            )
                        )

                        // Bottom Row: Time • Speed Button • Fullscreen Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${formatMediaTime(seekPositionMs)} / ${formatMediaTime(durationMs)}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // CC Subtitle Button
                                Box {
                                    Button(
                                        onClick = {
                                            showSubtitleMenu = true
                                            showControls()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (subtitlesEnabled && selectedSubtitle != null) Color(0xFF2196F3) else Color(0x33FFFFFF)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text(
                                            text = "CC",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showSubtitleMenu,
                                        onDismissRequest = { showSubtitleMenu = false },
                                        modifier = Modifier.background(Color(0xFF1E1E1E)).widthIn(min = 200.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.subtitles),
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )

                                        // Option: Off
                                        val isOff = !subtitlesEnabled || selectedSubtitle == null
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.subtitles_off),
                                                        color = if (isOff) Color(0xFF2196F3) else Color.White,
                                                        fontWeight = if (isOff) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    if (isOff) {
                                                        Text("✓", color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            },
                                            onClick = {
                                                if (subtitlesEnabled) onToggleSubtitles()
                                                showSubtitleMenu = false
                                                showControls()
                                            }
                                        )

                                        // Subtitle tracks
                                        if (availableSubtitles.isEmpty()) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "(Không có phụ đề)",
                                                        color = Color.Gray,
                                                        fontSize = 13.sp
                                                    )
                                                },
                                                onClick = {
                                                    showSubtitleMenu = false
                                                    showControls()
                                                }
                                            )
                                        } else {
                                            availableSubtitles.forEach { sub ->
                                                val isCurrent = subtitlesEnabled && selectedSubtitle?.id == sub.id
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = sub.name,
                                                                color = if (isCurrent) Color(0xFF2196F3) else Color.White,
                                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                                maxLines = 1
                                                            )
                                                            if (isCurrent) {
                                                                Text("✓", color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    },
                                                    onClick = {
                                                        onSelectSubtitle(sub)
                                                        if (!subtitlesEnabled) onToggleSubtitles()
                                                        showSubtitleMenu = false
                                                        showControls()
                                                    }
                                                )
                                            }
                                        }

                                        // Section: Subtitle Position (Cao / Vừa / Thấp)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(1.dp)
                                                .background(Color(0x33FFFFFF))
                                        )

                                        Text(
                                            stringResource(R.string.subtitle_position),
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                        )

                                        listOf(
                                            SubtitlePosition.HIGH to stringResource(R.string.sub_pos_high),
                                            SubtitlePosition.MEDIUM to stringResource(R.string.sub_pos_medium),
                                            SubtitlePosition.LOW to stringResource(R.string.sub_pos_low)
                                        ).forEach { (pos, label) ->
                                            val isSelected = subtitlePosition == pos
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            color = if (isSelected) Color(0xFF2196F3) else Color.White,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        if (isSelected) {
                                                            Text("✓", color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    onSubtitlePositionChanged(pos)
                                                    showSubtitleMenu = false
                                                    showControls()
                                                }
                                            )
                                        }
                                    }
                                }

                                // Audio Track Selector Button (visible when video has multiple audio tracks)
                                if (audioTracks.size > 1) {
                                    Box {
                                        Button(
                                            onClick = {
                                                showAudioMenu = true
                                                showControls()
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0x33FFFFFF)
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(
                                                text = "🎧",
                                                fontSize = 12.sp
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.audio_tracks),
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = showAudioMenu,
                                            onDismissRequest = { showAudioMenu = false },
                                            modifier = Modifier.background(Color(0xFF1E1E1E))
                                        ) {
                                            Text(
                                                stringResource(R.string.audio_tracks),
                                                color = Color.Gray,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                            )

                                            audioTracks.forEach { track ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = track.name,
                                                                color = if (track.isSelected) Color(0xFF2196F3) else Color.White,
                                                                fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                maxLines = 1
                                                            )
                                                            if (track.isSelected) {
                                                                Text("✓", color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)
                                                            }
                                                        }
                                                    },
                                                    onClick = {
                                                        player.trackSelectionParameters = player.trackSelectionParameters
                                                            .buildUpon()
                                                            .setOverrideForType(
                                                                TrackSelectionOverride(
                                                                    track.group.mediaTrackGroup,
                                                                    track.trackIndex
                                                                )
                                                            )
                                                            .build()
                                                        showAudioMenu = false
                                                        showControls()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Speed Selector Button
                                Box {
                                    Button(
                                        onClick = { showSpeedMenu = true; showControls() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text(
                                            text = if (currentSpeed == 1.0f) "1.0x" else "${currentSpeed}x",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showSpeedMenu,
                                        onDismissRequest = { showSpeedMenu = false },
                                        modifier = Modifier.background(Color(0xFF1E1E1E))
                                    ) {
                                        Text(
                                            stringResource(R.string.playback_speed),
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                        speedOptions.forEach { speed ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = if (speed == 1.0f) stringResource(R.string.speed_normal) else "${speed}x",
                                                            color = if (speed == currentSpeed) Color(0xFF2196F3) else Color.White,
                                                            fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        if (speed == currentSpeed) {
                                                            Text("✓", color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    currentSpeed = speed
                                                    player.setPlaybackSpeed(speed)
                                                    showSpeedMenu = false
                                                    showControls()
                                                }
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { fullscreen = !fullscreen; showControls() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    FullscreenIcon(exit = fullscreen)
                                }
                            }
                        }
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
