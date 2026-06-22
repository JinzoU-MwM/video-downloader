package com.jni.videodownloader.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jni.videodownloader.data.DownloadRepository
import com.jni.videodownloader.data.Prefs
import com.jni.videodownloader.domain.DownloadOptions
import com.jni.videodownloader.domain.Platform
import com.jni.videodownloader.domain.UrlExtractor
import com.jni.videodownloader.domain.defaultTitle
import com.jni.videodownloader.work.DownloadController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(
        val platform: Platform,
        val baseUrl: String,
        val initial: DownloadOptions,
    ) : PreviewState
    data class Error(val message: String) : PreviewState
    data object Done : PreviewState
}

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val controller: DownloadController,
    private val prefs: Prefs,
) : ViewModel() {

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var platform: Platform? = null
    private var baseUrl: String? = null
    private var sourceUrl: String? = null
    private var started = false
    private var enqueued = false

    fun start(sharedText: String) {
        if (started) return
        started = true
        val url = UrlExtractor.firstUrl(sharedText)
        if (url == null || UrlExtractor.detectPlatform(url) == null) {
            _state.value = PreviewState.Error("Tautan tidak didukung")
            return
        }
        sourceUrl = url
        viewModelScope.launch {
            _state.value = try {
                val info = withContext(Dispatchers.IO) { repo.resolve(url) }
                platform = info.platform
                baseUrl = info.directUrl
                PreviewState.Ready(info.platform, info.directUrl, prefs.lastOptions())
            } catch (e: Exception) {
                PreviewState.Error(e.message ?: "Gagal menyiapkan unduhan")
            }
        }
    }

    fun confirm(options: DownloadOptions) {
        val p = platform ?: return
        val base = baseUrl ?: return
        val url = sourceUrl ?: return
        if (enqueued) return
        enqueued = true
        prefs.save(options)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val title = defaultTitle(p, now)
            val finalUrl = options.mediaUrl(base)
            val id = withContext(Dispatchers.IO) {
                repo.create(url, p, title, null, now)
            }
            controller.enqueue(id, finalUrl, options.mode, title)
            _state.value = PreviewState.Done
        }
    }
}
