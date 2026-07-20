package com.j2team.fileserver.core.model

data class RemoteResource(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val mimeType: String? = null,
    val permissions: ResourcePermissions = ResourcePermissions(),
)

/** Server-authorized operations for an individual resource. Missing API fields stay denied. */
data class ResourcePermissions(
    val canDownload: Boolean = false,
    val canUpload: Boolean = false,
    val canCreate: Boolean = false,
    val canDelete: Boolean = false,
)

data class ResourceListing(
    val resources: List<RemoteResource>,
    val directoryPermissions: ResourcePermissions = ResourcePermissions(),
)
