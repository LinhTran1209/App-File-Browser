package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ResourcePermissions
import com.j2team.fileserver.core.model.ResourceListing
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.ApiResult
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
import java.util.Base64

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
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Download failed ($code)"))

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile ?: File("."), ".${destination.name}.part")
        val total = connection.contentLengthLong
        var copied = 0L
        try {
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
    fun downloadToResult(
        profile: ServerProfile,
        token: String? = null,
        remotePath: String,
        openDestination: () -> OutputStream,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): ApiResult<Unit> = try {
        require(remotePath.isNotBlank()) { "Remote path is required" }
        val connection = open(rawUrl(profile, remotePath), "GET")
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Auth", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Download failed ($code)"))
        val total = connection.contentLengthLong
        var copied = 0L
        connection.inputStream.use { input ->
            openDestination().use { output ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onProgress?.invoke(copied, total)
                }
            }
        }
        ApiResult(code, Unit)
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    /** Uploads one local file as multipart/form-data to a remote directory. */
    fun upload(
        profile: ServerProfile,
        token: String? = null,
        parentPath: String = "/",
        file: File,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Result<String> = runCatching {
        require(file.isFile) { "Upload file does not exist: ${file.name}" }
        val boundary = "----FileServer${System.currentTimeMillis()}"
        val connection = open(apiUrl(profile, "/api/resources", parentPath), "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("X-Auth", token)
        }
        val total = MultipartEncoder.encodedSize(boundary, file.name, file.length())
        connection.setFixedLengthStreamingMode(total)
        connection.outputStream.use { output ->
            FileInputStream(file).use { input ->
                MultipartEncoder.write(output, boundary, file.name, input, file.length(), onProgress)
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        require(code in 200..299) { "Upload failed ($code)" }
        body
    }

    suspend fun createDirectory(profile: ServerProfile, token: String? = null, path: String): Result<Unit> = runCatching {
        require(path.isNotBlank() && path != "/") { "Directory path is required" }
        val connection = open(apiUrl(profile, "/api/resources", path), "POST").apply {
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("X-Auth", token)
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
                if (!token.isNullOrBlank()) setRequestProperty("X-Auth", token)
            }
            val code = connection.responseCode
            require(code in 200..299) { requestError("Unable to delete resource", code, connection) }
        }
    }

    fun login(profile: ServerProfile, username: String, password: String): Result<String> = runCatching {
        val connection = open(profile.endpoint + "api/login", "POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(JSONObject().put("username", username).put("password", password).toString().toByteArray()) }
        require(connection.responseCode in 200..299) { "Login failed (${connection.responseCode})" }
        connection.inputStream.bufferedReader().use { it.readText().trim('"', '\n', ' ') }
    }

    fun list(profile: ServerProfile, token: String? = null, path: String = "/"): Result<List<RemoteResource>> =
        listResult(profile, token, path).toResult()

    fun listResult(profile: ServerProfile, token: String? = null, path: String = "/"): ApiResult<List<RemoteResource>> =
        listWithPermissionsResult(profile, token, path).map { it.resources }

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
                    isDirectory = item.optBoolean("isDir"),
                    size = item.optLong("size"),
                    permissions = permissionsOf(item, responsePermissions),
                ))
            }
        }, responsePermissions))
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun rawUrl(profile: ServerProfile, remotePath: String): String =
        apiUrl(profile, "/api/raw", remotePath)

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
    }

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
        )
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

    companion object {
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
