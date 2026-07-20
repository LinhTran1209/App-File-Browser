Exit code: 0
Wall time: 0.6 seconds
Output:
package com.j2team.fileserver.core.network

import java.net.URI

data class NormalizedEndpoint(
    val scheme: String,
    val host: String,
    val port: Int,
    val basePath: String,
) {
    val url: String get() = "$scheme://$host:$port${basePath.trimEnd('/')}/"
}

object Endpoint {
    fun normalize(raw: String): Result<NormalizedEndpoint> = runCatching {
        val input = raw.trim().let { if ("://" in it) it else "http://$it" }
        val uri = URI(input)
        require(uri.scheme == "http" || uri.scheme == "https") { "Scheme must be http or https" }
        require(!uri.host.isNullOrBlank()) { "Host is required" }
        val port = if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port
        require(port in 1..65535) { "Port is invalid" }
        NormalizedEndpoint(uri.scheme.lowercase(), uri.host, port, "/${uri.path.trim('/')}")
    }
}

