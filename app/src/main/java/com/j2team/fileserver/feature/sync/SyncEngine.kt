package com.j2team.fileserver.feature.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.model.RemoteResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class SyncEngine(
    private val context: Context,
    private val store: SyncFolderStore,
    private val repository: SyncRemoteRepository,
) {
    suspend fun run(profile: ServerProfile, folderId: String): Result<SyncFolder> = globalMutex.withLock {
        withContext(Dispatchers.IO) { runCatching { synchronize(profile, folderId) } }
    }

    private suspend fun synchronize(profile: ServerProfile, folderId: String): SyncFolder {
        var folder = store.find(folderId) ?: throw IOException("Sync folder no longer exists")
        if (!folder.enabled) return folder
        folder = store.save(folder.copy(state = SyncState.Syncing, error = null))
        try {
            val localRoot = DocumentFile.fromTreeUri(context, Uri.parse(folder.localTreeUri))
                ?.takeIf { it.exists() && it.isDirectory } ?: throw IOException("Local folder is unavailable")
            var baseline = store.baseline(folder.id)
            var cursor = folder.cursor
            val remote = baseline.remote.toMutableMap()
            var fullScanMillis = folder.lastFullScanMillis

            if (cursor == 0L || remote.isEmpty() || System.currentTimeMillis() - fullScanMillis >= FULL_RESCAN_INTERVAL_MILLIS) {
                // Capture the cursor before the snapshot, then replay anything that
                // changed while the recursive listing was running.
                cursor = repository.changes(profile, folder.remotePath, 0, bootstrap = true).getOrThrow().cursor
                remote.clear()
                remote.putAll(repository.recursiveList(profile, folder.remotePath).getOrThrow())
                fullScanMillis = System.currentTimeMillis()
            } else {
                val page = repository.changes(profile, folder.remotePath, cursor).getOrThrow()
                if (page.reset) {
                    remote.clear()
                    remote.putAll(repository.recursiveList(profile, folder.remotePath).getOrThrow())
                } else if (requiresRescan(page.changes)) {
                    remote.clear()
                    remote.putAll(repository.recursiveList(profile, folder.remotePath).getOrThrow())
                    fullScanMillis = System.currentTimeMillis()
                } else {
                    applyChanges(remote, folder.remotePath, page.changes)
                }
                cursor = page.cursor
            }

            val local = scanLocal(localRoot)
            val usage = repository.diskUsage(profile, folder.remotePath).getOrNull()
            var freeBytes = usage?.let { (it.total - it.used).coerceAtLeast(0L) } ?: Long.MAX_VALUE
            var storageFull = false
            val pendingLocalUploads = mutableSetOf<String>()
            val allPaths = (baseline.local.keys + baseline.remote.keys + local.keys + remote.keys)
                .distinct().sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })

            for (relative in allPaths) {
                val localNow = local[relative]
                val remoteNow = remote[relative]
                val localBefore = baseline.local[relative]
                val remoteBefore = baseline.remote[relative]
                val localChanged = !same(localNow, localBefore)
                val remoteChanged = !same(remoteNow, remoteBefore)
                when {
                    !localChanged && !remoteChanged -> Unit
                    same(localNow, remoteNow) -> Unit
                    localChanged && !remoteChanged -> {
                        if (localNow == null) {
                            deleteRemote(profile, folder.remotePath, relative, remote)
                        } else {
                            val delta = (localNow.size - (remoteNow?.size ?: 0L)).coerceAtLeast(0L)
                            if (!localNow.directory && delta > freeBytes) { storageFull = true; pendingLocalUploads += relative }
                            else {
                                pushLocal(profile, folder.remotePath, localRoot, relative, localNow, remote)
                                freeBytes = (freeBytes - delta).coerceAtLeast(0L)
                            }
                        }
                    }
                    remoteChanged && !localChanged -> {
                        if (remoteNow == null) deleteLocal(localRoot, relative)
                        else pullRemote(profile, folder.remotePath, localRoot, relative, remoteNow)
                    }
                    else -> {
                        when {
                            localNow == null && remoteNow != null -> pullRemote(profile, folder.remotePath, localRoot, relative, remoteNow)
                            remoteNow == null && localNow != null -> {
                                val delta = localNow.size.coerceAtLeast(0L)
                                if (!localNow.directory && delta > freeBytes) { storageFull = true; pendingLocalUploads += relative }
                                else pushLocal(profile, folder.remotePath, localRoot, relative, localNow, remote)
                            }
                            localNow?.directory == true && remoteNow?.directory == true -> Unit
                            localNow != null && remoteNow != null -> resolveConflict(profile, folder.remotePath, localRoot, relative, localNow, remoteNow, remote)
                        }
                    }
                }
            }

            // Consume events generated by our own mutations and concurrent clients.
            var drainAttempts = 0
            while (drainAttempts++ < 10) {
                val page = repository.changes(profile, folder.remotePath, cursor).getOrThrow()
                if (page.reset) {
                    remote.clear()
                    remote.putAll(repository.recursiveList(profile, folder.remotePath).getOrThrow())
                } else if (requiresRescan(page.changes)) {
                    remote.clear()
                    remote.putAll(repository.recursiveList(profile, folder.remotePath).getOrThrow())
                    fullScanMillis = System.currentTimeMillis()
                } else applyChanges(remote, folder.remotePath, page.changes)
                val previous = cursor
                cursor = page.cursor
                if (page.changes.isEmpty() || cursor == previous) break
            }

            val actualLocal = scanLocal(localRoot)
            val scannedLocal = actualLocal.toMutableMap()
            pendingLocalUploads.forEach { relative ->
                val previous = baseline.local[relative]
                if (previous == null) scannedLocal.remove(relative) else scannedLocal[relative] = previous
            }
            baseline = SyncBaseline(scannedLocal, remote.toMap())
            store.saveBaseline(folder.id, baseline)
            folder = store.save(folder.copy(
                cursor = cursor,
                lastSyncMillis = System.currentTimeMillis(),
                lastFullScanMillis = fullScanMillis,
                totalBytes = actualLocal.values.filterNot { it.directory }.sumOf { it.size },
                state = if (storageFull) SyncState.StorageFull else SyncState.Idle,
                error = if (storageFull) "Cloud storage is full" else null,
            ))
            return folder
        } catch (error: Throwable) {
            folder = store.save(folder.copy(state = SyncState.Error, error = error.message ?: error.toString()))
            throw error
        }
    }

    private fun scanLocal(root: DocumentFile): Map<String, SyncEntry> = buildMap {
        fun walk(directory: DocumentFile, prefix: String) {
            directory.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                val entry = SyncEntry(relative, child.isDirectory, child.length(), child.lastModified())
                put(relative, entry)
                if (child.isDirectory) walk(child, relative)
            }
        }
        walk(root, "")
    }

    private suspend fun pushLocal(profile: ServerProfile, remoteRoot: String, localRoot: DocumentFile, relative: String, entry: SyncEntry, remote: MutableMap<String, SyncEntry>) {
        remote[relative]?.takeIf { it.directory != entry.directory }?.let {
            repository.delete(profile, listOf(remotePath(remoteRoot, relative))).getOrThrow()
            removeTree(remote, relative)
        }
        if (entry.directory) {
            repository.createDirectory(profile, remotePath(remoteRoot, relative)).getOrThrow()
            remote[relative] = entry
            return
        }
        ensureRemoteParents(profile, remoteRoot, relative, remote)
        val source = findLocal(localRoot, relative) ?: throw IOException("Local file disappeared: $relative")
        val temporary = File.createTempFile("sync-upload-", ".part", context.cacheDir)
        try {
            context.contentResolver.openInputStream(source.uri)?.use { input -> temporary.outputStream().buffered().use(input::copyTo) }
                ?: throw IOException("Unable to read local file: $relative")
            repository.upload(profile, remotePath(remoteRoot, relative.substringBeforeLast('/', "")), temporary, relative.substringAfterLast('/')).getOrThrow()
            remote[relative] = entry
        } finally { temporary.delete() }
    }

    private suspend fun pullRemote(profile: ServerProfile, remoteRoot: String, localRoot: DocumentFile, relative: String, entry: SyncEntry, overrideName: String? = null) {
        if (entry.directory) { ensureLocalDirectory(localRoot, overrideName ?: relative); return }
        val destinationRelative = overrideName ?: relative
        val parent = ensureLocalDirectory(localRoot, destinationRelative.substringBeforeLast('/', ""))
        val name = destinationRelative.substringAfterLast('/')
        val temporaryName = ".$name.sync-part"
        parent.findFile(temporaryName)?.delete()
        val temporary = parent.createFile("application/octet-stream", temporaryName) ?: throw IOException("Unable to create local temporary file")
        try {
            repository.download(profile, remotePath(remoteRoot, relative), {
                context.contentResolver.openOutputStream(temporary.uri, "w") ?: throw IOException("Unable to write local file")
            }).getOrThrow()
            parent.findFile(name)?.delete()
            if (!temporary.renameTo(name)) throw IOException("Unable to finalize local file: $name")
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private suspend fun resolveConflict(profile: ServerProfile, remoteRoot: String, localRoot: DocumentFile, relative: String, local: SyncEntry, remoteEntry: SyncEntry, remote: MutableMap<String, SyncEntry>) {
        val stamp = System.currentTimeMillis()
        val name = relative.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").takeIf { it != name }
        val base = if (extension == null) name else name.removeSuffix(".$extension")
        val conflictName = "$base.cloud-conflict-$stamp" + (extension?.let { ".$it" } ?: "")
        val parent = relative.substringBeforeLast('/', "")
        val conflictRelative = listOf(parent, conflictName).filter(String::isNotBlank).joinToString("/")
        repository.rename(
            profile,
            RemoteResource(name, remotePath(remoteRoot, relative), remoteEntry.directory, remoteEntry.size),
            conflictName,
        ).getOrThrow()
        val moved = remote.filterKeys { it == relative || it.startsWith("$relative/") }
        removeTree(remote, relative)
        moved.forEach { (old, value) ->
            val next = conflictRelative + old.removePrefix(relative)
            remote[next] = value.copy(relativePath = next)
        }
        remote.filterKeys { it == conflictRelative || it.startsWith("$conflictRelative/") }
            .toList().sortedBy { it.first.count { c -> c == '/' } }
            .forEach { (path, value) -> pullRemote(profile, remoteRoot, localRoot, path, value) }
        pushLocal(profile, remoteRoot, localRoot, relative, local, remote)
        if (local.directory) {
            scanLocal(localRoot).filterKeys { it.startsWith("$relative/") }
                .toList().sortedBy { it.first.count { c -> c == '/' } }
                .forEach { (path, value) -> pushLocal(profile, remoteRoot, localRoot, path, value, remote) }
        }
    }

    private suspend fun deleteRemote(profile: ServerProfile, root: String, relative: String, remote: MutableMap<String, SyncEntry>) {
        repository.delete(profile, listOf(remotePath(root, relative))).getOrThrow()
        removeTree(remote, relative)
    }

    private fun deleteLocal(root: DocumentFile, relative: String) { findLocal(root, relative)?.delete() }

    private suspend fun ensureRemoteParents(profile: ServerProfile, root: String, relative: String, remote: MutableMap<String, SyncEntry>) {
        var current = ""
        relative.substringBeforeLast('/', "").split('/').filter(String::isNotBlank).forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (remote[current] == null) {
                repository.createDirectory(profile, remotePath(root, current)).getOrThrow()
                remote[current] = SyncEntry(current, true, 0, System.currentTimeMillis())
            }
        }
    }

    private fun ensureLocalDirectory(root: DocumentFile, relative: String): DocumentFile {
        var current = root
        relative.split('/').filter(String::isNotBlank).forEach { segment ->
            val existing = current.findFile(segment)
            if (existing != null && !existing.isDirectory && !existing.delete()) throw IOException("Unable to replace local file: $segment")
            current = existing?.takeIf { it.isDirectory } ?: current.createDirectory(segment)
                ?: throw IOException("Unable to create local folder: $segment")
        }
        return current
    }

    private fun findLocal(root: DocumentFile, relative: String): DocumentFile? {
        var current: DocumentFile? = root
        relative.split('/').filter(String::isNotBlank).forEach { segment -> current = current?.findFile(segment) }
        return current
    }

    private fun applyChanges(remote: MutableMap<String, SyncEntry>, root: String, changes: List<RemoteChange>) {
        changes.forEach { change ->
            val source = relativeTo(root, change.path)
            val destination = change.destination?.let { relativeTo(root, it) }
            when (change.operation.lowercase(Locale.ROOT)) {
                "delete" -> source?.let { removeTree(remote, it) }
                "rename" -> {
                    if (source != null) {
                        val moved = remote.filterKeys { it == source || it.startsWith("$source/") }
                        removeTree(remote, source)
                        if (destination != null) moved.forEach { (old, value) ->
                            val next = destination + old.removePrefix(source)
                            remote[next] = value.copy(relativePath = next)
                        }
                    } else if (destination != null) remote[destination] = SyncEntry(destination, change.directory, change.size, change.modified)
                }
                else -> source?.let { remote[it] = SyncEntry(it, change.directory, change.size, change.modified) }
            }
        }
    }

    private fun requiresRescan(changes: List<RemoteChange>): Boolean = changes.any { change ->
        change.operation.equals("rescan", ignoreCase = true) ||
            (change.operation.equals("rename", ignoreCase = true) && change.directory && change.path.isBlank())
    }

    private fun removeTree(values: MutableMap<String, SyncEntry>, relative: String) {
        values.keys.filter { it == relative || it.startsWith("$relative/") }.toList().forEach(values::remove)
    }

    private fun relativeTo(root: String, absolute: String): String? {
        if (absolute.isBlank()) return null
        val normalizedRoot = "/" + root.trim('/')
        val normalized = "/" + absolute.trim('/')
        if (normalized == normalizedRoot) return ""
        if (normalizedRoot == "/") return normalized.trimStart('/').takeIf(String::isNotEmpty)
        val prefix = normalizedRoot.trimEnd('/') + "/"
        if (!normalized.startsWith(prefix)) return null
        return normalized.removePrefix(prefix).takeIf(String::isNotEmpty)
    }

    private fun remotePath(root: String, relative: String): String =
        ("/" + root.trim('/') + "/" + relative.trim('/')).replace(Regex("/+"), "/").trimEnd('/').ifEmpty { "/" }

    private fun same(first: SyncEntry?, second: SyncEntry?): Boolean = when {
        first == null || second == null -> first == second
        else -> first.sameContent(second)
    }

    private companion object {
        val globalMutex = Mutex()
        const val FULL_RESCAN_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
    }
}
