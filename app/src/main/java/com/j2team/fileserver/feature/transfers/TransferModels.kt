package com.j2team.fileserver.feature.transfers

enum class TransferDirection { Download, Upload }
enum class TransferState { Queued, Running, Paused, Completed, Failed, Cancelled }

data class TransferTask(
    val id: String,
    val name: String,
    val path: String,
    val direction: TransferDirection,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val state: TransferState = TransferState.Queued,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val isActive: Boolean get() = state == TransferState.Queued || state == TransferState.Running || state == TransferState.Paused
}
