Exit code: 0
Wall time: 0.6 seconds
Output:
package com.j2team.fileserver.feature.browser

object BrowserPath {
    fun parent(path: String): String {
        val clean = normalize(path)
        if (clean == "/") return "/"
        return clean.substringBeforeLast('/').ifBlank { "/" }
    }

    fun child(parent: String, name: String): String {
        require(name.isNotBlank() && name != "." && name != "..") { "Invalid resource name" }
        return if (normalize(parent) == "/") "/$name" else "${normalize(parent)}/$name"
    }

    fun normalize(path: String): String =
        path.trim().replace(Regex("/+"), "/").let {
            when {
                it.isBlank() -> "/"
                !it.startsWith("/") -> "/$it"
                it.length > 1 -> it.trimEnd('/')
                else -> it
            }
        }
}

