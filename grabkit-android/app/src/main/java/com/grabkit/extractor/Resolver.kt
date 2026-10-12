package com.grabkit.extractor

import android.os.SystemClock
import com.grabkit.model.MediaInfo
import com.grabkit.model.Platform
import com.grabkit.util.AppError
import com.grabkit.util.ErrorClassifier
import com.grabkit.util.ErrorCode
import com.grabkit.util.PlatformDetector
import com.grabkit.util.UrlNormalizer
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** normalize -> detect -> native extractor -> yt-dlp fallback (non-YouTube). Port of resolve() in main.py. */
@Singleton
class Resolver @Inject constructor(
    private val normalizer: UrlNormalizer,
    private val youtube: YouTubeExtractor,
    private val tiktok: TikTokExtractor,
    private val instagram: InstagramExtractor,
    private val facebook: FacebookExtractor,
    private val x: XExtractor,
    private val ytdlp: YtDlpBridge,
) {
    private data class Cached(val at: Long, val info: MediaInfo)

    private val cache = ConcurrentHashMap<String, Cached>() // metadata only, never media

    suspend fun resolve(input: String): MediaInfo {
        val orig = input.trim()
        cache[orig]?.let { if (SystemClock.elapsedRealtime() - it.at < TTL_MS) return it.info }

        val url = normalizer.normalize(orig)
        val platform = PlatformDetector.detect(url) ?: throw AppError(ErrorCode.UNSUPPORTED)

        val info = if (platform == Platform.YOUTUBE) {
            try {
                youtube.extract(url)
            } catch (e: Throwable) {
                throw ErrorClassifier.classify(e)
            }
        } else {
            try {
                extractorFor(platform).extract(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val first = ErrorClassifier.classify(e)
                // Offline / timeout: yt-dlp would fail the same way, so don't make the user wait twice.
                if (first.code == ErrorCode.NO_INTERNET || first.code == ErrorCode.TIMEOUT) throw first
                try {
                    ytdlp.info(url, platform)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Throwable) {
                    val second = ErrorClassifier.classify(e2)
                    if (first.code != ErrorCode.FETCH_FAILED) throw first
                    if (second.code != ErrorCode.FETCH_FAILED) throw second
                    throw AppError(
                        ErrorCode.FETCH_FAILED,
                        "Native: ${first.detail ?: first.message} | yt-dlp: ${second.detail ?: ""}".take(400),
                    )
                }
            }
        }
        cache[orig] = Cached(SystemClock.elapsedRealtime(), info)
        return info
    }

    private fun extractorFor(p: Platform): Extractor = when (p) {
        Platform.TIKTOK -> tiktok
        Platform.INSTAGRAM -> instagram
        Platform.FACEBOOK -> facebook
        Platform.X -> x
        Platform.YOUTUBE -> youtube
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}
