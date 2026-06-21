package com.jni.videodownloader.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jni.videodownloader.data.DownloadRepository
import com.jni.videodownloader.domain.Download
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: DownloadRepository,
) : ViewModel() {
    val items: StateFlow<List<Download>> =
        repo.observeAll().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )

    fun delete(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.delete(id) }
    }
}
