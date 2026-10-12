package com.grabkit.util

import kotlinx.coroutines.CancellationException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ErrorCode { PRIVATE, GEO, RATE_LIMIT, UNSUPPORTED, FETCH_FAILED, NO_INTERNET, TIMEOUT }

/** [detail] is the raw reason; it is only appended to the message for FETCH_FAILED. */
class AppError(
    val code: ErrorCode,
    val detail: String? = null,
    message: String? = null,
) : Exception(message ?: ErrorClassifier.messageFor(code, detail))

class FfmpegException(message: String) : Exception(message)

object ErrorClassifier {
    private val ANSI = Regex("\u001B\\[[0-9;]*m")
    private val PRIVATE_KEYS = listOf("private", "login", "log in", "sign in", "cookies", "authenticat", "members-only")
    private val GEO_RE = Regex("\\bgeo\\b|geo-?restrict|geo-?block|your country|not available in")
    private val RATE_KEYS = listOf("429", "too many requests", "rate limit", "rate-limit")
    private val UNSUPPORTED_KEYS = listOf("unsupported url", "not a valid url")
    private val OFFLINE_KEYS = listOf(
        "name or service not known", "no address associated", "network is unreachable",
        "failed to resolve", "unable to resolve host", "temporary failure in name resolution",
    )

    fun messageFor(code: ErrorCode, detail: String? = null): String = when (code) {
        ErrorCode.PRIVATE -> "This content is private or requires a login."
        ErrorCode.GEO -> "This content is geo-restricted."
        ErrorCode.RATE_LIMIT -> "The platform is rate-limiting. Wait a minute and retry."
        ErrorCode.UNSUPPORTED -> "Unsupported link format. Use a direct post URL."
        ErrorCode.NO_INTERNET -> "No internet connection."
        ErrorCode.TIMEOUT -> "Request timed out."
        ErrorCode.FETCH_FAILED ->
            "Couldn't fetch that link." + if (detail.isNullOrBlank()) "" else " ($detail)"
    }

    fun clean(raw: String?, max: Int = 140): String =
        ANSI.replace(raw.orEmpty(), "").trim().take(max)

    fun classify(t: Throwable): AppError {
        if (t is CancellationException) throw t
        if (t is AppError) return t
        for (c in generateSequence(t) { it.cause }.take(6)) {
            when (c) {
                is SocketTimeoutException -> return AppError(ErrorCode.TIMEOUT)
                is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                    return AppError(ErrorCode.NO_INTERNET)
                is InterruptedIOException ->
                    if (c.message?.contains("timeout", ignoreCase = true) == true) return AppError(ErrorCode.TIMEOUT)
            }
        }
        val msg = t.message ?: t.javaClass.simpleName
        return classify(if (t is FfmpegException) "ffmpeg: $msg" else msg)
    }

    /** Message-based classification (yt-dlp / Python errors arrive as text). */
    fun classify(message: String): AppError {
        val s = message.lowercase()
        return when {
            OFFLINE_KEYS.any { it in s } -> AppError(ErrorCode.NO_INTERNET)
            "timed out" in s || "timeout" in s -> AppError(ErrorCode.TIMEOUT)
            PRIVATE_KEYS.any { it in s } -> AppError(ErrorCode.PRIVATE)
            GEO_RE.containsMatchIn(s) -> AppError(ErrorCode.GEO)
            RATE_KEYS.any { it in s } -> AppError(ErrorCode.RATE_LIMIT)
            UNSUPPORTED_KEYS.any { it in s } -> AppError(ErrorCode.UNSUPPORTED)
            else -> AppError(ErrorCode.FETCH_FAILED, clean(message))
        }
    }

    fun httpError(code: Int): AppError = when (code) {
        429 -> AppError(ErrorCode.RATE_LIMIT)
        451 -> AppError(ErrorCode.GEO)
        else -> AppError(ErrorCode.FETCH_FAILED, "HTTP $code")
    }
}
