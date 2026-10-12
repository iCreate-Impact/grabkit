package com.grabkit.extractor

import com.grabkit.model.Engine
import com.grabkit.model.ItemType
import com.grabkit.model.MediaInfo
import com.grabkit.model.MediaItem
import com.grabkit.model.Platform
import com.grabkit.model.Source
import com.grabkit.util.AppError
import com.grabkit.util.ErrorClassifier
import com.grabkit.util.ErrorCode
import com.grabkit.util.RecordingCookieJar
import com.grabkit.util.arr
import com.grabkit.util.fetch
import com.grabkit.util.get
import com.grabkit.util.int
import com.grabkit.util.obj
import com.grabkit.util.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** No-watermark TikTok: web page JSON (playAddr) first, then the mobile feed API. */
@Singleton
class TikTokExtractor @Inject constructor(private val client: OkHttpClient) : Extractor {

    override suspend fun extract(url: String): MediaInfo {
        val errs = mutableListOf<String>()
        try {
            return fromWebPage(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val a = ErrorClassifier.classify(e)
            if (a.code == ErrorCode.PRIVATE) throw a
            errs += "web: ${a.detail ?: a.message}"
        }
        try {
            return fromFeedApi(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val a = ErrorClassifier.classify(e)
            if (a.code == ErrorCode.PRIVATE) throw a
            errs += "app: ${a.detail ?: a.message}"
        }
        throw AppError(ErrorCode.FETCH_FAILED, errs.joinToString("; ").take(200))
    }

    private suspend fun fromWebPage(url: String): MediaInfo {
        val jar = RecordingCookieJar()
        val c = client.newBuilder().cookieJar(jar).build()
        val page = c.fetch(
            url.toHttpUrl(),
            mapOf("Accept-Language" to "en-US,en;q=0.9", "Referer" to "https://www.tiktok.com/"),
        )
        val m = DATA_RE.find(page.body) ?: throw AppError(ErrorCode.FETCH_FAILED, "no embedded data in page")
        val root = Json.parseToJsonElement(m.groupValues[1])
        val detail = root["__DEFAULT_SCOPE__"]["webapp.video-detail"]
        val item = detail["itemInfo"]["itemStruct"].obj()
        if (item == null) {
            // Only report "private" when TikTok itself returns a non-zero status code.
            val status = detail["statusCode"].int()
            if (status != null && status != 0) throw AppError(ErrorCode.PRIVATE)
            throw AppError(ErrorCode.FETCH_FAILED, "page had no video item")
        }
        // The CDN rejects requests without the page cookies and a TikTok Referer.
        val hdr = mapOf("Cookie" to jar.cookieHeader(), "Referer" to "https://www.tiktok.com/")
        val v = item["video"]
        val cover = v["cover"].str().takeUnless { it.isNullOrEmpty() } ?: v["originCover"].str()
        val images = item["imagePost"]["images"].arr()
        val items = if (!images.isNullOrEmpty()) {
            images.mapNotNull { img ->
                val u = img["imageURL"]["urlList"][0].str() ?: return@mapNotNull null
                MediaItem(ItemType.IMAGE, u, listOf(Source(0, u, hdr)))
            }
        } else {
            val u = v["playAddr"].str().takeUnless { it.isNullOrEmpty() }
                ?: v["bitrateInfo"][0]["PlayAddr"]["UrlList"][0].str()
                ?: throw AppError(ErrorCode.FETCH_FAILED, "no playable URL")
            listOf(MediaItem(ItemType.VIDEO, cover, listOf(Source(v["height"].int() ?: 0, u, hdr))))
        }
        if (items.isEmpty()) throw AppError(ErrorCode.FETCH_FAILED, "no media in post")
        return MediaInfo(
            url = url, platform = Platform.TIKTOK,
            title = item["desc"].str().takeUnless { it.isNullOrBlank() } ?: "TikTok post",
            thumb = cover ?: items[0].thumb, items = items, engine = Engine.DIRECT,
        )
    }

    private suspend fun fromFeedApi(url: String): MediaInfo {
        val first = client.fetch(url.toHttpUrl())
        val id = ID_RE.find(first.finalUrl)?.groupValues?.get(1)
            ?: throw AppError(ErrorCode.UNSUPPORTED)
        val api = "https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/feed/".toHttpUrl().newBuilder().apply {
            mapOf(
                "aweme_id" to id, "iid" to "7318518857994389254", "device_id" to "7318517321748022790",
                "channel" to "googleplay", "aid" to "1180", "app_name" to "musical_ly",
                "version_code" to "300904", "version_name" to "30.9.4", "device_platform" to "android",
                "os_version" to "13", "device_type" to "Pixel 7", "device_brand" to "google",
                "region" to "US", "carrier_region" to "US",
            ).forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val res = client.fetch(
            api, mapOf("User-Agent" to "com.zhiliaoapp.musically/2023009040 (Linux; U; Android 13; en_US; Pixel 7)"),
        )
        val j: JsonElement = runCatching { Json.parseToJsonElement(res.body) }
            .getOrElse { throw AppError(ErrorCode.FETCH_FAILED, "empty or non-JSON response from TikTok") }
        val a = j["aweme_list"].arr()?.firstOrNull { it["aweme_id"].str() == id }
            ?: throw AppError(ErrorCode.FETCH_FAILED, "TikTok returned no data for this post (deleted or private?)")
        val video = a["video"]
        val cover = video["cover"]["url_list"][0].str()
        val images = a["image_post_info"]["images"].arr()
        val items = if (!images.isNullOrEmpty()) {
            images.mapNotNull { img ->
                val u = img["display_image"]["url_list"][0].str() ?: return@mapNotNull null
                MediaItem(ItemType.IMAGE, u, listOf(Source(0, u)))
            }
        } else {
            // play_addr is the clean stream; download_addr carries the watermark.
            val srcs = mutableListOf<Source>()
            video["play_addr"]["url_list"][0].str()?.let { srcs += Source(video["height"].int() ?: 0, it) }
            video["bit_rate"].arr()?.forEach { b ->
                val pa = b["play_addr"]
                pa["url_list"][0].str()?.let { srcs += Source(pa["height"].int() ?: 0, it) }
            }
            if (srcs.isEmpty()) throw AppError(ErrorCode.FETCH_FAILED, "no playable URL")
            listOf(MediaItem(ItemType.VIDEO, cover, srcs))
        }
        if (items.isEmpty()) throw AppError(ErrorCode.FETCH_FAILED, "no media in post")
        return MediaInfo(
            url = url, platform = Platform.TIKTOK,
            title = a["desc"].str().takeUnless { it.isNullOrBlank() } ?: "TikTok post",
            thumb = cover ?: items[0].thumb, items = items, engine = Engine.DIRECT,
        )
    }

    private companion object {
        val DATA_RE = Regex(
            "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.+?)</script>", RegexOption.DOT_MATCHES_ALL,
        )
        val ID_RE = Regex("/(?:video|photo)/(\\d+)")
    }
}
