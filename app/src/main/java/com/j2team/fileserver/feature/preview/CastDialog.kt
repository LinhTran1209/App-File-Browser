package com.j2team.fileserver.feature.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.network.CastControlRequest
import com.j2team.fileserver.core.network.CastDevice
import com.j2team.fileserver.core.network.CastStatus
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object CastSessionManager {
    var activeDevice by mutableStateOf<CastDevice?>(null)
    var activeFilePath by mutableStateOf<String?>(null)
    var isPaused by mutableStateOf(false)
    var playbackRate by mutableFloatStateOf(1.0f)
    var cachedDevices by mutableStateOf<List<CastDevice>>(emptyList())

    // Live context from MediaPreview so top-bar Cast button knows current video position & subtitle
    var currentMediaPositionSec by mutableDoubleStateOf(0.0)
    var currentMediaSubtitleUrl by mutableStateOf("")
    var lastLocalActionMs by mutableLongStateOf(0L)

    val isCasting: Boolean
        get() = activeDevice != null

    fun markLocalAction() {
        lastLocalActionMs = System.currentTimeMillis()
    }

    suspend fun castFile(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
        device: CastDevice,
        filePath: String,
        subtitleUrl: String = "",
        startTimeSeconds: Double = 0.0,
    ): Result<String> {
        markLocalAction()
        return withContext(Dispatchers.IO) {
            sessionRepository.castControl(
                profile = profile,
                request = CastControlRequest(
                    action = "play",
                    controlUrl = device.controlUrl,
                    deviceAddr = device.address,
                    filePath = filePath,
                    subtitleUrl = subtitleUrl,
                    startTime = startTimeSeconds,
                )
            ).onSuccess {
                activeDevice = device
                activeFilePath = filePath
                isPaused = false
                playbackRate = 1.0f
                markLocalAction()
            }
        }
    }

    suspend fun setPaused(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
        pause: Boolean,
    ): Result<String>? {
        val device = activeDevice ?: return null
        val nextAction = if (pause) "pause" else "resume"
        markLocalAction()
        isPaused = pause
        return withContext(Dispatchers.IO) {
            sessionRepository.castControl(
                profile = profile,
                request = CastControlRequest(
                    action = nextAction,
                    controlUrl = device.controlUrl,
                    deviceAddr = device.address,
                )
            )
        }
    }

    suspend fun togglePause(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
    ): Result<String>? = setPaused(profile, sessionRepository, !isPaused)

    suspend fun seekTo(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
        seconds: Double,
    ): Result<String>? {
        val device = activeDevice ?: return null
        markLocalAction()
        return withContext(Dispatchers.IO) {
            sessionRepository.castControl(
                profile = profile,
                request = CastControlRequest(
                    action = "seek",
                    controlUrl = device.controlUrl,
                    deviceAddr = device.address,
                    seekSeconds = seconds.coerceAtLeast(0.0),
                )
            )
        }
    }

    suspend fun setPlaybackRate(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
        rate: Float,
    ): Result<String>? {
        val device = activeDevice ?: return null
        markLocalAction()
        playbackRate = rate
        return withContext(Dispatchers.IO) {
            sessionRepository.castControl(
                profile = profile,
                request = CastControlRequest(
                    action = "rate",
                    controlUrl = device.controlUrl,
                    deviceAddr = device.address,
                    playbackRate = rate.toDouble(),
                )
            )
        }
    }

    suspend fun pollStatus(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
    ): CastStatus? = withContext(Dispatchers.IO) {
        sessionRepository.castStatus(profile).getOrNull()
    }?.also { status ->
        val recentlyActed = System.currentTimeMillis() - lastLocalActionMs < 2200L
        if (status.active && status.device != null) {
            activeDevice = status.device
            if (status.filePath.isNotBlank() && !recentlyActed) {
                activeFilePath = status.filePath
            }
            if (!recentlyActed) {
                when (status.state) {
                    "PAUSED" -> isPaused = true
                    "PLAYING" -> isPaused = false
                }
                if (status.playbackRate > 0.0) {
                    playbackRate = status.playbackRate.toFloat()
                }
            }
        } else if (!status.active && !recentlyActed) {
            activeDevice = null
            activeFilePath = null
            isPaused = false
        }
    }

    suspend fun stopCasting(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
    ) {
        val device = activeDevice
        markLocalAction()
        activeDevice = null
        activeFilePath = null
        isPaused = false
        playbackRate = 1.0f
        if (device != null) {
            withContext(Dispatchers.IO) {
                sessionRepository.castControl(
                    profile = profile,
                    request = CastControlRequest(
                        action = "stop",
                        controlUrl = device.controlUrl,
                        deviceAddr = device.address,
                    )
                )
            }
        }
    }
}

@Composable
internal fun CastButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = CastSessionManager.isCasting
    IconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(AppIcons.Cast),
                contentDescription = "Truyền lên TV (Cast)",
                tint = if (active) Color(0xFF42A5F5) else Color.White,
                modifier = Modifier.size(22.dp)
            )
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                )
            }
        }
    }
}

@Composable
internal fun CastDialog(
    profile: ServerProfile,
    sessionRepository: SessionRepository,
    filePath: String,
    subtitleUrl: String = "",
    currentTimeSeconds: Double = 0.0,
    isMediaFile: Boolean = false,
    onCastStarted: (CastDevice) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(CastSessionManager.cachedDevices) }
    var loading by remember { mutableStateOf(devices.isEmpty()) }
    var actionBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun scanDevices(forceRefresh: Boolean) {
        scope.launch {
            loading = true
            errorMessage = null
            withContext(Dispatchers.IO) {
                sessionRepository.castDevices(profile, refresh = forceRefresh)
            }.onSuccess { found ->
                devices = found
                CastSessionManager.cachedDevices = found
                loading = false
            }.onFailure { err ->
                loading = false
                errorMessage = err.localizedMessage ?: err.message ?: "Không thể quét thiết bị TV"
            }
        }
    }

    LaunchedEffect(profile.id) {
        scanDevices(forceRefresh = devices.isEmpty())
    }

    val activeDevice = CastSessionManager.activeDevice
    val otherDevices = remember(devices, activeDevice) {
        if (activeDevice == null) devices
        else devices.filter { it.controlUrl != activeDevice.controlUrl }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF181A20),
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(18.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x0CFFFFFF))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(AppIcons.Cast),
                            contentDescription = null,
                            tint = Color(0xFF42A5F5),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Truyền tới thiết bị",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (!loading) scanDevices(forceRefresh = true) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    color = Color(0xFF42A5F5),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                RefreshVectorIcon(tint = Color(0xFFB0B8C8))
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            CloseVectorIcon(tint = Color(0xFFB0B8C8))
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Active session card
                    if (activeDevice != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x1F2196F3))
                                .border(1.dp, Color(0x552196F3), RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    color = Color(0x332196F3),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        TvVectorIcon(tint = Color(0xFF64B5F6))
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF4CAF50))
                                        )
                                        Text(
                                            text = activeDevice.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = formatDeviceSubtitle(activeDevice, prefix = "Đang phát"),
                                        color = Color(0xFF90CAF9),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (CastSessionManager.activeFilePath != filePath) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                actionBusy = true
                                                errorMessage = null
                                                CastSessionManager.castFile(
                                                    profile = profile,
                                                    sessionRepository = sessionRepository,
                                                    device = activeDevice,
                                                    filePath = filePath,
                                                    subtitleUrl = subtitleUrl,
                                                    startTimeSeconds = currentTimeSeconds,
                                                ).onSuccess {
                                                    actionBusy = false
                                                    onCastStarted(activeDevice)
                                                    onDismiss()
                                                }.onFailure { err ->
                                                    actionBusy = false
                                                    errorMessage = err.localizedMessage ?: err.message
                                                }
                                            }
                                        },
                                        enabled = !actionBusy,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f).height(36.dp)
                                    ) {
                                        MiniPlayPauseVectorIcon(isPaused = true, tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Phát tệp này", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                } else if (isMediaFile) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                actionBusy = true
                                                CastSessionManager.togglePause(profile, sessionRepository)
                                                actionBusy = false
                                            }
                                        },
                                        enabled = !actionBusy,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x29FFFFFF)),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f).height(36.dp)
                                    ) {
                                        MiniPlayPauseVectorIcon(isPaused = CastSessionManager.isPaused, tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (CastSessionManager.isPaused) "Tiếp tục" else "Tạm dừng",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            actionBusy = true
                                            CastSessionManager.stopCasting(profile, sessionRepository)
                                            actionBusy = false
                                        }
                                    },
                                    enabled = !actionBusy,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x2EE53935)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f).height(36.dp)
                                ) {
                                    MiniStopVectorIcon(tint = Color(0xFFFF8A80))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Ngắt kết nối",
                                        color = Color(0xFFFF8A80),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Error message if any
                    if (errorMessage != null) {
                        Surface(
                            color = Color(0x26E53935),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = Color(0xFFFF8A80),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Loading or Device List
                    if (loading && devices.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF42A5F5),
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "Đang dò tìm TV trong mạng LAN...",
                                color = Color(0xFFB0B8C8),
                                fontSize = 13.sp
                            )
                        }
                    } else if (devices.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Không tìm thấy TV nào bật Google Cast / DLNA trong mạng LAN.",
                                color = Color(0xFF9AA4B8),
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { scanDevices(forceRefresh = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x262196F3)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Quét lại", color = Color(0xFF64B5F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (otherDevices.isNotEmpty()) {
                        if (activeDevice != null) {
                            Text(
                                text = "Chuyển sang thiết bị khác",
                                color = Color(0xFF8A94A6),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            otherDevices.forEach { device ->
                                val isGoogleCast = device.type.equals("chromecast", ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0x0FFFFFFF))
                                        .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(12.dp))
                                        .clickable(enabled = !actionBusy) {
                                            scope.launch {
                                                actionBusy = true
                                                errorMessage = null
                                                CastSessionManager.castFile(
                                                    profile = profile,
                                                    sessionRepository = sessionRepository,
                                                    device = device,
                                                    filePath = filePath,
                                                    subtitleUrl = subtitleUrl,
                                                    startTimeSeconds = currentTimeSeconds,
                                                ).onSuccess {
                                                    actionBusy = false
                                                    onCastStarted(device)
                                                    onDismiss()
                                                }.onFailure { err ->
                                                    actionBusy = false
                                                    errorMessage = err.localizedMessage ?: err.message ?: "Không thể truyền lên TV"
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            color = if (isGoogleCast) Color(0x222196F3) else Color(0x1AFFFFFF),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                TvVectorIcon(
                                                    tint = if (isGoogleCast) Color(0xFF64B5F6) else Color(0xFFB0BEC5)
                                                )
                                            }
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = device.name,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Surface(
                                                    color = if (isGoogleCast) Color(0x2E2196F3) else Color(0x2600BFA5),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = if (isGoogleCast) "Google Cast" else "DLNA",
                                                        color = if (isGoogleCast) Color(0xFF90CAF9) else Color(0xFF80CBC4),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                                Text(
                                                    text = cleanDeviceSubInfo(device),
                                                    color = Color(0xFF9AA4B8),
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))
                                    MiniPlayPauseVectorIcon(isPaused = true, tint = Color(0xFF64B5F6))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun cleanModelName(rawModel: String): String =
    rawModel
        .replace("(Google Cast)", "", ignoreCase = true)
        .replace("(DLNA)", "", ignoreCase = true)
        .trim()

private fun cleanDeviceSubInfo(device: CastDevice): String {
    val cleanModel = cleanModelName(device.model)
    return buildString {
        if (cleanModel.isNotBlank() && !device.name.contains(cleanModel, ignoreCase = true)) {
            append(cleanModel)
            if (device.address.isNotBlank()) {
                append(" • ")
                append(device.address)
            }
        } else {
            append(device.address)
        }
    }
}

private fun formatDeviceSubtitle(device: CastDevice, prefix: String): String {
    val proto = if (device.type.equals("chromecast", ignoreCase = true)) "Google Cast" else "DLNA"
    return buildString {
        append(prefix)
        append(" • ")
        append(proto)
        if (device.address.isNotBlank()) {
            append(" • ")
            append(device.address)
        }
    }
}

@Composable
private fun TvVectorIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.14f),
            size = Size(w * 0.84f, h * 0.58f),
            cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.32f, h * 0.86f),
            end = Offset(w * 0.68f, h * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.72f),
            end = Offset(w * 0.5f, h * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun MiniPlayPauseVectorIcon(isPaused: Boolean, tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        if (isPaused) {
            val path = Path().apply {
                moveTo(w * 0.22f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.5f)
                lineTo(w * 0.22f, h * 0.86f)
                close()
            }
            drawPath(path, color = tint)
        } else {
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.18f, h * 0.16f),
                size = Size(w * 0.22f, h * 0.68f),
                cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.60f, h * 0.16f),
                size = Size(w * 0.22f, h * 0.68f),
                cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
            )
        }
    }
}

@Composable
private fun MiniStopVectorIcon(tint: Color) {
    Canvas(modifier = Modifier.size(13.dp)) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.15f, size.height * 0.15f),
            size = Size(size.width * 0.7f, size.height * 0.7f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

@Composable
private fun RefreshVectorIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        drawArc(
            color = tint,
            startAngle = 35f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(size.width * 0.15f, size.height * 0.15f),
            size = Size(size.width * 0.7f, size.height * 0.7f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        val path = Path().apply {
            moveTo(size.width * 0.66f, size.height * 0.08f)
            lineTo(size.width * 0.88f, size.height * 0.22f)
            lineTo(size.width * 0.65f, size.height * 0.34f)
            close()
        }
        drawPath(path, color = tint)
    }
}

@Composable
private fun CloseVectorIcon(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 1.8.dp.toPx()
        val pad = size.width * 0.22f
        drawLine(
            color = tint,
            start = Offset(pad, pad),
            end = Offset(size.width - pad, size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(size.width - pad, pad),
            end = Offset(pad, size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
