package com.j2team.fileserver.core.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object AppCacheManager {
    private const val THUMBNAIL_MAX_BYTES = 100L * 1024L * 1024L
    private const val THUMBNAIL_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val STALE_TEMP_AGE_MS = 24L * 60L * 60L * 1_000L
    private val lock = Mutex()

    fun thumbnailFile(context: Context, profileId: String, remotePath: String): File {
        val key = UUID.nameUUIDFromBytes(remotePath.toByteArray())
        return File(context.cacheDir, "thumbnails/$profileId-$key.img")
    }

    suspend fun cleanup(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            cleanupStaleTempFiles(context.cacheDir)
            trimThumbnails(context.cacheDir)
        }
    }

    suspend fun recordAccess(file: File) = withContext(Dispatchers.IO) {
        if (file.isFile) file.setLastModified(System.currentTimeMillis())
    }

    suspend fun recordWrite(context: Context, file: File) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (file.isFile && file.length() > 0L) {
                file.setLastModified(System.currentTimeMillis())
            } else {
                file.delete()
            }
            trimThumbnails(context.cacheDir)
        }
    }

    suspend fun thumbnailCacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        lock.withLock { thumbnailFiles(context.cacheDir).sumOf(File::length) }
    }

    suspend fun clearThumbnails(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            val directory = File(context.cacheDir, "thumbnails")
            directory.listFiles()?.forEach(File::delete)
            if (directory.listFiles().isNullOrEmpty()) directory.delete()
        }
    }

    private fun cleanupStaleTempFiles(cacheDir: File) {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_AGE_MS
        val prefixes = listOf("preview-", "text-preview-", "upload-", "download-")
        cacheDir.listFiles()
            ?.filter { it.isFile && prefixes.any(it.name::startsWith) && it.lastModified() < cutoff }
            ?.forEach(File::delete)
    }

    private fun trimThumbnails(cacheDir: File) {
        val now = System.currentTimeMillis()
        val maximumAge = now - THUMBNAIL_MAX_AGE_MS
        thumbnailFiles(cacheDir)
            .filter { it.length() <= 0L || it.lastModified() < maximumAge }
            .forEach(File::delete)

        val remaining = thumbnailFiles(cacheDir).sortedBy(File::lastModified).toMutableList()
        var totalBytes = remaining.sumOf(File::length)
        while (totalBytes > THUMBNAIL_MAX_BYTES && remaining.isNotEmpty()) {
            val oldest = remaining.removeFirst()
            val length = oldest.length()
            if (oldest.delete()) totalBytes -= length
        }
    }

    private fun thumbnailFiles(cacheDir: File): List<File> =
        File(cacheDir, "thumbnails").listFiles()?.filter(File::isFile).orEmpty()
}
