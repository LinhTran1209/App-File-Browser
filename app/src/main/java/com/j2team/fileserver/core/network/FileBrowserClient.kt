package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ResourcePermissions
import com.j2team.fileserver.core.model.ResourceListing
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.model.DiskUsage
import com.j2team.fileserver.core.model.ShareDurationUnit
import com.j2team.fileserver.core.model.ShareLink
import com.j2team.fileserver.core.model.ServerGlobalSettings
import com.j2team.fileserver.core.model.ServerUser
import com.j2team.fileserver.core.model.AdminDirectoryListing
import com.j2team.fileserver.core.session.ApiResult
import com.j2team.fileserver.feature.sync.RemoteChange
import com.j2team.fileserver.feature.sync.RemoteChangePage
import com.j2team.fileserver.feature.sync.SyncEntry
import com.j2team.fileserver.feature.sync.ServerAccountIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.EmptyCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PreviewProbe(val mimeType: String?, val sample: ByteArray)

data class ComicPage(val index: Int, val name: String)
data class ComicManifest(val title: String, val pages: List<ComicPage>, val count: Int)

private const val NETWORK_BUFFER_SIZE = 256 * 1024
private const val TUS_CHUNK_SIZE = 32L * 1024L * 1024L

/** Atomically pairs cancellation with publication of resources that must be closed. */
internal class CancellableRequestOwner {
    private val cancelled = AtomicBoolean(false)
    private val cleanup = AtomicReference<(() -> Unit)?>(null)

    fun cancel(): (() -> Unit)? {
        cancelled.set(true)
        return cleanup.getAndSet(null)
    }

    fun publish(close: () -> Unit): Boolean {
        cleanup.set(close)
        if (!cancelled.get()) return true
        cleanup.getAndSet(null)?.invoke()
        return false
    }

    fun clear() {
        cleanup.set(null)
    }

    fun isCancelled(): Boolean = cancelled.get()
}

class FileBrowserClient {
    /** Downloads a remote resource to [destination] without buffering it in memory. */
    fun download(
        profile: ServerProfile,
        token: String? = null,
        remotePath: String,
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = downloadResult(profile, token, remotePath, destination, onProgress).toResult()

    fun downloadResult(
        profile: ServerProfile,
        token: String? = null,
        remotePath: String,
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): ApiResult<File> = try {
        require(remotePath.isNotBlank()) { "Remote path is required" }
        val connection = open(rawUrl(profile, remotePath), "GET")
        applyAuthorization(connection, token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Download failed ($code)"))

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: File("."), ".${destination.name}.part")
        val total = connection.contentLengthLong
        var copied = 0L
        try {
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(NETWORK_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress?.invoke(copied, total)
                    }
                }
            }
            if (destination.exists() && !destination.delete()) throw IOException("Unable to replace destination")
            if (!temporary.renameTo(destination)) throw IOException("Unable to finalize download")
            ApiResult(code, destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    /** Streams a download to a caller-owned output stream, allowing SAF destinations without a temporary disk file. */
    suspend fun downloadToResult(
        profile: ServerProfile,
        token: String? = null,
        remotePath: String,
        startOffset: Long = 0L,
        openDestination: (append: Boolean) -> OutputStream,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): ApiResult<Unit> {
        require(remotePath.isNotBlank()) { "Remote path is required" }
        return streamDownloadToResult(rawUrl(profile, remotePath), token, startOffset, openDestination, onProgress)
    }

    suspend fun downloadArchiveToResult(
        profile: ServerProfile,
        token: String? = null,
        remotePaths: List<String>,
        algorithm: String,
        openDestination: () -> OutputStream,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): ApiResult<Unit> {
        require(remotePaths.isNotEmpty()) { "At least one remote path is required" }
        return streamDownloadToResult(archiveUrl(profile, remotePaths, algorithm), token, 0L, { openDestination() }, onProgress)
    }

    private suspend fun streamDownloadToResult(
        url: String,
        token: String?,
        startOffset: Long,
        openDestination: (append: Boolean) -> OutputStream,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?,
    ): ApiResult<Unit> = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        val activeInput = AtomicReference<InputStream?>(null)
        val activeOutput = AtomicReference<OutputStream?>(null)
        val requestOwner = CancellableRequestOwner()
        val cancelActiveRequest = {
            val cleanup = requestOwner.cancel()
            if (cleanup != null) Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { cleanup() })
            Unit
        }
        val closeActiveRequest = {
            activeInput.get()?.runCatching { close() }
            activeOutput.get()?.runCatching { close() }
            activeConnection.get()?.disconnect()
            Unit
        }
        continuation.invokeOnCancellation {
            cancelActiveRequest()
        }
        Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
            val result = try {
                if (requestOwner.isCancelled()) return@Runnable
                val connection = open(url, "GET")
                activeConnection.set(connection)
                if (!requestOwner.publish(closeActiveRequest)) return@Runnable
                if (requestOwner.isCancelled()) return@Runnable
                applyAuthorization(connection, token)
                if (startOffset > 0L) connection.setRequestProperty("Range", "bytes=$startOffset-")
                val code = connection.responseCode
                if (code !in 200..299) ApiResult(code, error = IOException("Download failed ($code)")) else {
                    val append = startOffset > 0L && code == HttpURLConnection.HTTP_PARTIAL
                    val base = if (append) startOffset else 0L
                    val responseLength = connection.contentLengthLong
                    val total = if (responseLength > 0L) base + responseLength else -1L
                    var copied = base
                    connection.inputStream.use { input ->
                        activeInput.set(input)
                        openDestination(append).use { output ->
                            activeOutput.set(output)
                            val buffer = ByteArray(NETWORK_BUFFER_SIZE)
                            while (continuation.isActive) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                onProgress?.invoke(copied, total)
                            }
                        }
                    }
                    ApiResult(code, Unit)
                }
            } catch (error: Throwable) {
                ApiResult(-1, error = error)
            } finally {
                requestOwner.clear()
                activeConnection.getAndSet(null)?.disconnect()
            }
            if (continuation.isActive) continuation.resumeWith(Result.success(result))
        })
    }

    /** Reads only a bounded, authenticated prefix and preserves the server Content-Type for preview routing. */
    fun previewProbeResult(profile: ServerProfile, token: String? = null, remotePath: String, maxBytes: Int = 64 * 1024): ApiResult<PreviewProbe> = try {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val connection = open(rawUrl(profile, remotePath), "GET")
        try {
            if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
            connection.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            val code = connection.responseCode
            if (code !in 200..299) return ApiResult(code, error = IOException("Preview probe failed ($code)"))
            val sample = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(maxBytes)
                val buffer = ByteArray(minOf(NETWORK_BUFFER_SIZE, maxBytes))
                while (output.size() < maxBytes) {
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            ApiResult(code, PreviewProbe(connection.contentType?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty), sample))
        } finally {
            connection.disconnect()
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    /** Uploads the file body exactly as-is to File Browser's resource endpoint. */
    fun upload(
        profile: ServerProfile,
        token: String? = null,
        parentPath: String = "/",
        file: File,
        remoteName: String = file.name,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Result<String> = runCatching {
        require(file.isFile) { "Upload file does not exist: ${file.name}" }
        val destinationPath = parentPath.trimEnd('/') + "/" + remoteName
        // Folder retries may encounter a partially uploaded remote file. Replace it instead of
        // failing the whole batch with 409 Conflict.
        val connection = open(apiUrl(profile, "/api/resources", destinationPath) + "?override=true", "POST").apply {
            doOutput = true
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Accept", "application/json")
            applyAuthorization(this, token)
        }
        val total = file.length()
        connection.setFixedLengthStreamingMode(total)
        connection.outputStream.use { output ->
            FileInputStream(file).use { input ->
                val buffer = ByteArray(NETWORK_BUFFER_SIZE)
                var sent = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    sent += count
                    onProgress?.invoke(sent, total)
                }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        require(code in 200..299) { "Upload failed ($code)" }
        body
    }

    /** Uploads through File Browser's TUS endpoint so a retry can continue at the server offset. */
    fun uploadResumable(
        profile: ServerProfile,
        token: String? = null,
        parentPath: String = "/",
        remoteName: String,
        totalBytes: Long,
        openSource: () -> InputStream,
        shouldContinue: () -> Boolean = { true },
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<String> = runCatching {
        require(totalBytes >= 0L) { "Upload size is unavailable" }
        val destinationPath = parentPath.trimEnd('/') + "/" + remoteName
        val uploadUrl = apiUrl(profile, "/api/tus", destinationPath)

        var offset = tusOffset(uploadUrl, token, totalBytes) ?: run {
            val create = open("$uploadUrl?override=true", "POST").apply {
                setRequestProperty("Upload-Length", totalBytes.toString())
                setRequestProperty("Tus-Resumable", "1.0.0")
                applyAuthorization(this, token)
            }
            try {
                val code = create.responseCode
                require(code == HttpURLConnection.HTTP_CREATED) { "Unable to start resumable upload ($code)" }
            } finally {
                create.disconnect()
            }
            0L
        }
        require(offset in 0L..totalBytes) { "Invalid server upload offset: $offset" }
        onProgress?.invoke(offset, totalBytes)

        openSource().buffered(NETWORK_BUFFER_SIZE).use { input ->
            skipFully(input, offset)
            while (offset < totalBytes) {
                if (!shouldContinue()) throw IOException("Upload paused")
                val chunkLength = minOf(TUS_CHUNK_SIZE, totalBytes - offset)
                // HttpURLConnection rejects PATCH on some Android/JVM implementations.
                // The mod accepts this standard override before its normal TUS POST route.
                val patch = open(uploadUrl, "POST").apply {
                    doOutput = true
                    readTimeout = 60_000
                    setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    setRequestProperty("Content-Type", "application/offset+octet-stream")
                    setRequestProperty("Upload-Offset", offset.toString())
                    setRequestProperty("Tus-Resumable", "1.0.0")
                    applyAuthorization(this, token)
                    setFixedLengthStreamingMode(chunkLength)
                }
                var written = 0L
                try {
                    patch.outputStream.buffered(NETWORK_BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(NETWORK_BUFFER_SIZE)
                        while (written < chunkLength) {
                            if (!shouldContinue()) throw IOException("Upload paused")
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), chunkLength - written).toInt())
                            if (count < 0) throw IOException("Upload source ended before $totalBytes bytes")
                            output.write(buffer, 0, count)
                            written += count
                            onProgress?.invoke(offset + written, totalBytes)
                        }
                    }
                    val code = patch.responseCode
                    require(code == HttpURLConnection.HTTP_NO_CONTENT) { "Resumable upload failed ($code)" }
                    offset = patch.getHeaderField("Upload-Offset")?.toLongOrNull() ?: offset + written
                } finally {
                    patch.disconnect()
                }
            }
        }
        destinationPath
    }

    private fun tusOffset(url: String, token: String?, expectedLength: Long): Long? {
        val head = open(url, "HEAD").apply {
            setRequestProperty("Tus-Resumable", "1.0.0")
            applyAuthorization(this, token)
        }
        return try {
            when (val code = head.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val length = head.getHeaderField("Upload-Length")?.toLongOrNull()
                    if (length == expectedLength) head.getHeaderField("Upload-Offset")?.toLongOrNull() else null
                }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("Unable to inspect resumable upload ($code)")
            }
        } finally {
            head.disconnect()
        }
    }

    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw IOException("Upload source is shorter than the saved server offset")
                remaining--
            }
        }
    }

    suspend fun createDirectory(profile: ServerProfile, token: String? = null, path: String): Result<Unit> = runCatching {
        require(path.isNotBlank() && path != "/") { "Directory path is required" }
        // File Browser distinguishes a directory from an empty file by the trailing slash.
        val directoryPath = path.trimEnd('/') + "/"
        val connection = open(apiUrl(profile, "/api/resources", directoryPath), "POST").apply {
            setRequestProperty("Accept", "application/json")
            applyAuthorization(this, token)
        }
        val code = connection.responseCode
        require(code in 200..299) { requestError("Unable to create folder", code, connection) }
    }

    /** Deletes resources one by one so an authorization rejection cannot delete later selections. */
    suspend fun delete(profile: ServerProfile, token: String? = null, paths: List<String>): Result<Unit> = runCatching {
        require(paths.isNotEmpty()) { "At least one resource is required" }
        paths.forEach { path ->
            require(path.isNotBlank() && path != "/") { "Resource path is required" }
            val connection = open(apiUrl(profile, "/api/resources", path), "DELETE").apply {
                setRequestProperty("Accept", "application/json")
                applyAuthorization(this, token)
            }
            val code = connection.responseCode
            require(code in 200..299) { requestError("Unable to delete resource", code, connection) }
        }
    }

    suspend fun move(
        profile: ServerProfile,
        token: String? = null,
        resources: List<RemoteResource>,
        destinationDirectory: String,
    ): Result<Unit> = runCatching {
        require(resources.isNotEmpty()) { "At least one resource is required" }
        val destinationParent = destinationDirectory.trim().let { if (it.isEmpty()) "/" else "/" + it.trim('/') }
        resources.forEach { resource ->
            val sourcePath = resource.path + if (resource.isDirectory && !resource.path.endsWith('/')) "/" else ""
            val destinationPath = destinationParent.trimEnd('/') + "/" + resource.name
            val encodedDestination = java.net.URLEncoder.encode(destinationPath, Charsets.UTF_8.name()).replace("+", "%20")
            val url = apiUrl(profile, "/api/resources", sourcePath) +
                "?action=rename&destination=$encodedDestination&override=false&rename=false"
            val connection = open(url, "PATCH").apply {
                setRequestProperty("Accept", "application/json")
                if (!token.isNullOrBlank()) setRequestProperty("X-Auth", token)
            }
            val code = connection.responseCode
            require(code in 200..299) { requestError("Unable to move resource", code, connection) }
        }
    }

    suspend fun copy(
        profile: ServerProfile,
        token: String? = null,
        resources: List<RemoteResource>,
        destinationDirectory: String,
    ): Result<Unit> = runCatching {
        require(resources.isNotEmpty()) { "At least one resource is required" }
        val destinationParent = destinationDirectory.trim().let { if (it.isEmpty()) "/" else "/" + it.trim('/') }
        resources.forEach { resource ->
            val sourcePath = resource.path + if (resource.isDirectory && !resource.path.endsWith('/')) "/" else ""
            val destinationPath = destinationParent.trimEnd('/') + "/" + resource.name
            val encodedDestination = java.net.URLEncoder.encode(destinationPath, Charsets.UTF_8.name()).replace("+", "%20")
            val url = apiUrl(profile, "/api/resources", sourcePath) +
                "?action=copy&destination=$encodedDestination&override=false&rename=false"
            val connection = open(url, "PATCH").apply {
                setRequestProperty("Accept", "application/json")
                applyAuthorization(this, token)
            }
            val code = connection.responseCode
            require(code in 200..299) { requestError("Unable to copy resource", code, connection) }
        }
    }

    suspend fun rename(
        profile: ServerProfile,
        token: String? = null,
        resource: RemoteResource,
        newName: String,
    ): Result<Unit> = runCatching {
        require(newName.isNotBlank() && newName != "." && newName != ".." && '/' !in newName && '\\' !in newName) {
            "A valid resource name is required"
        }
        val sourcePath = resource.path + if (resource.isDirectory && !resource.path.endsWith('/')) "/" else ""
        val parent = resource.path.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
        val destinationPath = parent.trimEnd('/') + "/" + newName.trim()
        val encodedDestination = java.net.URLEncoder.encode(destinationPath, Charsets.UTF_8.name()).replace("+", "%20")
        val url = apiUrl(profile, "/api/resources", sourcePath) +
            "?action=rename&destination=$encodedDestination&override=false&rename=false"
        val connection = open(url, "PATCH").apply {
            setRequestProperty("Accept", "application/json")
            applyAuthorization(this, token)
        }
        val code = connection.responseCode
        require(code in 200..299) { requestError("Unable to rename resource", code, connection) }
    }

    fun thumbnailResult(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
        destination: File,
    ): ApiResult<File> = try {
        val connection = open(apiUrl(profile, "/api/preview/thumb", remotePath), "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Thumbnail unavailable ($code)"))
        destination.parentFile?.mkdirs()
        connection.inputStream.use { input -> destination.outputStream().buffered().use(input::copyTo) }
        ApiResult(code, destination)
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun login(profile: ServerProfile, username: String, password: String): Result<String> = runCatching {
        val connection = open(profile.endpoint + "api/login", "POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(JSONObject().put("username", username).put("password", password).toString().toByteArray()) }
        require(connection.responseCode in 200..299) { "Login failed (${connection.responseCode})" }
        connection.inputStream.bufferedReader().use { it.readText().trim('"', '\n', ' ') }
    }

    fun isReachable(profile: ServerProfile): Boolean = try {
        val connection = (URL(profile.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_500
            readTimeout = 2_500
            instanceFollowRedirects = false
            useCaches = false
        }
        connection.responseCode in 100..599
    } catch (_: Throwable) {
        false
    }

    fun list(profile: ServerProfile, token: String? = null, path: String = "/"): Result<List<RemoteResource>> =
        listResult(profile, token, path).toResult()

    fun listResult(profile: ServerProfile, token: String? = null, path: String = "/"): ApiResult<List<RemoteResource>> =
        listWithPermissionsResult(profile, token, path).map { it.resources }

    fun recursiveListResult(profile: ServerProfile, token: String?, rootPath: String): ApiResult<Map<String, SyncEntry>> = try {
        val root = "/" + rootPath.trim('/')
        val connection = open(apiUrl(profile, "/api/resources/recursive", root), "GET")
        applyAuthorization(connection, token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException(requestError("Unable to scan sync folder", code, connection)))
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        val prefix = root.trimEnd('/') + "/"
        ApiResult(code, buildMap {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val absolute = item.optString("path")
                val relative = absolute.removePrefix(prefix).trim('/')
                if (relative.isEmpty()) continue
                put(relative, SyncEntry(
                    relativePath = relative,
                    directory = item.optBoolean("isDir"),
                    size = item.optLong("size"),
                    modified = parseTimeMillis(item.opt("modified")),
                ))
            }
        })
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun syncChangesResult(
        profile: ServerProfile,
        token: String?,
        rootPath: String,
        cursor: Long,
        waitSeconds: Int = 0,
        bootstrap: Boolean = false,
    ): ApiResult<RemoteChangePage> = try {
        val encodedPath = URLEncoder.encode("/" + rootPath.trim('/'), Charsets.UTF_8.name()).replace("+", "%20")
        val url = profile.endpoint.trimEnd('/') + "/api/sync/changes?cursor=${cursor.coerceAtLeast(0)}&path=$encodedPath&limit=500&wait=${waitSeconds.coerceIn(0, 30)}&bootstrap=$bootstrap"
        val connection = open(url, "GET")
                applyAuthorization(connection, token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException(requestError("Unable to read sync changes", code, connection)))
        val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val changes = body.optJSONArray("changes") ?: JSONArray()
        ApiResult(code, RemoteChangePage(
            cursor = body.optLong("cursor", cursor),
            reset = body.optBoolean("reset"),
            changes = buildList {
                for (index in 0 until changes.length()) changes.getJSONObject(index).let { item ->
                    add(RemoteChange(
                        id = item.optLong("id"), operation = item.optString("operation"), path = item.optString("path"),
                        destination = item.optString("destination").takeIf(String::isNotBlank),
                        directory = item.optBoolean("directory"), size = item.optLong("size"), modified = item.optLong("modified"),
                    ))
                }
            },
        ))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun syncIdentityResult(profile: ServerProfile, token: String?): ApiResult<ServerAccountIdentity> = try {
        val connection = open(profile.endpoint.trimEnd('/') + "/api/sync/identity", "GET")
        applyAuthorization(connection, token)
        val code = connection.responseCode
        if (code !in 200..299) ApiResult(code, error = IOException(requestError("Unable to identify server", code, connection)))
        else {
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            ApiResult(code, ServerAccountIdentity(json.getString("serverId"), json.getLong("userId")))
        }
    } catch (error: Throwable) { ApiResult(-1, error = error) }

    private fun parseTimeMillis(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
        else -> 0L
    }

    fun diskUsageResult(profile: ServerProfile, token: String? = null, path: String = "/"): ApiResult<DiskUsage> = try {
        val connection = open(apiUrl(profile, "/api/usage", path), "GET")
        applyAuthorization(connection, token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException(requestError("Unable to load disk usage", code, connection)))
        val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        ApiResult(code, DiskUsage(total = body.optLong("total").coerceAtLeast(0L), used = body.optLong("used").coerceAtLeast(0L)))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun videoThumbnailResult(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
        destination: File,
    ): ApiResult<File> = try {
        val encodedPath = URLEncoder.encode(remotePath, Charsets.UTF_8.name()).replace("+", "%20")
        val connection = open(profile.endpoint.trimEnd('/') + "/api/video-thumbnail?path=$encodedPath", "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) {
            return ApiResult(code, error = IOException(requestError("Video thumbnail unavailable", code, connection)))
        }
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.part")
        try {
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            }
            check(temporary.length() > 0L) { "Empty video thumbnail" }
            if (destination.exists() && !destination.delete()) throw IOException("Unable to replace video thumbnail")
            check(temporary.renameTo(destination)) { "Unable to publish video thumbnail" }
            ApiResult(code, destination)
        } finally {
            temporary.delete()
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun queueVideoThumbnailResult(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
    ): ApiResult<Unit> {
        val encodedPath = URLEncoder.encode(remotePath, Charsets.UTF_8.name()).replace("+", "%20")
        val result = jsonRequest(
            profile = profile,
            token = token,
            path = "/api/video-thumbnail?path=$encodedPath",
            method = "POST",
        )
        return if (result.code in 200..299) ApiResult(result.code, Unit)
        else ApiResult(result.code, error = result.error)
    }

    fun sharesResult(profile: ServerProfile, token: String? = null, path: String): ApiResult<List<ShareLink>> = try {
        val connection = open(apiUrl(profile, "/api/share", path), "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException(requestError("Unable to load shares", code, connection)))
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        ApiResult(code, buildList {
            for (index in 0 until array.length()) add(shareLinkOf(array.getJSONObject(index)))
        })
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun createShareResult(
        profile: ServerProfile,
        token: String? = null,
        path: String,
        duration: Int,
        unit: ShareDurationUnit,
        password: String,
    ): ApiResult<ShareLink> = try {
        require(duration > 0) { "Share duration must be positive" }
        val connection = open(apiUrl(profile, "/api/share", path), "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            applyAuthorization(this, token)
        }
        val payload = JSONObject()
            .put("password", password)
            .put("expires", duration.toString())
            .put("unit", unit.apiValue)
            .toString()
            .toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(payload) }
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException(requestError("Unable to create share", code, connection)))
        val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        ApiResult(code, shareLinkOf(body))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun deleteShareResult(profile: ServerProfile, token: String? = null, hash: String): ApiResult<Unit> = try {
        require(hash.isNotBlank()) { "Share hash is required" }
        val connection = open(apiUrl(profile, "/api/share", "/$hash"), "DELETE")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) ApiResult(code, error = IOException(requestError("Unable to delete share", code, connection)))
        else ApiResult(code, Unit)
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    /** Reads File Browser v2's authenticated self-user record: `{"perm":{"download", "create", "delete"}}`. */
    fun currentPermissionsResult(profile: ServerProfile, token: String?): ApiResult<ResourcePermissions> = try {
        val userId = currentUserId(token) ?: return ApiResult(-1, error = IOException("Unable to identify the authenticated user"))
        val connection = open(profile.endpoint.trimEnd('/') + "/api/users/$userId", "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Unable to load permissions ($code)"))
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val permissions = permissionObject(response).orEmpty()
        ApiResult(code, ResourcePermissions(
            canDownload = permissionEnabled(permissions, "download"),
            // File Browser v2 uses create permission for both folder creation and multipart upload.
            canUpload = permissionEnabled(permissions, "create"),
            canCreate = permissionEnabled(permissions, "create"),
            canDelete = permissionEnabled(permissions, "delete"),
            canRename = permissionEnabled(permissions, "rename"),
            canShare = permissionEnabled(permissions, "share"),
        ))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun listWithPermissionsResult(profile: ServerProfile, token: String? = null, path: String = "/"): ApiResult<ResourceListing> = try {
        val url = apiUrl(profile, "/api/resources", path)
        val connection = open(url, "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Unable to list files ($code)"))
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val response = body.takeUnless { it.trimStart().startsWith("[") }?.let(::JSONObject)
        val responsePermissions = response?.let(::permissionsOf) ?: ResourcePermissions()
        val array = if (response == null) JSONArray(body) else response.optJSONArray("items") ?: JSONArray()
        ApiResult(code, ResourceListing(buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(RemoteResource(
                    name = item.optString("name"),
                    path = item.optString("path", path),
                    isDirectory = directoryOf(item),
                    size = item.optLong("size"),
                    mimeType = item.optString("mimeType").ifBlank { item.optString("mime") }.takeIf { it.isNotBlank() },
                    permissions = permissionsOf(item, responsePermissions),
                ))
            }
        }, responsePermissions))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun rawUrl(profile: ServerProfile, remotePath: String): String =
        apiUrl(profile, "/api/raw", remotePath)

    fun archiveUrl(profile: ServerProfile, remotePaths: List<String>, algorithm: String): String {
        val supported = setOf("zip", "tar", "targz", "tarbz2", "tarxz", "tarlz4", "tarsz", "tarbr", "tarzst")
        require(algorithm in supported) { "Unsupported archive format" }
        require(remotePaths.isNotEmpty()) { "At least one remote path is required" }
        // File Browser treats a single directory as the raw resource itself. Sending it
        // through the bulk `files` query can produce a valid but empty archive.
        if (remotePaths.size == 1) return rawUrl(profile, remotePaths.single()) + "?algo=$algorithm"
        val files = URLEncoder.encode(remotePaths.joinToString(","), Charsets.UTF_8.name())
        return profile.endpoint.trimEnd('/') + "/api/raw/?files=$files&algo=$algorithm"
    }

    fun readText(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
        maxBytes: Int = 1_000_000,
    ): Result<String> = readTextResult(profile, token, remotePath, maxBytes).toResult()

    fun readTextResult(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
        maxBytes: Int = 1_000_000,
    ): ApiResult<String> = try {
        val connection = open(rawUrl(profile, remotePath), "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Unable to open file ($code)"))
        connection.inputStream.buffered().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (output.size() <= maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            require(output.size() <= maxBytes) { "Text file is too large to preview" }
            ApiResult(code, output.toString(Charsets.UTF_8.name()))
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    private fun open(url: String, method: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 8_000
        readTimeout = 15_000
        useCaches = false
        instanceFollowRedirects = false
    }

    private fun applyAuthorization(connection: HttpURLConnection, token: String?) {
        if (token.isNullOrBlank()) return
        if (token.startsWith(SYNC_TOKEN_PREFIX)) {
            connection.setRequestProperty("X-Sync-Token", token.removePrefix(SYNC_TOKEN_PREFIX))
        } else {
            connection.setRequestProperty("X-Auth", token)
        }
    }

    fun createSyncTokenResult(profile: ServerProfile, token: String?, path: String): ApiResult<String> = try {
        val connection = open(profile.endpoint.trimEnd('/') + "/api/sync/token", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            applyAuthorization(this, token)
        }
        connection.outputStream.use { output -> output.write(JSONObject().put("path", path).toString().toByteArray()) }
        val code = connection.responseCode
        if (code !in 200..299) ApiResult(code, error = IOException(requestError("Unable to authorize background sync", code, connection)))
        else ApiResult(code, JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("token"))
    } catch (error: Throwable) { ApiResult(-1, error = error) }

    fun revokeSyncTokenResult(profile: ServerProfile, token: String): ApiResult<Unit> = try {
        val connection = open(profile.endpoint.trimEnd('/') + "/api/sync/token", "DELETE")
        applyAuthorization(connection, SYNC_TOKEN_PREFIX + token)
        val code = connection.responseCode
        if (code !in 200..299) ApiResult(code, error = IOException("Unable to revoke sync token ($code)")) else ApiResult(code, Unit)
    } catch (error: Throwable) { ApiResult(-1, error = error) }

    private fun apiUrl(profile: ServerProfile, apiPath: String, path: String): String {
        val cleanPath = path.trim().let { if (it.isEmpty() || it == "/") "/" else if (it.startsWith("/")) it else "/$it" }
        return profile.endpoint.trimEnd('/') + apiPath + encodePath(cleanPath)
    }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun permissionsOf(item: JSONObject, inherited: ResourcePermissions = ResourcePermissions()): ResourcePermissions {
        val permissions = item.optJSONObject("permissions") ?: item
        fun allowed(name: String, inheritedValue: Boolean): Boolean = when {
            permissions.has(name) -> permissions.optBoolean(name)
            item !== permissions && item.has(name) -> item.optBoolean(name)
            else -> inheritedValue
        }
        return ResourcePermissions(
            canDownload = allowed("canDownload", inherited.canDownload),
            canUpload = allowed("canUpload", inherited.canUpload),
            canCreate = allowed("canCreate", inherited.canCreate),
            canDelete = allowed("canDelete", inherited.canDelete),
            canRename = allowed("canRename", inherited.canRename),
            canShare = allowed("canShare", inherited.canShare),
        )
    }

    fun currentUserResult(profile: ServerProfile, token: String?): ApiResult<ServerUser> {
        val userId = currentUserId(token)
            ?: return ApiResult(-1, error = IOException("Unable to identify the authenticated user"))
        return userResult(profile, token, userId)
    }

    fun usersResult(profile: ServerProfile, token: String?): ApiResult<List<ServerUser>> = try {
        val connection = open(profile.endpoint.trimEnd('/') + "/api/users", "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException(requestError("Unable to load users", code, connection)))
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        ApiResult(code, buildList {
            for (index in 0 until array.length()) add(ServerStorageCodec.user(array.getJSONObject(index)))
        })
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun userResult(profile: ServerProfile, token: String?, id: Long): ApiResult<ServerUser> = try {
        val connection = open(profile.endpoint.trimEnd('/') + "/api/users/$id", "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException(requestError("Unable to load user", code, connection)))
        ApiResult(code, ServerStorageCodec.user(JSONObject(connection.inputStream.bufferedReader().use { it.readText() })))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun saveUserResult(
        profile: ServerProfile,
        token: String?,
        user: ServerUser,
        newPassword: String = "",
        currentPassword: String = "",
        profileOnly: Boolean = false,
    ): ApiResult<ServerUser> = try {
        val creating = user.id <= 0
        val url = profile.endpoint.trimEnd('/') + "/api/users" + if (creating) "" else "/${user.id}"
        val body = ServerStorageCodec.userMutation(
            user = user,
            newPassword = newPassword,
            creating = creating,
            currentPassword = currentPassword,
            profileOnly = profileOnly,
        )
        val result = jsonRequest(
            profile = profile,
            token = token,
            path = if (creating) "/api/users" else "/api/users/${user.id}",
            method = if (creating) "POST" else "PUT",
            body = body,
        )
        if (result.code !in 200..299) {
            ApiResult(result.code, error = result.error)
        } else {
            val responseUser = result.value
                ?.trim()
                ?.takeIf { it.startsWith("{") }
                ?.let { ServerStorageCodec.user(JSONObject(it)) }
            ApiResult(result.code, responseUser ?: user)
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun deleteUserResult(profile: ServerProfile, token: String?, id: Long, currentPassword: String): ApiResult<Unit> {
        val result = jsonRequest(
            profile, token, "/api/users/$id", "DELETE",
            JSONObject().put("current_password", currentPassword),
        )
        return if (result.code in 200..299) ApiResult(result.code, Unit) else ApiResult(result.code, error = result.error)
    }

    fun adminDirectoriesResult(
        profile: ServerProfile,
        token: String?,
        path: String,
    ): ApiResult<AdminDirectoryListing> = try {
        val encodedPath = URLEncoder.encode(path, Charsets.UTF_8.name()).replace("+", "%20")
        val result = jsonRequest(profile, token, "/api/admin/directories?path=$encodedPath", "GET")
        if (result.code !in 200..299) ApiResult(result.code, error = result.error)
        else ApiResult(result.code, ServerStorageCodec.directory(JSONObject(result.value ?: "{}")))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun createAdminDirectoryResult(
        profile: ServerProfile,
        token: String?,
        parent: String,
        name: String,
    ): ApiResult<AdminDirectoryListing> = try {
        val result = jsonRequest(
            profile,
            token,
            "/api/admin/directories",
            "POST",
            JSONObject().put("parent", parent).put("name", name),
        )
        if (result.code !in 200..299) ApiResult(result.code, error = result.error)
        else ApiResult(result.code, ServerStorageCodec.directory(JSONObject(result.value ?: "{}")))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun resourceOwnersResult(
        profile: ServerProfile,
        token: String?,
        paths: List<String>,
    ): ApiResult<List<String>> = try {
        val result = jsonRequest(
            profile,
            token,
            "/api/resource-owners",
            "POST",
            JSONObject().put("paths", JSONArray(paths)),
        )
        if (result.code !in 200..299) ApiResult(result.code, error = result.error)
        else ApiResult(result.code, ServerStorageCodec.owners(JSONObject(result.value ?: "{}")))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun settingsResult(profile: ServerProfile, token: String?): ApiResult<ServerGlobalSettings> = try {
        val result = jsonRequest(profile, token, "/api/settings", "GET")
        if (result.code !in 200..299) return ApiResult(result.code, error = result.error)
        ApiResult(result.code, globalSettingsOf(JSONObject(result.value ?: "{}")))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun updateSettingsResult(profile: ServerProfile, token: String?, settings: ServerGlobalSettings): ApiResult<ServerGlobalSettings> = try {
        val body = JSONObject(settings.rawJson.ifBlank { "{}" })
        body.put("signup", settings.signup)
        body.put("createUserDir", settings.createUserDir)
        body.put("hideLoginButton", settings.hideLoginButton)
        body.put("userHomeBasePath", settings.userHomeBasePath)
        body.put("minimumPasswordLength", settings.minimumPasswordLength)
        body.optJSONObject("branding")?.also { branding ->
            branding.put("disableExternal", settings.disableExternalLinks)
            branding.put("disableUsedPercentage", settings.disableUsedPercentage)
            branding.put("theme", settings.theme)
            branding.put("name", settings.instanceName)
            branding.put("files", settings.brandingDirectory)
        } ?: body.put("branding", JSONObject()
            .put("disableExternal", settings.disableExternalLinks)
            .put("disableUsedPercentage", settings.disableUsedPercentage)
            .put("theme", settings.theme)
            .put("name", settings.instanceName)
            .put("files", settings.brandingDirectory))
        body.optJSONObject("tus")?.also { tus ->
            tus.put("chunkSize", settings.chunkSizeBytes)
            tus.put("retryCount", settings.retryCount)
        } ?: body.put("tus", JSONObject().put("chunkSize", settings.chunkSizeBytes).put("retryCount", settings.retryCount))
        val result = jsonRequest(profile, token, "/api/settings", "PUT", body)
        if (result.code in 200..299) ApiResult(result.code, settings.copy(rawJson = body.toString()))
        else ApiResult(result.code, error = result.error)
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun allSharesResult(profile: ServerProfile, token: String?): ApiResult<List<ShareLink>> = try {
        val connection = open(profile.endpoint.trimEnd('/') + "/api/shares", "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException(requestError("Unable to load shares", code, connection)))
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        ApiResult(code, buildList { for (index in 0 until array.length()) add(shareLinkOf(array.getJSONObject(index))) })
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    private fun shareLinkOf(item: JSONObject): ShareLink = ShareLink(
        hash = item.optString("hash"),
        path = item.optString("path"),
        expire = item.optLong("expire"),
        hasPassword = item.optBoolean("hasPassword"),
        userId = item.optLong("userID", item.optLong("userId")),
        username = item.optString("username"),
    )

    private fun globalSettingsOf(item: JSONObject): ServerGlobalSettings {
        val branding = item.optJSONObject("branding") ?: JSONObject()
        val tus = item.optJSONObject("tus") ?: JSONObject()
        return ServerGlobalSettings(
            signup = item.optBoolean("signup"),
            createUserDir = item.optBoolean("createUserDir"),
            hideLoginButton = item.optBoolean("hideLoginButton"),
            userHomeBasePath = item.optString("userHomeBasePath", "/users"),
            minimumPasswordLength = item.optInt("minimumPasswordLength", 3),
            disableExternalLinks = branding.optBoolean("disableExternal"),
            disableUsedPercentage = branding.optBoolean("disableUsedPercentage"),
            theme = branding.optString("theme", "dark"),
            instanceName = branding.optString("name"),
            brandingDirectory = branding.optString("files"),
            chunkSizeBytes = tus.optLong("chunkSize", 20L * 1024L * 1024L),
            retryCount = tus.optInt("retryCount", 5),
            rawJson = item.toString(),
        )
    }

    private fun jsonRequest(
        profile: ServerProfile,
        token: String?,
        path: String,
        method: String,
        body: JSONObject? = null,
        actorPassword: String = "",
    ): ApiResult<String> = try {
        val connection = open(profile.endpoint.trimEnd('/') + path, method).apply {
            setRequestProperty("Accept", "application/json")
            applyAuthorization(this, token)
            if (actorPassword.isNotBlank()) {
                setRequestProperty(
                    "X-Password",
                    URLEncoder.encode(actorPassword, Charsets.UTF_8.name()).replace("+", "%20"),
                )
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) ApiResult(code, error = IOException(requestError("Request failed", code, connection)))
        else ApiResult(code, connection.inputStream.bufferedReader().use { it.readText() })
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    private fun directoryOf(item: JSONObject): Boolean {
        val type = item.optString("type").lowercase()
        val mime = item.optString("mimeType").ifBlank { item.optString("mime") }.lowercase()
        return item.optBoolean("isDir") ||
            item.optBoolean("isDirectory") ||
            type in setOf("dir", "directory", "folder") ||
            mime == "inode/directory"
    }

    private fun requestError(prefix: String, code: Int, connection: HttpURLConnection): String {
        val message = connection.errorStream?.bufferedReader()?.use { it.readText().trim() }.orEmpty()
        return if (message.isBlank()) "$prefix ($code)" else "$prefix ($code): $message"
    }

    private fun currentUserId(token: String?): Long? = runCatching {
        val payload = token?.split('.')?.getOrNull(1) ?: throw IllegalArgumentException("Missing JWT payload")
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        Regex("\\\"id\\\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLong()?.takeIf { it > 0 }
    }.getOrNull()

    private fun permissionEnabled(permissions: String, name: String): Boolean =
        Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*true\\b").containsMatchIn(permissions)

    /** Extracts only a JSON object assigned to the official `perm` field; malformed values stay denied. */
    private fun permissionObject(response: String): String? {
        val field = Regex("\\\"perm\\\"\\s*:").find(response) ?: return null
        val start = response.indexOf('{', field.range.last + 1)
        if (start < 0) return null
        var depth = 0
        for (index in start until response.length) {
            when (response[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return response.substring(start, index + 1)
                }
            }
        }
        return null
    }

    fun comicManifestResult(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
    ): ApiResult<ComicManifest> = try {
        val encodedPath = encodePath(remotePath)
        val url = profile.endpoint.trimEnd('/') + "/api/comic/manifest?path=$encodedPath"
        val connection = open(url, "GET")
        applyAuthorization(connection, token)
        val code = connection.responseCode
        if (code !in 200..299) ApiResult(code, error = IOException("Unable to get comic manifest ($code)"))
        else {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val title = json.optString("title", remotePath.substringAfterLast('/'))
            val pagesJson = json.optJSONArray("pages") ?: JSONArray()
            val pages = (0 until pagesJson.length()).map { i ->
                val p = pagesJson.getJSONObject(i)
                ComicPage(index = p.getInt("index"), name = p.optString("name", "Page $i"))
            }
            val count = json.optInt("count", pages.size)
            ApiResult(code, ComicManifest(title, pages, count))
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun saveResourceResult(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
        content: String,
    ): ApiResult<Unit> = try {
        val url = apiUrl(profile, "/api/resources", remotePath)
        val connection = open(url, "PUT")
        applyAuthorization(connection, token)
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        connection.doOutput = true
        val bytes = content.toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        if (code in 200..299) {
            ApiResult(code, Unit)
        } else if (code == 404 || code == 405) {
            // Fallback to POST with override=true (which handles both new and existing files)
            val postUrl = apiUrl(profile, "/api/resources", remotePath) + "?override=true"
            val postConn = open(postUrl, "POST")
            applyAuthorization(postConn, token)
            postConn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            postConn.doOutput = true
            postConn.outputStream.use { it.write(bytes) }
            val postCode = postConn.responseCode
            if (postCode in 200..299) {
                ApiResult(postCode, Unit)
            } else {
                val errBody = postConn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                ApiResult(postCode, error = IOException("Unable to save file ($postCode): $errBody"))
            }
        } else {
            val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            ApiResult(code, error = IOException("Unable to save file ($code): $errBody"))
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun downloadComicPageResult(
        profile: ServerProfile,
        token: String?,
        remotePath: String,
        pageIndex: Int,
        destination: File,
    ): ApiResult<File> = try {
        val encodedPath = encodePath(remotePath)
        val url = profile.endpoint.trimEnd('/') + "/api/comic/page?path=$encodedPath&page=$pageIndex"
        val connection = open(url, "GET")
        applyAuthorization(connection, token)
        val code = connection.responseCode
        if (code !in 200..299) ApiResult(code, error = IOException("Unable to load comic page ($code)"))
        else {
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, ".page-${destination.name}.tmp")
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            if (temp.exists()) {
                if (destination.exists()) destination.delete()
                temp.renameTo(destination)
            }
            ApiResult(code, destination)
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    companion object {
        private const val SYNC_TOKEN_PREFIX = "sync:"
        /** Exposed for tests and callers which need the exact File Browser resources destination. */
        fun encodedResourcePath(path: String): String {
            val cleanPath = path.trim().let { if (it.isEmpty() || it == "/") "/" else if (it.startsWith("/")) it else "/$it" }
            return "/api/resources" + cleanPath.split('/').joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
            }
        }
    }
}

/** Streaming single-file multipart writer. It never buffers file content or permits progress past [fileSize]. */
object MultipartEncoder {
    private const val CRLF = "\r\n"

    fun escapeFilename(fileName: String): String = buildString(fileName.length) {
        fileName.forEach { char ->
            when (char) {
                '"' -> append("%22")
                '\\' -> append("%5C")
                '\r' -> append("%0D")
                '\n' -> append("%0A")
                else -> append(char)
            }
        }
    }

    fun encodedSize(boundary: String, fileName: String, fileSize: Long): Long =
        prefix(boundary, fileName).size.toLong() + fileSize + suffix(boundary).size

    fun write(
        output: OutputStream,
        boundary: String,
        fileName: String,
        input: InputStream,
        fileSize: Long,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null,
    ): Long {
        require(fileSize >= 0) { "File size cannot be negative" }
        val head = prefix(boundary, fileName)
        val tail = suffix(boundary)
        val total = head.size.toLong() + fileSize + tail.size
        var sent = 0L
        output.write(head)
        sent += head.size
        onProgress?.invoke(sent, total)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > fileSize - copied) throw IOException("Input exceeded declared file size")
            output.write(buffer, 0, count)
            copied += count
            sent += count
            onProgress?.invoke(sent, total)
        }
        if (copied != fileSize) throw IOException("Input size did not match declared file size")
        output.write(tail)
        sent += tail.size
        onProgress?.invoke(sent, total)
        return sent
    }

    private fun prefix(boundary: String, fileName: String): ByteArray = (
        "--$boundary$CRLF" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"${escapeFilename(fileName)}\"$CRLF" +
            "Content-Type: application/octet-stream$CRLF$CRLF"
        ).toByteArray(Charsets.UTF_8)

    private fun suffix(boundary: String): ByteArray = "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)
}
