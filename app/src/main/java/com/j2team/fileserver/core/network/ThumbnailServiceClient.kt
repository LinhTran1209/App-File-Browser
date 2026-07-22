package com.j2team.fileserver.core.network

import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.session.ApiResult
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val THUMBNAIL_SERVICE_PORT = 8890

/** Optional LAN companion. Failures never affect browsing or media playback. */
class ThumbnailServiceClient {
    fun cachedThumbnailResult(
        profile: ServerProfile,
        token: String,
        remotePath: String,
        destination: File,
    ): ApiResult<File> = try {
        val connection = open(profile, remotePath, "GET", token)
        val code = connection.responseCode
        if (code !in 200..299) return ApiResult(code, error = IOException("Video thumbnail unavailable ($code)"))
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.part")
        try {
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            }
            check(temporary.length() > 0L) { "Empty video thumbnail" }
            check(temporary.renameTo(destination)) { "Unable to publish video thumbnail" }
            ApiResult(code, destination)
        } finally {
            temporary.delete()
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    fun requestThumbnailResult(
        profile: ServerProfile,
        token: String,
        remotePath: String,
    ): ApiResult<Unit> = try {
        val connection = open(profile, remotePath, "POST", token).apply { doOutput = true }
        connection.outputStream.use { }
        val code = connection.responseCode
        if (code !in 200..299) {
            ApiResult(code, error = IOException("Unable to queue video thumbnail ($code)"))
        } else {
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (input.read(buffer) != -1) {
                    // Drain the small response so HttpURLConnection can release its socket.
                }
            }
            ApiResult(code, Unit)
        }
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    private fun open(
        profile: ServerProfile,
        remotePath: String,
        method: String,
        token: String,
    ): HttpURLConnection {
        val encodedPath = URLEncoder.encode(remotePath, Charsets.UTF_8.name()).replace("+", "%20")
        return (URL("http://${profile.host}:$THUMBNAIL_SERVICE_PORT/v1/thumbnail?path=$encodedPath")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = if (method == "GET") 5_000 else 3_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("X-Auth", token)
        }
    }
}
