package com.grabkit.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.grabkit.MainActivity
import com.grabkit.R
import com.grabkit.model.DownloadRequest
import com.grabkit.model.HistoryEntry
import com.grabkit.model.JobState
import com.grabkit.model.JobStatus
import com.grabkit.util.ErrorClassifier
import com.grabkit.util.SavedFile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Foreground host for the download queue. Started whenever something is enqueued; works through the
 * queue sequentially and stops itself when it is empty. Progress is published through [DownloadQueue],
 * which the UI observes directly.
 */
@AndroidEntryPoint
class DownloadService : LifecycleService() {

    @Inject lateinit var queue: DownloadQueue
    @Inject lateinit var engine: DownloadEngine

    private val workerLock = Mutex()
    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Must happen within a few seconds of startForegroundService().
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_PROGRESS, progressNotification("Starting…", "", 0, true), type)
        lifecycleScope.launch {
            // The lock serialises overlapping start commands into one sequential worker.
            workerLock.withLock {
                while (true) process(queue.next() ?: break)
            }
            // No-op if a newer start command arrived meanwhile (that one will drain the queue).
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun process(req: DownloadRequest) {
        queue.setJob(req.id, JobState(JobStatus.RUNNING, 0f, "Fetching"))
        try {
            engine.run(
                req,
                onProgress = { pct, label ->
                    queue.setJob(req.id, JobState(JobStatus.RUNNING, pct, label))
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifyAt > 500) { // Android rate-limits notification updates
                        lastNotifyAt = now
                        postNotification(NOTIF_PROGRESS, progressNotification(req.info.title, label, pct.toInt(), false))
                    }
                },
                onSaved = { saved ->
                    queue.addHistory(
                        HistoryEntry(saved.name, req.info.platform, saved.size, System.currentTimeMillis(), saved.uri.toString()),
                    )
                    notifyDone(saved)
                },
            )
            queue.setJob(req.id, JobState(JobStatus.DONE, 100f, "Done"))
        } catch (e: CancellationException) {
            queue.setJob(req.id, JobState(JobStatus.ERROR, error = "Download interrupted."))
            throw e
        } catch (e: Throwable) {
            queue.setJob(req.id, JobState(JobStatus.ERROR, error = ErrorClassifier.classify(e).message))
        }
    }

    private fun progressNotification(title: String, label: String, percent: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyDone(saved: SavedFile) {
        val b = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText(saved.name)
            .setAutoCancel(true)
        // file:// Uris can't be handed to other apps on Android 7+; those only exist on <= API 28 as a fallback.
        if (saved.uri.scheme == "content") {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(pending(view))
        } else {
            b.setContentIntent(openAppIntent())
        }
        postNotification((System.nanoTime() % Int.MAX_VALUE).toInt(), b.build())
    }

    private fun openAppIntent() = pending(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))

    private fun pending(intent: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(id: Int, n: Notification) {
        // On Android 13+ the user may have denied POST_NOTIFICATIONS; the service keeps running regardless.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(id, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val CHANNEL_PROGRESS = "grabkit_progress"
        private const val CHANNEL_DONE = "grabkit_done"
        private const val NOTIF_PROGRESS = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
