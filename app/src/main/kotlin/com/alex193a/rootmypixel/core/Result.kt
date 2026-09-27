package com.alex193a.rootmypixel.core

/**
 * Generic result wrapper for failable operations.
 * Domain and data layers must never throw — always return [Result].
 */
interface Error {
    val message: String
}

sealed interface Result<out D, out E : Error> {
    data class Success<out D>(val data: D) : Result<D, Nothing>
    data class Error<out E : com.alex193a.rootmypixel.core.Error>(val error: E) : Result<Nothing, E>
}

typealias EmptyResult<E> = Result<Unit, E>

inline fun <T, E : Error, R> Result<T, E>.map(
    map: (T) -> R
): Result<R, E> {
    return when (this) {
        is Result.Error -> Result.Error(error)
        is Result.Success -> Result.Success(map(this.data))
    }
}

inline fun <T, E : Error, R : Error> Result<T, E>.mapError(
    mapError: (E) -> R
): Result<T, R> {
    return when (this) {
        is Result.Error -> Result.Error(mapError(error))
        is Result.Success -> Result.Success(data)
    }
}

inline fun <T, E : Error> Result<T, E>.onSuccess(
    action: (T) -> Unit
): Result<T, E> {
    return when (this) {
        is Result.Error -> this
        is Result.Success -> {
            action(this.data)
            this
        }
    }
}

inline fun <T, E : Error> Result<T, E>.onFailure(
    action: (E) -> Unit
): Result<T, E> {
    return when (this) {
        is Result.Error -> {
            action(error)
            this
        }
        is Result.Success -> this
    }
}

fun <T, E : Error> Result<T, E>.asEmptyResult(): EmptyResult<E> {
    return map { }
}
