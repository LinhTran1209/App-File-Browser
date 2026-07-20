package com.j2team.fileserver.core.model

data class RemoteResource(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)
