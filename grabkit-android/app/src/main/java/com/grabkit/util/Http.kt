package com.grabkit.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CipherSuite
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.brotli.BrotliInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    /** Same desktop Chrome UA the web app's backend sends. */
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /**
     * Chrome's cipher-suite order. This is a best effort: on Android the TLS stack (Conscrypt) still
     * decides extension order, curves and GREASE, so the JA3/JA4 hash will NOT match real Chrome.
     * TikTok may therefore reject the request; the extractors surface that as a normal error.
     */
    private fun chromeLikeSpec(): ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .cipherSuites(
            CipherSuite.TLS_AES_128_GCM_SHA256,
            CipherSuite.TLS_AES_256_GCM_SHA384,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
        )
        .supportsTlsExtensions(true)
        .build()

    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        // Falls back to the stock specs if a device rejects the Chrome-like list.
        .connectionSpecs(listOf(chromeLikeSpec(), ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .addInterceptor(BrotliInterceptor) // Chrome advertises br; handles gzip + br decoding
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

/** Remembers every cookie seen during a request chain (incl. redirects) so they can be replayed on the CDN. */
class RecordingCookieJar : CookieJar {
    private val all = CopyOnWriteArrayList<Cookie>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        all.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = all.filter { it.matches(url) }

    fun cookieHeader(): String =
        all.associate { it.name to it.value }.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

data class HttpResult(val code: Int, val finalUrl: String, val body: String)

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() }
        }
    })
}

private fun requestFor(url: HttpUrl, headers: Map<String, String>): Request =
    Request.Builder().url(url).header("User-Agent", Http.UA).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.build()

/** GET and read the whole body as text. Never throws for 4xx/5xx; the caller inspects [HttpResult.code]. */
suspend fun OkHttpClient.fetch(url: HttpUrl, headers: Map<String, String> = emptyMap()): HttpResult =
    newCall(requestFor(url, headers)).await().use { resp ->
        val body = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
        HttpResult(resp.code, resp.request.url.toString(), body)
    }

/** Streams [url] to [dest]; [onProgress] gets (bytesSoFar, contentLength or -1). */
suspend fun OkHttpClient.download(
    url: String,
    dest: File,
    headers: Map<String, String> = emptyMap(),
    onProgress: (Long, Long) -> Unit,
) {
    val httpUrl = url.toHttpUrlOrNull() ?: throw AppError(ErrorCode.FETCH_FAILED, "bad URL")
    val req = Request.Builder().url(httpUrl)
        .header("User-Agent", Http.UA)
        .header("Accept-Encoding", "identity") // keeps Content-Length truthful for the progress bar
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .build()
    newCall(req).await().use { resp ->
        if (!resp.isSuccessful) throw ErrorClassifier.httpError(resp.code)
        val body = resp.body ?: throw AppError(ErrorCode.FETCH_FAILED, "empty response")
        val total = body.contentLength()
        withContext(Dispatchers.IO) {
            body.byteStream().use { input ->
                dest.outputStream().use { out -> copyWithProgress(input, out, total, onProgress) }
            }
        }
    }
}

private fun CoroutineScope.copyWithProgress(
    input: java.io.InputStream,
    out: java.io.OutputStream,
    total: Long,
    onProgress: (Long, Long) -> Unit,
) {
    val buf = ByteArray(64 * 1024)
    var got = 0L
    while (true) {
        ensureActive()
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        got += n
        onProgress(got, total)
    }
}
