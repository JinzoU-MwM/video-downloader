package com.jni.videodownloader.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jni.videodownloader.BuildConfig
import com.jni.videodownloader.data.DownloadRepository
import com.jni.videodownloader.data.net.AppLatest
import com.jni.videodownloader.domain.Download
import com.jni.videodownloader.work.Updater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data class Available(val info: AppLatest) : UpdateUi
    data class Downloading(val percent: Int) : UpdateUi
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val updater: Updater,
) : ViewModel() {
    val items: StateFlow<List<Download>> =
        repo.observeAll().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    init {
        viewModelScope.launch {
            val info = runCatching {
                withContext(Dispatchers.IO) { repo.latestUpdate(BuildConfig.VERSION_CODE) }
            }.getOrNull()
            if (info != null) _update.value = UpdateUi.Available(info)
        }
    }

    fun startUpdate() {
        val info = (_update.value as? UpdateUi.Available)?.info ?: return
        viewModelScope.launch {
            _update.value = UpdateUi.Downloading(0)
            runCatching {
                val file = updater.download(info.apkUrl) { p -> _update.value = UpdateUi.Downloading(p) }
                updater.install(file)
            }
            _update.value = UpdateUi.Idle
        }
    }

    fun dismissUpdate() {
        _update.value = UpdateUi.Idle
    }

    fun delete(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.delete(id) }
    }
}
