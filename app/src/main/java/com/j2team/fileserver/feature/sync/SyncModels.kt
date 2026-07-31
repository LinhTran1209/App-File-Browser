package com.j2team.fileserver.feature.sync

enum class SyncState { Disabled, Idle, Syncing, StorageFull, Error }

data class SyncFolder(
    val id: String,
    val profileId: String,
    val serverId: String = "",
    val userId: Long = 0,
    val name: String,
    val localTreeUri: String,
    val localDisplayPath: String,
    val remotePath: String,
    val enabled: Boolean = true,
    val state: SyncState = SyncState.Idle,
    val cursor: Long = 0,
    val lastSyncMillis: Long = 0,
    val lastFullScanMillis: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

data class ServerAccountIdentity(val serverId: String, val userId: Long)

data class SyncEntry(
    val relativePath: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
) {
    fun sameContent(other: SyncEntry?): Boolean = other != null &&
        directory == other.directory &&
        (directory || (size == other.size && modified == other.modified))
}

data class SyncBaseline(
    val local: Map<String, SyncEntry> = emptyMap(),
    val remote: Map<String, SyncEntry> = emptyMap(),
)

data class RemoteChange(
    val id: Long,
    val operation: String,
    val path: String,
    val destination: String?,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
)

data class RemoteChangePage(
    val cursor: Long,
    val reset: Boolean,
    val changes: List<RemoteChange>,
)
