package com.grabkit.model

enum class Platform(val label: String, val borderColor: Long) {
    YOUTUBE("YouTube", 0xFFFF4E45),
    TIKTOK("TikTok", 0xFF25F4EE),
    INSTAGRAM("Instagram", 0xFFE1306C),
    FACEBOOK("Facebook", 0xFF4A8CFF),
    X("X", 0xFFE7E9EC),
}

enum class ItemType { VIDEO, IMAGE }

/** DIRECT = native extractor gave CDN URLs; YTDLP = yt-dlp resolves and downloads on demand. */
enum class Engine { DIRECT, YTDLP }

enum class OutputFormat(val ext: String, val mime: String) {
    MP4("mp4", "video/mp4"),
    MP3("mp3", "audio/mpeg"),
    JPG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp");

    val isImage: Boolean get() = this == JPG || this == WEBP
}

/** One downloadable rendition. [height] is 0 when unknown / not applicable (images). */
data class Source(
    val height: Int,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

data class MediaItem(
    val type: ItemType,
    val thumb: String?,
    val sources: List<Source>,
    /** Only used for yt-dlp items, which have no [sources] until download time. */
    val maxHeight: Int = 0,
) {
    /** Selectable qualities, highest first. Empty means "Best" only. */
    val qualities: List<Int>
        get() = when {
            type != ItemType.VIDEO -> emptyList()
            sources.isNotEmpty() -> sources.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
            else -> STANDARD_QUALITIES.filter { it <= maxHeight }
                .ifEmpty { if (maxHeight > 0) listOf(maxHeight) else emptyList() }
        }

    companion object {
        val STANDARD_QUALITIES = listOf(1080, 720, 480, 360)
    }
}

data class MediaInfo(
    /** The resolved (unwrapped) URL; downloads use this, not what the user pasted. */
    val url: String,
    val platform: Platform,
    val title: String,
    val thumb: String?,
    val items: List<MediaItem>,
    val engine: Engine,
    val isPlaylist: Boolean = false,
)
