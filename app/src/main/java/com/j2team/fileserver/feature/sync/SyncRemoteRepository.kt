package com.j2team.fileserver.feature.sync

import com.j2team.fileserver.core.model.DiskUsage
import com.j2team.fileserver.core.model.RemoteResource
import com.j2team.fileserver.core.model.ServerProfile
import com.j2team.fileserver.core.network.FileBrowserClient
import java.io.File
import java.io.OutputStream

class SyncRemoteRepository(private val transport: FileBrowserClient, rawToken: String) {
    private val token = "sync:$rawToken"

    suspend fun recursiveList(profile: ServerProfile, path: String): Result<Map<String, SyncEntry>> =
        transport.recursiveListResult(profile, token, path).toResult()

    suspend fun changes(profile: ServerProfile, path: String, cursor: Long, waitSeconds: Int = 0, bootstrap: Boolean = false): Result<RemoteChangePage> =
        transport.syncChangesResult(profile, token, path, cursor, waitSeconds, bootstrap).toResult()

    fun identity(profile: ServerProfile): Result<ServerAccountIdentity> = transport.syncIdentityResult(profile, token).toResult()

    suspend fun diskUsage(profile: ServerProfile, path: String): Result<DiskUsage> =
        transport.diskUsageResult(profile, token, path).toResult()

    suspend fun createDirectory(profile: ServerProfile, path: String): Result<Unit> = transport.createDirectory(profile, token, path)
    suspend fun delete(profile: ServerProfile, paths: List<String>): Result<Unit> = transport.delete(profile, token, paths)
    suspend fun rename(profile: ServerProfile, resource: RemoteResource, newName: String): Result<Unit> = transport.rename(profile, token, resource, newName)

    suspend fun upload(profile: ServerProfile, parent: String, file: File, name: String): Result<String> =
        transport.upload(profile, token, parent, file, name)

    suspend fun download(profile: ServerProfile, path: String, openDestination: () -> OutputStream): Result<Unit> =
        transport.downloadToResult(profile, token, path, openDestination).toResult()

    fun revoke(profile: ServerProfile): Result<Unit> = transport.revokeSyncTokenResult(profile, token.removePrefix("sync:")).toResult()
}
