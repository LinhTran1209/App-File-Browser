package com.j2team.fileserver.core.session

import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ResourceListing
import com.j2team.fileserver.core.model.ResourcePermissions
import com.j2team.fileserver.core.model.DiskUsage
import com.j2team.fileserver.core.model.ShareDurationUnit
import com.j2team.fileserver.core.model.ShareLink
import com.j2team.fileserver.core.model.ServerGlobalSettings
import com.j2team.fileserver.core.model.ServerUser
import com.j2team.fileserver.core.model.AdminDirectoryListing
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.core.network.PreviewProbe
import com.j2team.fileserver.feature.sync.RemoteChangePage
import com.j2team.fileserver.feature.sync.SyncEntry
import com.j2team.fileserver.feature.sync.ServerIdentityStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class SessionRepository(
    private val secretStore: SecretStore,
    private val transport: FileBrowserClient,
    private val tokenStore: MutableMap<String, String> = ConcurrentHashMap(),
    private val identityStore: ServerIdentityStore? = null,
) {
    private val permissionCache = SessionPermissionCache()

    fun isReachable(profile: ServerProfile): Boolean = transport.isReachable(profile)
    suspend fun open(profile: ServerProfile): Result<AuthenticatedSession> =
        authenticated(profile) { token ->
            transport.listResult(profile, token, profile.basePath).map { Unit }
        }.map {
            bindIdentity(profile, tokenStore[profile.id].orEmpty())
            AuthenticatedSession(profile, tokenStore[profile.id].orEmpty())
        }

    suspend fun login(
        profile: ServerProfile,
        username: String,
        password: CharArray,
    ): Result<AuthenticatedSession> = runCatching {
        val passwordText = password.concatToString()
        try {
            val token = transport.login(profile, username, passwordText).getOrThrow()
            secretStore.put(profile.id, StoredCredential(username, password))
            permissionCache.clear(profile.id)
            tokenStore[profile.id] = token
            bindIdentity(profile, token)
            AuthenticatedSession(profile, token)
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun <T> authenticated(
        profile: ServerProfile,
        call: suspend (String) -> ApiResult<T>,
    ): Result<T> = SessionPolicy(object : SessionPolicy.TokenRefresher {
        override suspend fun renew(): Result<String> = renew(profile)
    }).execute(tokenStore[profile.id].orEmpty(), call)

    suspend fun list(profile: ServerProfile, path: String): Result<List<RemoteResource>> =
        authenticated(profile) { token -> transport.listResult(profile, token, path) }

    suspend fun recursiveList(profile: ServerProfile, path: String): Result<Map<String, SyncEntry>> =
        authenticated(profile) { token -> transport.recursiveListResult(profile, token, path) }

    suspend fun syncChanges(profile: ServerProfile, path: String, cursor: Long, waitSeconds: Int = 0, bootstrap: Boolean = false): Result<RemoteChangePage> =
        authenticated(profile) { token -> transport.syncChangesResult(profile, token, path, cursor, waitSeconds, bootstrap) }

    suspend fun createSyncToken(profile: ServerProfile, path: String): Result<String> =
        authenticated(profile) { token -> transport.createSyncTokenResult(profile, token, path) }

    suspend fun currentPermissions(profile: ServerProfile): Result<ResourcePermissions> =
        authenticated(profile) { token -> permissionResult(profile, token) }

    suspend fun listWithPermissions(profile: ServerProfile, path: String): Result<ResourceListing> =
        authenticated(profile) { token ->
            val capabilities = permissionResult(profile, token)
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

    private fun bindIdentity(profile: ServerProfile, token: String) {
        val store = identityStore ?: return
        transport.syncIdentityResult(profile, token).value?.let {
            store.put(profile.id, it)
            store.linkLegacyFolders(profile, it, transport)
        }
    }

    suspend fun downloadArchiveTo(
        profile: ServerProfile,
        remotePaths: List<String>,
        algorithm: String,
        openDestination: () -> OutputStream,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<Unit> = authenticated(profile) { token ->
        transport.downloadArchiveToResult(profile, token, remotePaths, algorithm, openDestination, onProgress)
    }

    suspend fun previewProbe(profile: ServerProfile, remotePath: String): Result<PreviewProbe> =
        authenticated(profile) { token -> transport.previewProbeResult(profile, token, remotePath) }

    suspend fun readText(profile: ServerProfile, remotePath: String): Result<String> =
        authenticated(profile) { token -> transport.readTextResult(profile, token, remotePath) }

    /**
     * Returns the active token without probing an unrelated directory first.
     * The media request itself is the authoritative authentication check and can
     * safely trigger one renewal when the server answers with 401/403.
     */
    suspend fun streamingToken(profile: ServerProfile): Result<String> =
        tokenStore[profile.id]?.takeIf { it.isNotBlank() }?.let(Result.Companion::success)
            ?: renew(profile)

    /** A rejected Media3 raw GET can safely be prepared again after this renewal. */
    suspend fun renewStreamingToken(profile: ServerProfile): Result<String> = renew(profile)

    fun rawUrl(profile: ServerProfile, remotePath: String): String = transport.rawUrl(profile, remotePath)

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

    suspend fun move(profile: ServerProfile, resources: List<RemoteResource>, destinationDirectory: String): Result<Unit> =
        mutationToken(profile).fold(
            onSuccess = { token -> transport.move(profile, token, resources, destinationDirectory) },
            onFailure = { error -> Result.failure(error) },
        )

    suspend fun rename(profile: ServerProfile, resource: RemoteResource, newName: String): Result<Unit> =
        mutationToken(profile).fold(
            onSuccess = { token -> transport.rename(profile, token, resource, newName) },
            onFailure = { error -> Result.failure(error) },
        )

    suspend fun thumbnail(profile: ServerProfile, remotePath: String, destination: File): Result<File> =
        authenticated(profile) { token -> transport.thumbnailResult(profile, token, remotePath, destination) }

    suspend fun cachedVideoThumbnail(profile: ServerProfile, remotePath: String, destination: File): Result<File> =
        authenticated(profile) { token ->
            transport.videoThumbnailResult(profile, token, remotePath, destination)
        }

    suspend fun diskUsage(profile: ServerProfile, path: String): Result<DiskUsage> =
        authenticated(profile) { token -> transport.diskUsageResult(profile, token, path) }

    suspend fun shares(profile: ServerProfile, path: String): Result<List<ShareLink>> =
        authenticated(profile) { token -> transport.sharesResult(profile, token, path) }

    suspend fun currentUser(profile: ServerProfile): Result<ServerUser> =
        authenticated(profile) { token -> transport.currentUserResult(profile, token) }

    suspend fun users(profile: ServerProfile): Result<List<ServerUser>> =
        authenticated(profile) { token -> transport.usersResult(profile, token) }

    suspend fun user(profile: ServerProfile, id: Long): Result<ServerUser> =
        authenticated(profile) { token -> transport.userResult(profile, token, id) }

    suspend fun adminDirectories(profile: ServerProfile, path: String): Result<AdminDirectoryListing> =
        authenticated(profile) { token -> transport.adminDirectoriesResult(profile, token, path) }

    suspend fun createAdminDirectory(
        profile: ServerProfile,
        parent: String,
        name: String,
    ): Result<AdminDirectoryListing> =
        authenticated(profile) { token -> transport.createAdminDirectoryResult(profile, token, parent, name) }

    suspend fun resourceOwners(profile: ServerProfile, paths: List<String>): Result<List<String>> =
        authenticated(profile) { token -> transport.resourceOwnersResult(profile, token, paths) }

    suspend fun saveUser(
        profile: ServerProfile,
        user: ServerUser,
        newPassword: String = "",
        currentPassword: String = "",
        profileOnly: Boolean = false,
    ): Result<ServerUser> {
        val storedCredential = if (currentPassword.isBlank()) secretStore.get(profile.id) else null
        val requestPassword = currentPassword.ifBlank {
            storedCredential?.password?.concatToString().orEmpty()
        }
        val result = try {
            authenticated(profile) { token ->
                transport.saveUserResult(profile, token, user, newPassword, requestPassword, profileOnly)
            }
        } finally {
            storedCredential?.password?.fill('\u0000')
        }
        if (result.isSuccess && profileOnly && newPassword.isNotBlank()) {
            val existing = secretStore.get(profile.id)
            try {
                val username = existing?.username?.takeIf { it.isNotBlank() } ?: user.username
                secretStore.put(profile.id, StoredCredential(username, newPassword.toCharArray()))
            } finally {
                existing?.password?.fill('\u0000')
            }
        }
        return result
    }

    private suspend fun permissionResult(
        profile: ServerProfile,
        token: String,
    ): ApiResult<ResourcePermissions> = try {
        val permissions = permissionCache.getOrLoad(profile.id) {
            val response = transport.currentPermissionsResult(profile, token)
            if (response.code !in 200..299) {
                throw response.error ?: IOException("Unable to load permissions (${response.code})")
            }
            response.value ?: ResourcePermissions()
        }
        ApiResult(200, permissions)
    } catch (error: Throwable) {
        ApiResult(-1, error = error)
    }

    suspend fun deleteUser(profile: ServerProfile, id: Long, currentPassword: String = ""): Result<Unit> {
        val storedCredential = if (currentPassword.isBlank()) secretStore.get(profile.id) else null
        val requestPassword = currentPassword.ifBlank {
            storedCredential?.password?.concatToString().orEmpty()
        }
        return try {
            authenticated(profile) { token ->
                transport.deleteUserResult(profile, token, id, requestPassword)
            }
        } finally {
            storedCredential?.password?.fill('\u0000')
        }
    }

    suspend fun serverSettings(profile: ServerProfile): Result<ServerGlobalSettings> =
        authenticated(profile) { token -> transport.settingsResult(profile, token) }

    suspend fun updateServerSettings(profile: ServerProfile, settings: ServerGlobalSettings): Result<ServerGlobalSettings> =
        authenticated(profile) { token -> transport.updateSettingsResult(profile, token, settings) }

    suspend fun allShares(profile: ServerProfile): Result<List<ShareLink>> =
        authenticated(profile) { token -> transport.allSharesResult(profile, token) }

    suspend fun createShare(
        profile: ServerProfile,
        path: String,
        duration: Int,
        unit: ShareDurationUnit,
        password: String,
    ): Result<ShareLink> = mutationToken(profile).fold(
        onSuccess = { token -> transport.createShareResult(profile, token, path, duration, unit, password).toResult() },
        onFailure = { error -> Result.failure(error) },
    )

    suspend fun deleteShare(profile: ServerProfile, hash: String): Result<Unit> =
        mutationToken(profile).fold(
            onSuccess = { token -> transport.deleteShareResult(profile, token, hash).toResult() },
            onFailure = { error -> Result.failure(error) },
        )

    fun shareUrl(profile: ServerProfile, hash: String): String =
        profile.endpoint.trimEnd('/') + "/share/" + hash

    suspend fun requestVideoThumbnail(profile: ServerProfile, remotePath: String): Result<Unit> =
        authenticated(profile) { token ->
            transport.queueVideoThumbnailResult(profile, token, remotePath)
        }

    /**
     * Refreshes authentication with a safe read first, then sends the upload exactly once.
     * The upload itself is never replayed because its acceptance cannot be determined safely.
     */
    suspend fun uploadOnce(
        profile: ServerProfile,
        parentPath: String,
        file: File,
        remoteName: String = file.name,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null,
    ): Result<String> {
        val token = mutationToken(profile).getOrElse { return Result.failure(it) }
        return transport.upload(profile, token, parentPath, file, remoteName, onProgress)
    }

    private suspend fun mutationToken(profile: ServerProfile): Result<String> =
        authenticated(profile) { candidate ->
            transport.listResult(profile, candidate, profile.basePath).map { candidate }
        }

    private fun renew(profile: ServerProfile): Result<String> {
        val credential = secretStore.get(profile.id)
            ?: return Result.failure(LoginRequiredException())
        return try {
            transport.login(profile, credential.username, credential.password.concatToString())
                .onSuccess { tokenStore[profile.id] = it }
        } finally {
            credential.password.fill('\u0000')
        }
    }

    fun clear(profileId: String) {
        tokenStore.remove(profileId)
        secretStore.delete(profileId)
    }

    fun clearProcessSession() {
        tokenStore.clear()
        secretStore.clearAll()
    }
}
