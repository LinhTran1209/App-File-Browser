package com.j2team.fileserver.core.model

data class DiskUsage(
    val total: Long,
    val used: Long,
)

data class ShareLink(
    val hash: String,
    val path: String,
    val expire: Long,
    val hasPassword: Boolean,
    val userId: Long = 0,
    val username: String = "",
)

enum class ShareDurationUnit(val apiValue: String) {
    Seconds("seconds"),
    Minutes("minutes"),
    Hours("hours"),
    Days("days"),
}
