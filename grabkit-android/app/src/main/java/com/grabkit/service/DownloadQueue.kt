package com.grabkit.service

import com.grabkit.model.DownloadRequest
import com.grabkit.model.HistoryEntry
import com.grabkit.model.JobState
import com.grabkit.model.JobStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory queue + observable state. It is a singleton so the UI and [DownloadService] always see the
 * same StateFlows, across config changes and while the service runs in the background.
 */
@Singleton
class DownloadQueue @Inject constructor() {
    private val pending = ConcurrentLinkedQueue<DownloadRequest>()

    private val _jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())
    val jobs: StateFlow<Map<String, JobState>> = _jobs.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    fun enqueue(req: DownloadRequest) {
        setJob(req.id, JobState(JobStatus.QUEUED, 0f, "Queued"))
        pending.add(req)
    }

    fun next(): DownloadRequest? = pending.poll()

    fun setJob(id: String, state: JobState) {
        _jobs.update { it + (id to state) }
    }

    fun addHistory(entry: HistoryEntry) {
        _history.update { (listOf(entry) + it).take(50) }
    }
}
