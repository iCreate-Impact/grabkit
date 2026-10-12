package com.grabkit.util

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.grabkit.model.OutputFormat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * All FFmpeg work goes through here. The call sites only use the com.arthenica.ffmpegkit API, which the
 * community forks keep, so changing the Gradle coordinate (see gradle.properties) is the only swap needed.
 */
@Singleton
class FfmpegRunner @Inject constructor() {

    suspend fun toMp3(input: File, output: File) =
        exec("-i", input.path, "-vn", "-codec:a", "libmp3lame", "-q:a", "2", output.path)

    suspend fun convertImage(input: File, output: File, format: OutputFormat) =
        if (format == OutputFormat.JPG) exec("-i", input.path, "-q:v", "2", output.path)
        else exec("-i", input.path, output.path)

    /** Stream-copy merge; falls back to re-encoding audio to AAC when the codecs can't live in mp4 as-is. */
    suspend fun merge(video: File, audio: File, output: File) {
        try {
            exec("-i", video.path, "-i", audio.path, "-c", "copy", output.path)
        } catch (e: FfmpegException) {
            output.delete()
            exec("-i", video.path, "-i", audio.path, "-c:v", "copy", "-c:a", "aac", output.path)
        }
    }

    suspend fun remux(input: File, output: File) = exec("-i", input.path, "-c", "copy", output.path)

    private suspend fun exec(vararg args: String) {
        val full = arrayOf("-y", "-loglevel", "error", *args)
        suspendCancellableCoroutine<Unit> { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(full) { s ->
                if (ReturnCode.isSuccess(s.returnCode)) {
                    cont.resume(Unit)
                } else {
                    val tail = s.allLogsAsString.orEmpty().trim().lines().lastOrNull { it.isNotBlank() }
                    cont.resumeWithException(FfmpegException(tail ?: "ffmpeg exited with ${s.returnCode}"))
                }
            }
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
    }
}
