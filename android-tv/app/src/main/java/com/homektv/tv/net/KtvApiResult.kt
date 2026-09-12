package com.homektv.tv.net

/** A user-safe classification for REST failures shown by controller UI. */
enum class KtvApiErrorKind {
    NETWORK,
    HTTP,
    BUSINESS,
    DECODE,
    PAYLOAD_TOO_LARGE,
}

data class KtvApiError(
    val kind: KtvApiErrorKind,
    val code: String,
    val message: String,
    val status: Int? = null,
)

sealed interface KtvApiResult<out T> {
    data class Success<T>(val value: T) : KtvApiResult<T>
    data class Failure(val error: KtvApiError) : KtvApiResult<Nothing>
}

inline fun <T, R> KtvApiResult<T>.mapValue(transform: (T) -> R): KtvApiResult<R> = when (this) {
    is KtvApiResult.Success -> KtvApiResult.Success(transform(value))
    is KtvApiResult.Failure -> this
}
