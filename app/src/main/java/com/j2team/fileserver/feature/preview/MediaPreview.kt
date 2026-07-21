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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.Dispatchers
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

internal fun mediaStreamConfiguration(token: String): MediaStreamConfiguration =
    MediaStreamConfiguration(headers = mapOf("X-Auth" to token))

/** Media only; `.ts` remains TypeScript unless File Browser declares an MPEG transport stream. */
internal fun mediaMimeType(name: String, declaredMimeType: String? = null): String? {
    val declared = declaredMimeType?.substringBefore(';')?.trim()?.lowercase()
    if (declared == "video/mp2t") return declared
    return when (PreviewRouter.extension(name)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "wmv" -> "video/x-ms-wmv"
        "avi" -> "video/x-msvideo"
        "m2ts", "mts" -> "video/mp2t"
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
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun createMediaPlayer(
    context: Context,
    rawUrl: String,
    token: String,
    mimeType: String,
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
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authError = stringResource(R.string.media_preview_auth_error)
    val playbackError = stringResource(R.string.media_preview_error)
    val externalError = stringResource(R.string.media_external_error)
    val mimeType = remember(item.name, declaredMimeType) { mediaMimeType(item.name, declaredMimeType) }
    var token by remember(profile.id, item.path) { mutableStateOf<String?>(null) }
    var isRefreshingToken by remember(item.path) { mutableStateOf(false) }
    var hasRetriedAuthentication by remember(item.path) { mutableStateOf(false) }
    var isOpeningExternally by remember(item.path) { mutableStateOf(false) }
    val externalFile = remember(item.path) {
        File(context.cacheDir, "media/${UUID.randomUUID()}-${item.name}")
    }

    DisposableEffect(externalFile) {
        onDispose { externalFile.delete() }
    }
    LaunchedEffect(profile.id, item.path) {
        sessionRepository.streamingToken(profile)
            .onSuccess { token = it }
            .onFailure { onError(authError) }
    }

    if (mimeType == null || token == null) {
        CircularProgressIndicator()
        return
    }

    val player = remember(token, item.path, mimeType) {
        createMediaPlayer(context, sessionRepository.rawUrl(profile, item.path), token!!, mimeType)
    }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.isAuthenticationFailure() && !hasRetriedAuthentication && !isRefreshingToken) {
                    hasRetriedAuthentication = true
                    isRefreshingToken = true
                    scope.launch {
                        sessionRepository.renewStreamingToken(profile)
                            .onSuccess { token = it }
                            .onFailure { onError(authError) }
                        isRefreshingToken = false
                    }
                } else if (!isRefreshingToken) {
                    onError(playbackError)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PlayerSurface(player = player, modifier = Modifier.weight(1f).fillMaxWidth())
        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
            Text(stringResource(if (isPlaying) R.string.media_pause else R.string.media_play))
        }
        Button(
            enabled = !isOpeningExternally,
            onClick = {
                isOpeningExternally = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        externalFile.parentFile?.mkdirs()
                        sessionRepository.download(profile, item.path, externalFile)
                    }
                    result.onSuccess {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            it,
                        )
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: ActivityNotFoundException) {
                            onError(externalError)
                        }
                    }.onFailure { onError(externalError) }
                    isOpeningExternally = false
                }
            },
            modifier = Modifier.padding(16.dp),
        ) {
            Text(stringResource(R.string.media_open_with))
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
