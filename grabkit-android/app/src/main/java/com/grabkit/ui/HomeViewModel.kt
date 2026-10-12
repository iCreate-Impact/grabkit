package com.grabkit.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grabkit.extractor.Resolver
import com.grabkit.model.DownloadRequest
import com.grabkit.model.MediaInfo
import com.grabkit.model.OutputFormat
import com.grabkit.model.Platform
import com.grabkit.service.DownloadQueue
import com.grabkit.service.DownloadService
import com.grabkit.util.ErrorClassifier
import com.grabkit.util.PlatformDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

enum class CardPhase { FETCHING, READY, FAILED }

data class CardUi(
    val id: String,
    val url: String,
    val platform: Platform?,
    val phase: CardPhase,
    val info: MediaInfo? = null,
    val error: String? = null,
    val selected: Set<Int> = emptySet(),
    val format: OutputFormat = OutputFormat.MP4,
    val quality: Int? = null, // null = Best
) {
    val formats: List<OutputFormat>
        get() = if (info?.items?.all { it.type == com.grabkit.model.ItemType.IMAGE } == true) {
            listOf(OutputFormat.JPG, OutputFormat.WEBP)
        } else {
            OutputFormat.values().toList()
        }

    /** Union of the items' qualities, highest first. Empty = only "Best". */
    val qualities: List<Int>
        get() = info?.items.orEmpty().flatMap { it.qualities }.distinct().sortedDescending()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val resolver: Resolver,
    private val queue: DownloadQueue,
) : ViewModel() {

    var input by mutableStateOf("")

    private val _cards = MutableStateFlow<List<CardUi>>(emptyList())
    val cards: StateFlow<List<CardUi>> = _cards.asStateFlow()
    val jobs = queue.jobs
    val history = queue.history

    private val seen = mutableSetOf<String>() // a link that already resolved isn't fetched twice
    private val fetchLock = Mutex()
    private var clipboardChecked = false

    /** Batch: one URL per line / whitespace; links are fetched one after another, newest card on top. */
    fun fetch() {
        val urls = PlatformDetector.extractUrls(input)
        if (urls.isEmpty()) return
        input = ""
        viewModelScope.launch { fetchLock.withLock { urls.forEach { load(it) } } }
    }

    private suspend fun load(url: String) {
        if (url in seen) return
        val id = UUID.randomUUID().toString()
        _cards.update { listOf(CardUi(id, url, PlatformDetector.detectLoose(url), CardPhase.FETCHING)) + it }
        try {
            val info = resolver.resolve(url)
            seen += url
            patch(id) {
                it.copy(
                    phase = CardPhase.READY, info = info, platform = info.platform,
                    selected = info.items.indices.toSet(),
                    format = if (info.items.all { i -> i.type == com.grabkit.model.ItemType.IMAGE }) OutputFormat.JPG else OutputFormat.MP4,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            patch(id) { it.copy(phase = CardPhase.FAILED, error = ErrorClassifier.classify(e).message) }
        }
    }

    private fun patch(id: String, f: (CardUi) -> CardUi) {
        _cards.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    fun toggle(id: String, index: Int) = patch(id) {
        it.copy(selected = if (index in it.selected) it.selected - index else it.selected + index)
    }

    fun toggleAll(id: String) = patch(id) {
        val all = it.info?.items?.indices?.toSet().orEmpty()
        it.copy(selected = if (it.selected == all) emptySet() else all)
    }

    fun setFormat(id: String, f: OutputFormat) = patch(id) { it.copy(format = f) }

    fun setQuality(id: String, q: Int?) = patch(id) { it.copy(quality = q) }

    fun download(id: String) {
        val card = _cards.value.firstOrNull { it.id == id } ?: return
        val info = card.info ?: return
        if (card.selected.isEmpty() || queue.jobs.value[id]?.isActive == true) return
        queue.enqueue(
            DownloadRequest(
                id = id, info = info, indices = card.selected.sorted(), format = card.format,
                quality = card.quality.takeIf { card.format == OutputFormat.MP4 },
            ),
        )
        DownloadService.start(appContext)
    }

    /** Called once per launch: pre-fill the box if the clipboard holds a supported link. */
    fun onClipboard(text: String?) {
        if (clipboardChecked) return
        clipboardChecked = true
        if (input.isNotEmpty() || text.isNullOrBlank()) return
        PlatformDetector.extractUrls(text).firstOrNull { PlatformDetector.detect(it) != null }?.let { input = it }
    }
}
