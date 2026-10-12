package com.grabkit.util

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Unwraps shortened / redirect links before platform detection (port of the web backend's normalize()). */
@Singleton
class UrlNormalizer @Inject constructor(private val client: OkHttpClient) {
    private val shortHosts = setOf(
        "t.co", "bit.ly", "tinyurl.com", "goo.gl", "lnkd.in", "ow.ly", "buff.ly", "is.gd",
        "vm.tiktok.com", "vt.tiktok.com", "fb.watch",
    )

    suspend fun normalize(input: String): String {
        var url = input
        repeat(3) {
            val u = url.toHttpUrlOrNull() ?: return url
            val host = u.host.lowercase()
            val next: String? = try {
                when {
                    host.endsWith("google.com") && u.encodedPath in listOf("/url", "/goto") -> {
                        val target = u.queryParameter("url") ?: u.queryParameter("q").orEmpty()
                        if (target.startsWith("http")) target else follow(url) // opaque token: best effort
                    }
                    host == "l.facebook.com" || host == "lm.facebook.com" -> u.queryParameter("u").orEmpty()
                    host in shortHosts -> follow(url)
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (next.isNullOrEmpty() || next == url) return url
            url = next
        }
        return url
    }

    private suspend fun follow(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", Http.UA).build()
        return client.newCall(req).await().use { it.request.url.toString() }
    }
}
