package com.j2team.fileserver.core.model

data class ServerProfile(
    val id: String,
    val displayName: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val basePath: String = "/",
) {
    val endpoint: String get() = "$scheme://$host:$port${basePath.trimEnd('/')}/"
}
