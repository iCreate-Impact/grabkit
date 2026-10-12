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
import com.grabkit.util.int
import com.grabkit.util.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstagramExtractor @Inject constructor(private val client: OkHttpClient) : Extractor {

    override suspend fun extract(url: String): MediaInfo {
        val m = POST_RE.find(url) ?: throw if ("/stories/" in url) {
            AppError(ErrorCode.UNSUPPORTED, message = "Instagram stories aren't supported. Use a direct post URL.")
        } else {
            AppError(ErrorCode.UNSUPPORTED)
        }
        val kind = if (m.groupValues[1] == "reels") "reel" else m.groupValues[1]
        val api = "https://www.instagram.com/$kind/${m.groupValues[2]}/".toHttpUrl().newBuilder()
            .addQueryParameter("__a", "1").addQueryParameter("__d", "dis").build()
        val res = client.fetch(api, mapOf("X-IG-App-ID" to "936619743392459", "X-Requested-With" to "XMLHttpRequest"))
        // Not JSON = Instagram sent its login wall.
        val j: JsonElement = runCatching { Json.parseToJsonElement(res.body) }
            .getOrElse { throw AppError(ErrorCode.PRIVATE) }

        val nodes: List<JsonElement>
        val title: String
        val first = j["items"][0]
        if (first != null) {
            nodes = first["carousel_media"].arr()?.toList().takeUnless { it.isNullOrEmpty() } ?: listOf(first)
            title = first["caption"]["text"].str().takeUnless { it.isNullOrEmpty() } ?: "Instagram post"
        } else {
            val sm = j["graphql"]["shortcode_media"]
                ?: throw AppError(ErrorCode.FETCH_FAILED, "unexpected Instagram response")
            nodes = sm["edge_sidecar_to_children"]["edges"].arr()?.mapNotNull { it["node"] }
                .takeUnless { it.isNullOrEmpty() } ?: listOf(sm)
            title = sm["edge_media_to_caption"]["edges"][0]["node"]["text"].str()
                .takeUnless { it.isNullOrEmpty() } ?: "Instagram post"
        }
        val items = nodes.map { node(it) }
        return MediaInfo(url, Platform.INSTAGRAM, title, items[0].thumb, items, Engine.DIRECT)
    }

    private fun node(n: JsonElement): MediaItem {
        val thumb = n["image_versions2"]["candidates"][0]["url"].str() ?: n["display_url"].str()
        val versions = n["video_versions"].arr()
        if (!versions.isNullOrEmpty()) {
            val srcs = versions.mapNotNull { v -> v["url"].str()?.let { Source(v["height"].int() ?: 0, it) } }
            if (srcs.isNotEmpty()) return MediaItem(ItemType.VIDEO, thumb, srcs)
        }
        val videoUrl = n["video_url"].str()
        if (videoUrl != null) return MediaItem(ItemType.VIDEO, thumb, listOf(Source(720, videoUrl)))
        return MediaItem(ItemType.IMAGE, thumb, listOfNotNull(thumb?.let { Source(0, it) }))
    }

    private companion object {
        val POST_RE = Regex("instagram\\.com/(?:[\\w.]+/)?(p|reel|reels|tv)/([\\w-]+)")
    }
}
