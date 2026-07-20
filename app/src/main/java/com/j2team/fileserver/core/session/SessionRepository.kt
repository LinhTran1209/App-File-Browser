package com.j2team.fileserver.core.session

import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ResourceListing
import com.j2team.fileserver.core.model.ResourcePermissions
import com.j2team.fileserver.core.network.FileBrowserClient
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class SessionRepository(
    private val secretStore: SecretStore,
    private val transport: FileBrowserClient,
    private val tokenStore: MutableMap<String, String> = ConcurrentHashMap(),
) {
    suspend fun open(profile: ServerProfile): Result<AuthenticatedSession> =
        authenticated(profile) { token ->
            transport.listResult(profile, token, profile.basePath).map { Unit }
        }.map { AuthenticatedSession(profile, tokenStore[profile.id].orEmpty()) }

    suspend fun login(
        profile: ServerProfile,
        username: String,
        password: CharArray,
    ): Result<AuthenticatedSession> = runCatching {
        val passwordText = password.concatToString()
        try {
            val token = transport.login(profile, username, passwordText).getOrThrow()
            secretStore.put(profile.id, StoredCredential(username, password))
            tokenStore[profile.id] = token
            AuthenticatedSession(profile, token)
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun <T> authenticated(
        profile: ServerProfile,
        call: suspend (String) -> ApiResult<T>,
    ): Result<T> = SessionPolicy(object : SessionPolicy.TokenRefresher {
        override suspend fun renew(): Result<String> {
            val credential = secretStore.get(profile.id)
                ?: return Result.failure(LoginRequiredException())
            return try {
                transport.login(profile, credential.username, credential.password.concatToString())
                    .onSuccess { tokenStore[profile.id] = it }
            } finally {
                credential.password.fill('\u0000')
            }
        }
    }).execute(tokenStore[profile.id].orEmpty(), call)

    suspend fun list(profile: ServerProfile, path: String): Result<List<RemoteResource>> =
        authenticated(profile) { token -> transport.listResult(profile, token, path) }

    suspend fun currentPermissions(profile: ServerProfile): Result<ResourcePermissions> =
        authenticated(profile) { token -> transport.currentPermissionsResult(profile, token) }

    suspend fun listWithPermissions(profile: ServerProfile, path: String): Result<ResourceListing> =
        authenticated(profile) { token ->
            val capabilities = transport.currentPermissionsResult(profile, token)
            if (capabilities.code !in 200..299) return@authenticated ApiResult(capabilities.code, error = capabilities.error)
            val permission = capabilities.value ?: ResourcePermissions()
            transport.listWithPermissionsResult(profile, token, path).map { listing ->
                listing.copy(
                    resources = listing.resources.map { it.copy(permissions = permission) },
                    directoryPermissions = permission,
                )
            }
        }

    suspend fun download(
        profile: ServerProfile,
        remotePath: String,
        destination: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<File> = authenticated(profile) { token ->
        transport.downloadResult(profile, token, remotePath, destination, onProgress)
    }

    suspend fun downloadTo(
        profile: ServerProfile,
        remotePath: String,
        openDestination: () -> OutputStream,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<Unit> = authenticated(profile) { token ->
        transport.downloadToResult(profile, token, remotePath, openDestination, onProgress)
    }

    suspend fun readText(profile: ServerProfile, remotePath: String): Result<String> =
        authenticated(profile) { token -> transport.readTextResult(profile, token, remotePath) }

    /** Authenticate with a safe list request before sending a non-idempotent mutation once. */
    suspend fun createDirectory(profile: ServerProfile, path: String): Result<Unit> =
        mutationToken(profile).fold(
            onSuccess = { token -> transport.createDirectory(profile, token, path) },
            onFailure = { error -> Result.failure(error) },
        )

    /** Deletes are deliberately never replayed after the first server request. */
    suspend fun delete(profile: ServerProfile, paths: List<String>): Result<Unit> =
        mutationToken(profile).fold(
            onSuccess = { token -> transport.delete(profile, token, paths) },
            onFailure = { error -> Result.failure(error) },
        )

    /**
     * Refreshes authentication with a safe read first, then sends the upload exactly once.
     * The upload itself is never replayed because its acceptance cannot be determined safely.
     */
    suspend fun uploadOnce(
        profile: ServerProfile,
        parentPath: String,
        file: File,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<String> {
        val token = mutationToken(profile).getOrElse { return Result.failure(it) }
        return transport.upload(profile, token, parentPath, file, onProgress)
    }

    private suspend fun mutationToken(profile: ServerProfile): Result<String> =
        authenticated(profile) { candidate ->
            transport.listResult(profile, candidate, profile.basePath).map { candidate }
        }

    fun clear(profileId: String) {
        tokenStore.remove(profileId)
        secretStore.delete(profileId)
    }
}
