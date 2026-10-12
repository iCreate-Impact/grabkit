package com.grabkit.extractor

import com.grabkit.model.Engine
import com.grabkit.model.ItemType
import com.grabkit.model.MediaInfo
import com.grabkit.model.MediaItem
import com.grabkit.model.Platform
import com.grabkit.model.Source
import com.grabkit.util.AppError
import com.grabkit.util.ErrorCode
import com.grabkit.util.arr
import com.grabkit.util.fetch
import com.grabkit.util.get
import com.grabkit.util.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XExtractor @Inject constructor(private val client: OkHttpClient) : Extractor {

    override suspend fun extract(url: String): MediaInfo {
        val id = STATUS_RE.find(url)?.groupValues?.get(1) ?: throw AppError(ErrorCode.UNSUPPORTED)
        val api = "https://cdn.syndication.twimg.com/tweet-result".toHttpUrl().newBuilder()
            .addQueryParameter("id", id).addQueryParameter("token", "a").addQueryParameter("lang", "en").build()
        val res = client.fetch(api)
        when (res.code) {
            404 -> throw AppError(ErrorCode.PRIVATE)
            429 -> throw AppError(ErrorCode.RATE_LIMIT)
            else -> Unit
        }
        val j: JsonElement = runCatching { Json.parseToJsonElement(res.body) }
            .getOrElse { throw AppError(ErrorCode.FETCH_FAILED, "unexpected response (HTTP ${res.code})") }

        val items = j["mediaDetails"].arr().orEmpty().mapNotNull { md ->
            val thumb = md["media_url_https"].str() ?: return@mapNotNull null
            if (md["type"].str() == "photo") {
                MediaItem(ItemType.IMAGE, thumb, listOf(Source(0, "$thumb?name=orig")))
            } else {
                val srcs = md["video_info"]["variants"].arr().orEmpty()
                    .filter { it["content_type"].str() == "video/mp4" }
                    .mapNotNull { v ->
                        val u = v["url"].str() ?: return@mapNotNull null
                        // Resolution lives in the URL path: /vid/1280x720/...
                        Source(SIZE_RE.find(u)?.groupValues?.get(2)?.toIntOrNull() ?: 0, u)
                    }
                if (srcs.isEmpty()) null else MediaItem(ItemType.VIDEO, thumb, srcs)
            }
        }
        if (items.isEmpty()) throw AppError(ErrorCode.FETCH_FAILED, "That post has no downloadable media.")
        val title = (j["text"].str().takeUnless { it.isNullOrEmpty() } ?: "X post").take(120)
        return MediaInfo(url, Platform.X, title, items[0].thumb, items, Engine.DIRECT)
    }

    private companion object {
        val STATUS_RE = Regex("status(?:es)?/(\\d+)")
        val SIZE_RE = Regex("/(\\d+)x(\\d+)/")
    }
}
