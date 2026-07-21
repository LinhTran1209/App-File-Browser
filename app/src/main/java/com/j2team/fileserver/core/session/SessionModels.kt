package com.j2team.fileserver.core.session

import com.j2team.fileserver.core.model.ServerProfile

data class StoredCredential(
    val username: String,
    val password: CharArray,
)

data class AuthenticatedSession(
    val profile: ServerProfile,
    val token: String,
)

class LoginRequiredException : IllegalStateException("Sign in is required")

data class ApiResult<T>(
    val code: Int,
    val value: T? = null,
    val error: Throwable? = null,
) {
    fun <R> map(transform: (T) -> R): ApiResult<R> =
        ApiResult(code, value?.let(transform), error)

    fun toResult(): Result<T> = when {
        code in 200..299 && value != null -> Result.success(value)
        else -> Result.failure(error ?: IllegalStateException("Request failed ($code)"))
    }
}

class SessionPolicy(private val refresher: TokenRefresher) {
    interface TokenRefresher {
        suspend fun renew(): Result<String>
    }

    suspend fun <T> execute(token: String, request: suspend (String) -> ApiResult<T>): Result<T> {
        val first = request(token)
        if (first.code !in AUTH_FAILURE_CODES) return first.toResult()

        val renewed = refresher.renew().getOrElse { return Result.failure(it) }
        return request(renewed).toResult()
    }

    private companion object {
        val AUTH_FAILURE_CODES = setOf(401, 403)
    }
}
