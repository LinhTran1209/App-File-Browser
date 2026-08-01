package com.j2team.fileserver.feature.transfers

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.feature.browser.BrowserPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DownloadConflict { Replace, KeepBoth, Cancel }

/** Durable transfer queue executor. Network/file streams are capped at two concurrent operations. */
class TransferCoordinator(
    private val context: Context,
    private val transferStore: TransferStore,
    private val sessionRepository: SessionRepository,
    private val profile: ServerProfile,
) {
    private val scope get() = TransferRuntime.scope
    private val streamSemaphore get() = TransferRuntime.streamSemaphore
    private val uploadSemaphore get() = TransferRuntime.uploadSemaphore

    suspend fun enqueueFile(uri: Uri, remotePath: String): TransferTask = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromSingleUri(context, uri) ?: throw IOException("Unable to access selected file")
        require(source.isFile) { "Selected item is not a file" }
        val task = transferStore.enqueue(
            name = source.name ?: "upload.bin",
            path = BrowserPath.normalize(remotePath),
            direction = TransferDirection.Upload,
            totalBytes = source.length().coerceAtLeast(0L),
            sourceUri = uri.toString(),
            profileId = profile.id,
        )
        startUpload(task)
        task
    }

    /** Creates every remote directory before its child files are queued, with one durable task per file. */
    suspend fun enqueueFolder(treeUri: Uri, remotePath: String) = withContext(Dispatchers.IO) {
        // Directory creation is a server mutation too. Keep it in the same single-upload
        // lane so selecting another folder cannot interrupt a file body already in flight.
        uploadSemaphore.withPermit {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Unable to access selected folder")
            require(root.isDirectory) { "Selected item is not a folder" }
            ensureUploadPermission(requiresCreate = true)
            val rootPath = BrowserPath.child(remotePath, root.name ?: "folder")
            sessionRepository.createDirectory(profile, rootPath).getOrThrow()
            val files = mutableListOf<Pair<DocumentFile, String>>()
            createDirectoryPlan(root, rootPath, files)
            files.forEach { (child, parent) -> enqueuePlannedFile(child, parent) }
        }
    }

    suspend fun hasDownloadConflict(treeUri: Uri, name: String): Boolean = withContext(Dispatchers.IO) {
        destinationRoot(treeUri).findFile(name) != null
    }

    suspend fun enqueueDownload(
        remotePath: String,
        name: String,
        totalBytes: Long,
        treeUri: Uri,
        conflict: DownloadConflict,
    ): TransferTask? = withContext(Dispatchers.IO) {
        if (conflict == DownloadConflict.Cancel) return@withContext null
        val root = destinationRoot(treeUri)
        val finalName = when (conflict) {
            DownloadConflict.Replace -> name
            DownloadConflict.KeepBoth -> uniqueName(root, name)
            DownloadConflict.Cancel -> error("handled above")
        }
        val task = transferStore.enqueue(finalName, BrowserPath.normalize(remotePath), TransferDirection.Download, totalBytes, treeUri.toString(), profile.id)
        startDownload(task)
        task
    }

    suspend fun enqueueArchiveDownload(
        remotePaths: List<String>,
        name: String,
        totalBytes: Long,
        treeUri: Uri,
        algorithm: String,
        conflict: DownloadConflict,
    ): TransferTask? = withContext(Dispatchers.IO) {
        require(remotePaths.isNotEmpty()) { "At least one item is required" }
        if (conflict == DownloadConflict.Cancel) return@withContext null
        val root = destinationRoot(treeUri)
        val finalName = when (conflict) {
            DownloadConflict.Replace -> name
            DownloadConflict.KeepBoth -> uniqueName(root, name)
            DownloadConflict.Cancel -> error("handled above")
        }
        val task = transferStore.enqueue(
            name = finalName,
            path = BrowserPath.normalize(remotePaths.first()),
            direction = TransferDirection.Download,
            totalBytes = totalBytes,
            sourceUri = treeUri.toString(),
            profileId = profile.id,
            archivePaths = remotePaths.map(BrowserPath::normalize),
            archiveAlgorithm = algorithm,
        )
        startDownload(task)
        task
    }

    fun retry(task: TransferTask) {
        resume(task)
    }

    fun pause(task: TransferTask) {
        if (!canPause(task)) return
        transferStore.pause(task.id) ?: return
        TransferRuntime.jobs[task.id]?.cancel()
    }

    fun resume(task: TransferTask) {
        if (!canResume(task)) return
        val queued = transferStore.resume(task.id) ?: return
        when (queued.direction) {
            TransferDirection.Upload -> startUpload(queued)
            TransferDirection.Download -> startDownload(queued)
        }
    }

    fun canRetry(task: TransferTask): Boolean =
        (task.state == TransferState.Failed || task.state == TransferState.Cancelled) && task.profileId == profile.id

    fun canPause(task: TransferTask): Boolean = task.state == TransferState.Running && task.profileId == profile.id

    fun canResume(task: TransferTask): Boolean =
        (task.state == TransferState.Paused || canRetry(task)) && task.profileId == profile.id

    private suspend fun createDirectoryPlan(folder: DocumentFile, remoteParent: String, files: MutableList<Pair<DocumentFile, String>>) {
        val children = folder.listFiles().sortedBy { it.name.orEmpty() }
        // Complete directory creation for this subtree before any file is queued.
        children.filter { it.isDirectory }.forEach { child ->
            val name = child.name ?: return@forEach
            val remoteDirectory = BrowserPath.child(remoteParent, name)
            ensureUploadPermission(requiresCreate = true)
            sessionRepository.createDirectory(profile, remoteDirectory).getOrThrow()
            createDirectoryPlan(child, remoteDirectory, files)
        }
        children.filter { it.isFile }.forEach { child ->
            files += child to remoteParent
        }
    }

    private fun enqueuePlannedFile(child: DocumentFile, remoteParent: String) {
        val name = child.name ?: return
        val task = transferStore.enqueue(name, remoteParent, TransferDirection.Upload, child.length().coerceAtLeast(0L), child.uri.toString(), profile.id)
        startUpload(task)
    }

    private fun startUpload(task: TransferTask) {
        val job = scope.launch {
        // File Browser on small servers can close one of multiple simultaneous POST bodies.
        // Serialize uploads while downloads keep their own two-stream allowance.
        uploadSemaphore.withPermit {
            var latest = transferStore.startIfQueued(task.id) ?: return@withPermit
            var observedBytes = latest.transferredBytes
            val progressGate = TransferProgressGate()
            try {
                ensureUploadPermission(requiresCreate = false)
                val uri = task.sourceUri?.let(Uri::parse) ?: throw IOException("Upload source is unavailable")
                val source = DocumentFile.fromSingleUri(context, uri) ?: throw IOException("Upload source is unavailable")
                val totalBytes = source.length().takeIf { it >= 0L } ?: latest.totalBytes
                latest = transferStore.save(latest.copy(totalBytes = totalBytes))
                sessionRepository.uploadResumable(
                    profile = profile,
                    parentPath = latest.path,
                    remoteName = latest.name,
                    totalBytes = totalBytes,
                    openSource = { context.contentResolver.openInputStream(uri) ?: throw IOException("Unable to read ${task.name}") },
                    shouldContinue = { transferStore.all().firstOrNull { it.id == task.id }?.state == TransferState.Running },
                ) { sent, total ->
                    observedBytes = sent.coerceAtMost(total)
                    val decision = progressGate.next()
                    if (decision.publish) latest = transferStore.updateProgress(latest.id, observedBytes, total, decision.persist) ?: latest
                }.getOrThrow()
                transferStore.update(latest.id, totalBytes, TransferState.Completed)
            } catch (error: Throwable) {
                val persisted = transferStore.all().firstOrNull { it.id == latest.id } ?: latest
                if (persisted.state == TransferState.Paused) {
                    transferStore.save(persisted.copy(transferredBytes = observedBytes, error = null))
                } else {
                    transferStore.update(persisted.id, observedBytes, TransferState.Failed, error.message ?: error.toString())
                }
            }
        }
        }
        TransferRuntime.jobs[task.id]?.cancel()
        TransferRuntime.jobs[task.id] = job
    }

    private fun startDownload(task: TransferTask) {
        val job = scope.launch {
        streamSemaphore.withPermit {
            var latest = transferStore.startIfQueued(task.id) ?: return@withPermit
            var part: DocumentFile? = null
            var completed = false
            var observedBytes = latest.transferredBytes
            val progressGate = TransferProgressGate()
            try {
                val permissions = sessionRepository.currentPermissions(profile).getOrThrow()
                if (!permissions.canDownload) throw SecurityException("Download is not permitted by the server")
                val root = destinationRoot(task.sourceUri?.let(Uri::parse) ?: throw IOException("Download destination is unavailable"))
                val partName = ".${task.name}.part"
                part = root.findFile(partName) ?: root.createFile("application/octet-stream", partName)
                    ?: throw IOException("Unable to create temporary download")
                var startOffset = part.length().coerceAtLeast(0L)
                if (task.archivePaths.isNotEmpty()) {
                    if (startOffset > 0L) {
                        part.delete()
                        part = root.createFile("application/octet-stream", partName)
                            ?: throw IOException("Unable to recreate temporary download")
                    }
                    startOffset = 0L
                }
                val activePart = part
                val openPart: (Boolean) -> java.io.OutputStream = { append ->
                    context.contentResolver.openOutputStream(activePart.uri, if (append) "wa" else "w")
                        ?: throw IOException("Unable to write temporary download")
                }
                val progress: (Long, Long) -> Unit = { copied, total ->
                    val reportedTotal = total.takeIf { it > 0 } ?: latest.totalBytes
                    val safeTotal = maxOf(reportedTotal, copied)
                    observedBytes = copied
                    val decision = progressGate.next()
                    if (decision.publish) latest = transferStore.updateProgress(latest.id, copied, safeTotal, decision.persist) ?: latest
                }
                if (task.archivePaths.isNotEmpty() && task.archiveAlgorithm != null) {
                    sessionRepository.downloadArchiveTo(profile, task.archivePaths, task.archiveAlgorithm, { openPart(false) }, progress).getOrThrow()
                } else {
                    sessionRepository.downloadTo(profile, task.path, startOffset, openPart, progress).getOrThrow()
                }
                // Preserve the old file until staging has succeeded. Rename it aside so a finalization failure can restore it.
                val backupName = ".${task.name}.${task.id}.${UUID.randomUUID()}.backup"
                val existing = root.findFile(task.name)
                var backupCreatedByThisAttempt = false
                if (existing != null) {
                    if (!existing.renameTo(backupName)) throw IOException("Unable to safely replace destination")
                    backupCreatedByThisAttempt = true
                }
                try {
                    val final = root.createFile("application/octet-stream", task.name) ?: throw IOException("Unable to create final download")
                    context.contentResolver.openInputStream(activePart.uri)?.use { input ->
                        context.contentResolver.openOutputStream(final.uri, "w")?.use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        } ?: throw IOException("Unable to finalize download")
                    } ?: throw IOException("Unable to read temporary download")
                    if (backupCreatedByThisAttempt && root.findFile(backupName)?.delete() != true) throw IOException("Finalized download but retained backup: $backupName")
                } catch (error: Throwable) {
                    val partialDeleted = root.findFile(task.name)?.delete() ?: true
                    val restored = !backupCreatedByThisAttempt || root.findFile(backupName)?.renameTo(task.name) == true
                    if (!partialDeleted || !restored) throw IOException("${error.message ?: "Unable to finalize download"}; old file retained at $backupName", error)
                    throw error
                }
                transferStore.update(latest.id, maxOf(latest.totalBytes, observedBytes), TransferState.Completed)
                completed = true
            } catch (error: Throwable) {
                val persisted = transferStore.all().firstOrNull { it.id == latest.id } ?: latest
                if (persisted.state == TransferState.Paused) {
                    transferStore.save(persisted.copy(transferredBytes = observedBytes, error = null))
                } else {
                    transferStore.update(persisted.id, observedBytes, TransferState.Failed, error.message ?: error.toString())
                }
            } finally {
                if (completed) part?.delete()
            }
        }
        }
        TransferRuntime.jobs[task.id]?.cancel()
        TransferRuntime.jobs[task.id] = job
    }

    private suspend fun ensureUploadPermission(requiresCreate: Boolean) {
        val permissions = sessionRepository.currentPermissions(profile).getOrThrow()
        if (!permissions.canUpload || (requiresCreate && !permissions.canCreate)) {
            throw SecurityException("Upload is not permitted by the server")
        }
    }

    private fun destinationRoot(treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Download folder is unavailable")

    private fun uniqueName(root: DocumentFile, name: String): String {
        if (root.findFile(name) == null) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        return generateSequence(1) { it + 1 }
            .map { "$stem ($it)$extension" }
            .first { root.findFile(it) == null }
    }
}

/** Process-wide worker owner: profile-specific facades share one scope and two-stream gate. */
object TransferRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val streamSemaphore = Semaphore(2)
    val uploadSemaphore = Semaphore(1)
    val jobs = ConcurrentHashMap<String, Job>()
}
