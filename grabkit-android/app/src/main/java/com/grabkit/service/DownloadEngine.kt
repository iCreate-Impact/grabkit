package com.grabkit.service

import android.content.Context
import com.grabkit.extractor.YtDlpBridge
import com.grabkit.model.DownloadRequest
import com.grabkit.model.Engine
import com.grabkit.model.ItemType
import com.grabkit.model.MediaInfo
import com.grabkit.model.MediaItem
import com.grabkit.model.OutputFormat
import com.grabkit.model.Source
import com.grabkit.util.AppError
import com.grabkit.util.ErrorCode
import com.grabkit.util.FfmpegRunner
import com.grabkit.util.FileUtil
import com.grabkit.util.SavedFile
import com.grabkit.util.download
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one [DownloadRequest] end to end: fetch bytes (OkHttp or yt-dlp) -> FFmpeg where needed ->
 * MediaStore. Temp files live in cacheDir and are removed after every item, success or failure.
 * Port of work() / prepare() from the web backend.
 */
@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val ffmpeg: FfmpegRunner,
    private val ytdlp: YtDlpBridge,
) {
    /** [onProgress] receives overall 0..100 across all selected items plus a label. */
    suspend fun run(
        req: DownloadRequest,
        onProgress: (Float, String) -> Unit,
        onSaved: (SavedFile) -> Unit,
    ) {
        val total = req.indices.size
        req.indices.forEachIndexed { k, idx ->
            val tag = if (total > 1) "${k + 1}/$total · " else ""
            val report: (Float, String) -> Unit = { pct, label ->
                onProgress(((k + pct.coerceIn(0f, 100f) / 100f) / total) * 100f, tag + label)
            }
            onSaved(downloadItem(req, idx, report))
        }
    }

    private suspend fun downloadItem(req: DownloadRequest, idx: Int, report: (Float, String) -> Unit): SavedFile {
        val info = req.info
        val item = info.items.getOrNull(idx) ?: throw AppError(ErrorCode.FETCH_FAILED, "That item doesn't exist.")
        var format = req.format
        if (format == OutputFormat.MP3 && item.type == ItemType.IMAGE) {
            throw AppError(ErrorCode.FETCH_FAILED, "Images can only be saved as jpg or webp.")
        }
        if (format == OutputFormat.MP4 && item.type == ItemType.IMAGE) format = OutputFormat.JPG

        val suffix = if (info.items.size > 1) "-${idx + 1}" else ""
        val name = "${FileUtil.safeName(info.title)}$suffix.${format.ext}"
        val dir = FileUtil.newTempDir(context)
        try {
            val out = produce(info, item, idx, format, req.quality, dir, report)
            report(97f, "Saving")
            return withContext(Dispatchers.IO) { FileUtil.saveToMediaStore(context, out, name, format) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private suspend fun produce(
        info: MediaInfo,
        item: MediaItem,
        idx: Int,
        format: OutputFormat,
        quality: Int?,
        dir: File,
        report: (Float, String) -> Unit,
    ): File {
        val out = File(dir, "out.${format.ext}")
        when {
            format.isImage -> {
                // An image post downloads the image; a video downloads its thumbnail.
                val src = if (item.type == ItemType.IMAGE) item.sources.firstOrNull() else null
                val url = src?.url ?: item.thumb ?: throw AppError(ErrorCode.FETCH_FAILED, "No image available.")
                val raw = File(dir, "raw")
                fetchTo(url, raw, src?.headers.orEmpty(), 0f, 85f, report)
                report(85f, "Converting")
                ffmpeg.convertImage(raw, out, format)
            }
            info.engine == Engine.YTDLP -> produceWithYtDlp(info, idx, format, quality, dir, out, report)
            format == OutputFormat.MP4 -> {
                val s = pick(item.sources, quality)
                fetchTo(s.url, out, s.headers, 0f, 100f, report)
            }
            else -> { // direct mp3: fetch the best video, then extract the audio track
                val s = pick(item.sources, null)
                val raw = File(dir, "raw")
                fetchTo(s.url, raw, s.headers, 0f, 85f, report)
                report(85f, "Converting")
                ffmpeg.toMp3(raw, out)
            }
        }
        return out
    }

    private suspend fun produceWithYtDlp(
        info: MediaInfo,
        idx: Int,
        format: OutputFormat,
        quality: Int?,
        dir: File,
        out: File,
        report: (Float, String) -> Unit,
    ) {
        report(0f, "Starting")
        val files = ytdlp.download(
            url = info.url, outDir = dir, audioOnly = format == OutputFormat.MP3,
            quality = quality, index = idx, isPlaylist = info.isPlaylist,
        ) { pct, label -> report(pct.toFloat(), label) } // yt-dlp's own progress-hook label
        if (format == OutputFormat.MP3) {
            report(95f, "Converting")
            ffmpeg.toMp3(File(files.first().path), out)
            return
        }
        val video = files.firstOrNull { it.role == "video" }
        val audio = files.firstOrNull { it.role == "audio" }
        if (video != null && audio != null) {
            report(95f, "Merging")
            ffmpeg.merge(File(video.path), File(audio.path), out)
        } else {
            val single = File((video ?: files.first()).path)
            if (single.extension.equals("mp4", ignoreCase = true)) {
                withContext(Dispatchers.IO) { single.copyTo(out, overwrite = true) }
            } else {
                report(95f, "Converting")
                ffmpeg.remux(single, out)
            }
        }
    }

    private suspend fun fetchTo(
        url: String,
        dest: File,
        headers: Map<String, String>,
        lo: Float,
        hi: Float,
        report: (Float, String) -> Unit,
    ) {
        report(lo, "Downloading")
        var lastAt = 0L
        client.download(url, dest, headers) { got, total ->
            val now = System.nanoTime()
            if (now - lastAt < 100_000_000L) return@download // ~10 updates/s is plenty
            lastAt = now
            val mb = String.format(Locale.US, "%.1f MB", got / 1048576.0)
            if (total > 0) {
                report(lo + (hi - lo) * (got.toFloat() / total), "Downloading · $mb")
            } else {
                report(lo, "Downloading · $mb") // no Content-Length: label only
            }
        }
        report(hi, "Downloading")
    }

    companion object {
        /** Highest rendition not above [q]; the smallest one if all are above; the best when [q] is null. */
        fun pick(sources: List<Source>, q: Int?): Source {
            val sorted = sources.sortedByDescending { it.height }
            if (sorted.isEmpty()) throw AppError(ErrorCode.FETCH_FAILED, "No playable source found.")
            if (q == null) return sorted.first()
            return sorted.firstOrNull { it.height <= q } ?: sorted.last()
        }
    }
}
