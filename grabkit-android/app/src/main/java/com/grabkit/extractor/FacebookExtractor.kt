package com.grabkit.extractor

import androidx.core.text.HtmlCompat
import com.grabkit.model.Engine
import com.grabkit.model.ItemType
import com.grabkit.model.MediaInfo
import com.grabkit.model.MediaItem
import com.grabkit.model.Platform
import com.grabkit.model.Source
import com.grabkit.util.AppError
import com.grabkit.util.ErrorCode
import com.grabkit.util.fetch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FacebookExtractor @Inject constructor(private val client: OkHttpClient) : Extractor {

    override suspend fun extract(url: String): MediaInfo {
        val html = client.fetch(url.toHttpUrl(), mapOf("Accept-Language" to "en-US,en;q=0.9")).body
        // First hit per height wins, in this priority order (HD keys first).
        val found = linkedMapOf<Int, String>()
        for ((key, height) in KEYS) {
            Regex("\"$key\":\"(https:[^\"]+)\"").findAll(html).forEach { m ->
                if (height !in found) unescape(m.groupValues[1])?.let { found[height] = it }
            }
        }
        if (found.isEmpty()) throw AppError(ErrorCode.PRIVATE)

        val thumb = OG_IMAGE.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
        val rawTitle = OG_TITLE.find(html)?.groupValues?.get(1)
        val title = rawTitle
            ?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() }
            ?.replace(COUNT_PREFIX, "")?.takeUnless { it.isBlank() }
            ?: "Facebook video"
        val item = MediaItem(ItemType.VIDEO, thumb, found.map { (h, u) -> Source(h, u) })
        return MediaInfo(url, Platform.FACEBOOK, title, thumb, listOf(item), Engine.DIRECT)
    }

    /** The page embeds URLs as JSON string literals (\/ and \uXXXX escapes). */
    private fun unescape(raw: String): String? =
        runCatching { Json.parseToJsonElement("\"$raw\"").jsonPrimitive.content }.getOrNull()

    private companion object {
        val KEYS = listOf(
            "hd_src" to 720, "browser_native_hd_url" to 720, "playable_url_quality_hd" to 720,
            "sd_src" to 360, "browser_native_sd_url" to 360, "playable_url" to 360,
        )
        val OG_IMAGE = Regex("property=\"og:image\" content=\"([^\"]+)\"")
        val OG_TITLE = Regex("property=\"og:title\" content=\"([^\"]*)\"")
        /** Strips a leading "1.2M views · 3K reactions | " style prefix. */
        val COUNT_PREFIX = Regex("^[^|]*\\b(?:views|reactions)\\b[^|]*\\|\\s*")
    }
}
