package com.j2team.fileserver.feature.browser

fun uploadSelectionFits(selectedBytes: Long?, total: Long, used: Long): Boolean =
    selectedBytes != null &&
        selectedBytes >= 0 &&
        selectedBytes <= (total - used).coerceAtLeast(0)

fun normalizedOwnerNames(usernames: List<String>): List<String> = buildList {
    usernames.forEach { username ->
        username.trim().takeIf { it.isNotEmpty() && it !in this }?.let(::add)
    }
}
