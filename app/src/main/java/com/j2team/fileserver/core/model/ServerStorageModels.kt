package com.j2team.fileserver.core.model

import java.math.BigDecimal
import java.math.RoundingMode

data class AdminDirectoryEntry(
    val name: String,
    val path: String,
)

data class AdminDirectoryListing(
    val path: String,
    val parent: String,
    val directories: List<AdminDirectoryEntry>,
    val total: Long,
    val used: Long,
    val free: Long,
    val contentBytes: Long,
)

enum class QuotaUnit(val bytes: Long) {
    GB(1_000_000_000L),
    TB(1_000_000_000_000L),
}

data class QuotaInput(
    val value: String,
    val unit: QuotaUnit,
    val unlimited: Boolean,
)

enum class StorageValidation {
    Valid,
    MissingFolder,
    InvalidQuota,
    BelowFolderContent,
    AboveFilesystem,
}

fun quotaBytesFromInput(value: String, unit: QuotaUnit, unlimited: Boolean): Long? {
    if (unlimited) return 0
    val amount = value.trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return null
    return runCatching {
        amount.multiply(BigDecimal.valueOf(unit.bytes)).longValueExact()
    }.getOrNull()
}

fun quotaInputFromBytes(bytes: Long): QuotaInput {
    if (bytes <= 0) return QuotaInput("0", QuotaUnit.GB, true)
    val unit = if (bytes >= QuotaUnit.TB.bytes) QuotaUnit.TB else QuotaUnit.GB
    val value = BigDecimal.valueOf(bytes)
        .divide(BigDecimal.valueOf(unit.bytes), 3, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
    return QuotaInput(value, unit, false)
}

fun validateUserStorage(
    scopeMissing: Boolean,
    quotaBytes: Long?,
    unlimited: Boolean,
    listing: AdminDirectoryListing?,
): StorageValidation {
    if (scopeMissing || listing == null) return StorageValidation.MissingFolder
    if (unlimited) return StorageValidation.Valid
    val quota = quotaBytes?.takeIf { it > 0 } ?: return StorageValidation.InvalidQuota
    if (quota < listing.contentBytes) return StorageValidation.BelowFolderContent
    if (quota > listing.total) return StorageValidation.AboveFilesystem
    return StorageValidation.Valid
}

fun uploadFitsAvailableSpace(selectedBytes: Long, total: Long, used: Long): Boolean =
    selectedBytes >= 0 && selectedBytes <= (total - used).coerceAtLeast(0)
