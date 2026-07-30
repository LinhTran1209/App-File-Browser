package com.j2team.fileserver.feature.preview

import kotlinx.coroutines.delay

internal suspend fun fetchSharedVideoThumbnail(
    maxFetchAttempts: Int = 4,
    retryDelayMs: Long = 750,
    fetch: suspend () -> Boolean,
    queue: suspend () -> Boolean,
): Boolean {
    require(maxFetchAttempts > 0) { "At least one fetch attempt is required" }
    if (fetch()) return true
    if (!queue()) return false
    repeat(maxFetchAttempts - 1) {
        if (retryDelayMs > 0) delay(retryDelayMs)
        if (fetch()) return true
    }
    return false
}
