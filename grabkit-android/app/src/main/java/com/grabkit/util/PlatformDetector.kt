package com.grabkit.util

import com.grabkit.model.Platform
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PlatformDetector {
    private val HOSTS = mapOf(
        Platform.YOUTUBE to listOf("youtube.com", "youtu.be"),
        Platform.TIKTOK to listOf("tiktok.com"),
        Platform.INSTAGRAM to listOf("instagram.com"),
        Platform.FACEBOOK to listOf("facebook.com", "fb.watch", "fb.com"),
        Platform.X to listOf("twitter.com", "x.com"),
    )

    /** Same patterns the web UI uses for the live hint while typing. */
    private val LOOSE = mapOf(
        Platform.YOUTUBE to Regex("youtu\\.?be", RegexOption.IGNORE_CASE),
        Platform.TIKTOK to Regex("tiktok\\.com", RegexOption.IGNORE_CASE),
        Platform.INSTAGRAM to Regex("instagram\\.com", RegexOption.IGNORE_CASE),
        Platform.FACEBOOK to Regex("(facebook|fb)\\.(com|watch)", RegexOption.IGNORE_CASE),
        Platform.X to Regex("(twitter|x)\\.com", RegexOption.IGNORE_CASE),
    )

    private val WHITESPACE = Regex("\\s+")
    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)

    /** Strict, host-based detection used when resolving a link. */
    fun detect(url: String): Platform? {
        val host = url.trim().toHttpUrlOrNull()?.host?.lowercase() ?: return null
        return HOSTS.entries.firstOrNull { (_, hs) -> hs.any { host == it || host.endsWith(".$it") } }?.key
    }

    /** Lenient detection for the typing hint and card badge. */
    fun detectLoose(url: String): Platform? =
        LOOSE.entries.firstOrNull { (_, re) -> re.containsMatchIn(url) }?.key

    /** Whitespace-separated http(s) links, de-duplicated, in order. */
    fun extractUrls(text: String): List<String> =
        text.split(WHITESPACE).filter { HTTP.containsMatchIn(it) }.distinct()
}
