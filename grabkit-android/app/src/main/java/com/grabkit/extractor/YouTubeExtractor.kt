package com.grabkit.extractor

import com.grabkit.model.MediaInfo
import com.grabkit.model.Platform
import javax.inject.Inject
import javax.inject.Singleton

/** YouTube goes through yt-dlp (Chaquopy) only; there is no native path. */
@Singleton
class YouTubeExtractor @Inject constructor(private val bridge: YtDlpBridge) : Extractor {
    override suspend fun extract(url: String): MediaInfo = bridge.info(url, Platform.YOUTUBE)
}
