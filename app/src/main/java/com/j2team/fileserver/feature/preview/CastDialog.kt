package com.j2team.fileserver.feature.preview

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.network.CastControlRequest
import com.j2team.fileserver.core.network.CastDevice
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.core.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object CastSessionManager {
    var activeDevice by mutableStateOf<CastDevice?>(null)
    var activeFilePath by mutableStateOf<String?>(null)
    var isPaused by mutableStateOf(false)
    var cachedDevices by mutableStateOf<List<CastDevice>>(emptyList())

    val isCasting: Boolean
        get() = activeDevice != null

    suspend fun castFile(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
        device: CastDevice,
        filePath: String,
        subtitleUrl: String = "",
        startTimeSeconds: Double = 0.0,
    ): Result<String> = withContext(Dispatchers.IO) {
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
        }
    }

    suspend fun togglePause(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
    ): Result<String>? {
        val device = activeDevice ?: return null
        val nextAction = if (isPaused) "resume" else "pause"
        return withContext(Dispatchers.IO) {
            sessionRepository.castControl(
                profile = profile,
                request = CastControlRequest(
                    action = nextAction,
                    controlUrl = device.controlUrl,
                    deviceAddr = device.address,
                )
            ).onSuccess {
                isPaused = !isPaused
            }
        }
    }

    suspend fun seekTo(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
        seconds: Double,
    ): Result<String>? {
        val device = activeDevice ?: return null
        return withContext(Dispatchers.IO) {
            sessionRepository.castControl(
                profile = profile,
                request = CastControlRequest(
                    action = "seek",
                    controlUrl = device.controlUrl,
                    deviceAddr = device.address,
                    seekSeconds = seconds,
                )
            )
        }
    }

    suspend fun stopCasting(
        profile: ServerProfile,
        sessionRepository: SessionRepository,
    ) {
        val device = activeDevice
        activeDevice = null
        activeFilePath = null
        isPaused = false
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
        Icon(
            painter = painterResource(AppIcons.Cast),
            contentDescription = "Truyền lên TV (Cast)",
            tint = if (active) Color(0xFF2196F3) else Color.White,
            modifier = Modifier.size(22.dp)
        )
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF1A1C23),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x0AFFFFFF))
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
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Truyền tới thiết bị (Cast)",
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
                            Text(
                                text = "↻",
                                color = if (loading) Color(0xFF2196F3) else Color(0xFFB0B8C8),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text(
                                text = "✕",
                                color = Color(0xFFB0B8C8),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Active session banner
                    val activeDevice = CastSessionManager.activeDevice
                    if (activeDevice != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x1F2196F3))
                                .border(1.dp, Color(0x552196F3), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )
                                Text(
                                    text = "Đang kết nối: ${activeDevice.name}",
                                    color = Color(0xFF90CAF9),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
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
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f).height(34.dp)
                                    ) {
                                        Text("▶ Phát tệp này", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f).height(34.dp)
                                    ) {
                                        Text(
                                            text = if (CastSessionManager.isPaused) "▶ Tiếp tục" else "⏸ Tạm dừng",
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
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33E53935)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f).height(34.dp)
                                ) {
                                    Text(
                                        text = "⏹ Ngắt kết nối",
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
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ $errorMessage",
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
                                color = Color(0xFF2196F3),
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
                                .padding(vertical = 20.dp),
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
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Quét lại", color = Color(0xFF64B5F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            devices.forEach { device ->
                                val isCurrentDevice = CastSessionManager.activeDevice?.controlUrl == device.controlUrl
                                val protocolLabel = if (device.type.equals("chromecast", ignoreCase = true)) "Google Cast" else "DLNA"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isCurrentDevice) Color(0x292196F3) else Color(0x0DFFFFFF)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isCurrentDevice) Color(0x662196F3) else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
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
                                            color = if (isCurrentDevice) Color(0x332196F3) else Color(0x14FFFFFF),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "📺",
                                                    fontSize = 17.sp
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.name,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = buildString {
                                                    append(protocolLabel)
                                                    if (device.model.isNotBlank() && !device.name.contains(device.model, ignoreCase = true)) {
                                                        append(" • ")
                                                        append(device.model)
                                                    }
                                                    if (device.address.isNotBlank()) {
                                                        append(" • ")
                                                        append(device.address)
                                                    }
                                                },
                                                color = Color(0xFF9AA4B8),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (actionBusy && isCurrentDevice) {
                                        CircularProgressIndicator(
                                            color = Color(0xFF2196F3),
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Text(
                                            text = if (isCurrentDevice) "Đang phát" else "▶",
                                            color = if (isCurrentDevice) Color(0xFF4CAF50) else Color(0xFF64B5F6),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

