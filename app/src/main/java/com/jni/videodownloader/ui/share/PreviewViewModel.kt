package com.jni.videodownloader.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jni.videodownloader.data.DownloadRepository
import com.jni.videodownloader.domain.UrlExtractor
import com.jni.videodownloader.domain.VideoInfo
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
    data class Ready(val info: VideoInfo) : PreviewState
    data class Error(val message: String) : PreviewState
    data object Done : PreviewState
}

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val controller: DownloadController,
) : ViewModel() {

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var resolved: VideoInfo? = null
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
                resolved = info
                PreviewState.Ready(info)
            } catch (e: Exception) {
                PreviewState.Error(e.message ?: "Gagal mengekstrak video")
            }
        }
    }

    fun confirm() {
        val info = resolved ?: return
        val url = sourceUrl ?: return
        if (enqueued) return
        enqueued = true
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                repo.create(url, info.platform, info.title, info.thumbnail, System.currentTimeMillis())
            }
            controller.enqueue(id, info, info.title ?: "Video")
            _state.value = PreviewState.Done
        }
    }
}
