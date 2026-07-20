package com.j2team.fileserver.feature.transfers

enum class TransferDirection { Download, Upload }
enum class TransferState { Queued, Running, Paused, Completed, Failed, Cancelled }
enum class TransferTab { Downloads, Uploads }

object TransferErrors { const val Interrupted = "transfer_interrupted_after_restart" }

data class TransferTask(
    val id: String,
    val name: String,
    val path: String,
    val direction: TransferDirection,
    /** Persisted SAF tree/source URI used for manual retry after a process restart. */
    val sourceUri: String? = null,
    /** Server ownership prevents a durable task from being retried against another selected server. */
    val profileId: String? = null,
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

fun List<TransferTask>.attentionCount(): Int = count { it.isActive || it.state == TransferState.Failed }

fun List<TransferTask>.attentionBadge(): String = attentionCount().let { if (it > 99) "99+" else it.toString() }

fun List<TransferTask>.forTab(tab: TransferTab): List<TransferTask> = filter { task ->
    task.direction == when (tab) {
        TransferTab.Downloads -> TransferDirection.Download
        TransferTab.Uploads -> TransferDirection.Upload
    }
}

fun recoverInterruptedTransfers(tasks: List<TransferTask>): List<TransferTask> = tasks.map { task ->
    if (task.state == TransferState.Queued || task.state == TransferState.Running || task.state == TransferState.Paused) task.copy(state = TransferState.Failed, error = TransferErrors.Interrupted) else task
}

fun removeTransfer(tasks: List<TransferTask>, id: String): List<TransferTask> = tasks.filterNot { it.id == id }
