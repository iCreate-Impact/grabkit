package com.grabkit.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.grabkit.model.OutputFormat
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class SavedFile(val uri: Uri, val name: String, val size: Long, val mime: String)

object FileUtil {
    private val UNSAFE = Regex("[^A-Za-z0-9._ -]+")
    private const val FOLDER = "GrabKit"

    /** Same rule as the web backend's safe(): ASCII-only, max 70 chars. */
    fun safeName(s: String?): String =
        UNSAFE.replace(s ?: "media", "_").trim(' ', '.', '_').take(70).ifEmpty { "media" }

    fun newTempDir(context: Context): File =
        File(context.cacheDir, "gk_${UUID.randomUUID().toString().replace("-", "")}").also { it.mkdirs() }

    /** Copies [src] into Movies/GrabKit (mp4) or Downloads/GrabKit (everything else). */
    fun saveToMediaStore(context: Context, src: File, displayName: String, format: OutputFormat): SavedFile =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(context, src, displayName, format)
        else saveLegacy(context, src, displayName, format)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(context: Context, src: File, name: String, format: OutputFormat): SavedFile {
        val resolver = context.contentResolver
        val (collection, relPath) = if (format == OutputFormat.MP4) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MOVIES}/$FOLDER"
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create the file in MediaStore")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't open the output file")
            out.use { o -> src.inputStream().use { it.copyTo(o, 64 * 1024) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return SavedFile(uri, name, src.length(), format.mime)
    }

    /** Android 5-9: no scoped storage, so write to the public folder (needs WRITE_EXTERNAL_STORAGE) and scan it. */
    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, src: File, name: String, format: OutputFormat): SavedFile {
        val type = if (format == OutputFormat.MP4) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS
        val dir = File(Environment.getExternalStoragePublicDirectory(type), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Storage permission is required to save files")
        val dest = uniqueFile(dir, name)
        src.copyTo(dest, overwrite = false)
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(format.mime)) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return SavedFile(scanned ?: Uri.fromFile(dest), dest.name, dest.length(), format.mime)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (f.exists()) {
            f = File(dir, "$base ($n)" + if (ext.isEmpty()) "" else ".$ext")
            n++
        }
        return f
    }
}
