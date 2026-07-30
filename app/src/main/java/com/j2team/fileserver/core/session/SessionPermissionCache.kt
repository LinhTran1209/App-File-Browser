package com.j2team.fileserver.core.session

import com.j2team.fileserver.core.model.ResourcePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SessionPermissionCache {
    private val values = ConcurrentHashMap<String, ResourcePermissions>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun getOrLoad(
        profileId: String,
        loader: suspend () -> ResourcePermissions,
    ): ResourcePermissions {
        values[profileId]?.let { return it }
        return locks.computeIfAbsent(profileId) { Mutex() }.withLock {
            values[profileId] ?: loader().also { values[profileId] = it }
        }
    }

    fun clear(profileId: String) {
        values.remove(profileId)
        locks.remove(profileId)
    }
}
