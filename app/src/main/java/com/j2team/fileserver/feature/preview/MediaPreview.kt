package com.j2team.fileserver.feature.preview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

internal fun mediaStreamConfiguration(token: String): MediaStreamConfiguration =
    MediaStreamConfiguration(headers = mapOf("X-Auth" to token))

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
    val dataSourceFactory = OkHttpDataSource.Factory(OkHttpClient())
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
    val externalError = stringResource(R.string.media_external_error)
    val loadingDescription = stringResource(R.string.media_loading)
    val mimeType = remember(item.name, declaredMimeType) { mediaMimeType(item.name, declaredMimeType) }
    var token by remember(profile.id, item.path) { mutableStateOf<String?>(null) }
    var localError by remember(item.path) { mutableStateOf<String?>(null) }
    var retryUsed by remember(item.path) { mutableStateOf(false) }
    var refreshInFlight by remember(item.path) { mutableStateOf(false) }
    var isOpeningExternally by remember(item.path) { mutableStateOf(false) }
    var fallbackJob by remember(item.path) { mutableStateOf<Job?>(null) }
    var savedPositionMs by rememberSaveable(item.path) { mutableLongStateOf(0L) }
    val disposed = remember(item.path) { AtomicBoolean(false) }
    val externalFile = remember(item.path) {
        File(context.cacheDir, "media/${UUID.randomUUID()}-${item.name.substringAfterLast('/').replace('\\', '_')}")
    }

    DisposableEffect(externalFile) {
        onDispose {
            disposed.set(true)
            fallbackJob?.cancel()
            cleanupFallbackFiles(externalFile)
        }
    }
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
                onPositionChanged = { savedPositionMs = it },
                onFailure = { authenticationFailure ->
                    when (streamFailureAction(authenticationFailure, retryUsed, refreshInFlight)) {
                        StreamFailureAction.RefreshAndReprepare -> {
                            retryUsed = true
                            refreshInFlight = true
                            scope.launch {
                                sessionRepository.renewStreamingToken(profile)
                                    .onSuccess { token = it }
                                    .onFailure { localError = authError }
                                refreshInFlight = false
                            }
                        }
                        StreamFailureAction.ShowLocalError -> localError = playbackError
                        StreamFailureAction.IgnoreWhileRefreshing -> Unit
                    }
                },
            )
        }
        localError?.let { Text(it, modifier = Modifier.padding(16.dp)) }
        Button(
            enabled = !isOpeningExternally,
            onClick = {
                isOpeningExternally = true
                fallbackJob = scope.launch {
                    cleanupFallbackFiles(externalFile)
                    externalFile.parentFile?.mkdirs()
                    val partFile = fallbackCleanupFiles(externalFile).last()
                    try {
                        val result = sessionRepository.downloadTo(
                            profile = profile,
                            remotePath = item.path,
                            openDestination = { partFile.outputStream() },
                        )
                        if (!currentCoroutineContext().isActive || disposed.get()) {
                            cleanupFallbackFiles(externalFile)
                            return@launch
                        }
                        result.onSuccess {
                            if (!partFile.renameTo(externalFile) || disposed.get()) {
                                cleanupFallbackFiles(externalFile)
                                if (!disposed.get()) localError = externalError
                                return@onSuccess
                            }
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    val type = mimeType ?: "application/octet-stream"
                                    setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", externalFile), type)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (_: ActivityNotFoundException) {
                                cleanupFallbackFiles(externalFile)
                                localError = externalError
                            }
                        }.onFailure {
                            cleanupFallbackFiles(externalFile)
                            localError = externalError
                        }
                    } catch (_: CancellationException) {
                        cleanupFallbackFiles(externalFile)
                    } finally {
                        if (!disposed.get()) {
                            isOpeningExternally = false
                            fallbackJob = null
                        }
                    }
                }
            },
            modifier = Modifier.padding(16.dp),
        ) {
            Text(stringResource(R.string.media_open_with))
        }
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
    onPositionChanged: (Long) -> Unit,
    onFailure: (Boolean) -> Unit,
) {
    val loadingDescription = stringResource(R.string.media_loading)
    val seekDescription = stringResource(R.string.media_seek)
    val player = remember(token, rawUrl, mimeType) {
        createMediaPlayer(context, rawUrl, token, mimeType, initialPositionMs)
    }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var isLoading by remember(player) { mutableStateOf(true) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var seekPositionMs by remember(player) { mutableLongStateOf(initialPositionMs) }
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

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerSurface(player = player, modifier = Modifier.weight(1f).fillMaxWidth())
        if (isLoading) Text(loadingDescription, modifier = Modifier.semantics { contentDescription = loadingDescription })
        Slider(
            value = seekPositionMs.coerceAtMost(durationMs.coerceAtLeast(1L)).toFloat(),
            onValueChange = { seekPositionMs = it.toLong() },
            onValueChangeFinished = {
                player.seekTo(seekPositionMs)
                onPositionChanged(seekPositionMs)
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = seekDescription },
        )
        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
            Text(stringResource(if (isPlaying) R.string.media_pause else R.string.media_play))
        }
    }
}

private fun PlaybackException.isAuthenticationFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException && current.responseCode in setOf(401, 403)) return true
        current = current.cause
    }
    return false
}
