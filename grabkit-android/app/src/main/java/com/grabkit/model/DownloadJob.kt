package com.grabkit.model

/** [id] is the id of the UI card that asked for the download. */
data class DownloadRequest(
    val id: String,
    val info: MediaInfo,
    val indices: List<Int>,
    val format: OutputFormat,
    val quality: Int?,
)

enum class JobStatus { QUEUED, RUNNING, DONE, ERROR }

data class JobState(
    val status: JobStatus,
    /** Overall progress 0..100 across all selected items. */
    val percent: Float = 0f,
    val label: String = "",
    val error: String? = null,
) {
    val isActive: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
}

data class HistoryEntry(
    val fileName: String,
    val platform: Platform,
    val sizeBytes: Long,
    val timeMillis: Long,
    val uri: String?,
)
