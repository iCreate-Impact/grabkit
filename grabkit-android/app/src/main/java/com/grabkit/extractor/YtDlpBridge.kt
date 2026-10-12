package com.grabkit.extractor

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.grabkit.model.Engine
import com.grabkit.model.ItemType
import com.grabkit.model.MediaInfo
import com.grabkit.model.MediaItem
import com.grabkit.model.Platform
import com.grabkit.model.Source
import com.grabkit.util.AppError
import com.grabkit.util.ErrorClassifier
import com.grabkit.util.ErrorCode
import com.grabkit.util.arr
import com.grabkit.util.get
import com.grabkit.util.int
import com.grabkit.util.str
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Python calls this from the yt-dlp progress hook. Kept as a plain Java-style interface for Chaquopy. */
fun interface YtProgress {
    fun update(percent: Double, label: String)
}

/** A raw stream file yt-dlp left in the temp dir. [role] is "video", "audio" or "muxed". */
data class YtFile(val path: String, val role: String)

/** Bridge to src/main/python/grabkit_ytdlp.py running inside Chaquopy. */
@Singleton
class YtDlpBridge @Inject constructor(@ApplicationContext private val context: Context) {

    private val module: PyObject by lazy {
        synchronized(Python::class.java) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        }
        Python.getInstance().getModule("grabkit_ytdlp")
    }

    /**
     * YouTube needs an external JS runtime. If you ship a QuickJS binary as jniLibs/<abi>/libqjs.so it is
     * picked up here and handed to yt-dlp; without it YouTube extraction may fail or miss formats.
     */
    private val jsRuntimePath: String? by lazy {
        File(context.applicationInfo.nativeLibraryDir, "libqjs.so").takeIf { it.canExecute() }?.absolutePath
    }

    suspend fun info(url: String, platform: Platform): MediaInfo = withContext(Dispatchers.IO) {
        val json = try {
            module.callAttr("info", url, jsRuntimePath).toString()
        } catch (e: Exception) {
            throw ErrorClassifier.classify(e)
        }
        parseInfo(json, url, platform)
    }

    suspend fun download(
        url: String,
        outDir: File,
        audioOnly: Boolean,
        quality: Int?,
        index: Int,
        isPlaylist: Boolean,
        onProgress: (Double, String) -> Unit,
    ): List<YtFile> = withContext(Dispatchers.IO) {
        val json = try {
            module.callAttr(
                "download", url, outDir.absolutePath, if (audioOnly) "audio" else "video",
                quality, index, isPlaylist, YtProgress { p, l -> onProgress(p, l) }, jsRuntimePath,
            ).toString()
        } catch (e: Exception) {
            throw ErrorClassifier.classify(e)
        }
        val files = Json.parseToJsonElement(json)["files"].arr().orEmpty().mapNotNull {
            val p = it["path"].str() ?: return@mapNotNull null
            YtFile(p, it["role"].str() ?: "muxed")
        }
        if (files.isEmpty()) throw AppError(ErrorCode.FETCH_FAILED, "Download produced no file.")
        files
    }

    private fun parseInfo(json: String, url: String, platform: Platform): MediaInfo {
        val j = Json.parseToJsonElement(json)
        val items = j["items"].arr().orEmpty().map { e ->
            MediaItem(
                type = if (e["type"].str() == "image") ItemType.IMAGE else ItemType.VIDEO,
                thumb = e["thumb"].str(),
                sources = e["sources"].arr().orEmpty().mapNotNull { s ->
                    s["url"].str()?.let { Source(s["h"].int() ?: 0, it) }
                },
                maxHeight = e["maxh"].int() ?: 0,
            )
        }
        if (items.isEmpty()) throw AppError(ErrorCode.FETCH_FAILED, "No media found at that link.")
        return MediaInfo(
            url = url, platform = platform,
            title = j["title"].str().takeUnless { it.isNullOrBlank() } ?: "Media",
            thumb = j["thumb"].str() ?: items[0].thumb,
            items = items, engine = Engine.YTDLP,
            isPlaylist = j["pl"].str() == "true",
        )
    }
}
