package com.nexastream.app.ui.screens.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.LocalVideoFile
import com.nexastream.app.utils.DownloadManager
import com.nexastream.app.utils.LocalVideoScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val database: AppDatabase,
    private val downloadManager: DownloadManager
) : ViewModel() {

    val downloads = database.downloadDao().getAll()

    private val _localVideos = MutableStateFlow<List<LocalVideoFile>>(emptyList())
    val localVideos: StateFlow<List<LocalVideoFile>> = _localVideos.asStateFlow()

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun scanLocalVideos(context: Context) {
        viewModelScope.launch {
            _isScanningLocal.value = true
            try {
                val list = LocalVideoScanner.scanLocalVideos(context)
                _localVideos.value = list
            } catch (e: Exception) {
                _localVideos.value = emptyList()
            } finally {
                _isScanningLocal.value = false
            }
        }
    }

    fun deleteDownload(id: String) {
        viewModelScope.launch {
            downloadManager.deleteDownload(id)
        }
    }

    fun pauseDownload(id: String) {
        viewModelScope.launch {
            downloadManager.pauseDownload(id)
        }
    }

    fun resumeDownload(id: String) {
        viewModelScope.launch {
            downloadManager.resumeDownload(id)
        }
    }

    fun retryDownload(id: String) {
        viewModelScope.launch {
            downloadManager.retryDownload(id)
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch {
            database.downloadDao().getAllSnapshot().forEach {
                downloadManager.deleteDownload(it.id)
            }
        }
    }

    fun toggleAllowMobileData(allow: Boolean) {
        viewModelScope.launch {
            com.nexastream.app.utils.UserPreferences.downloadWifiOnly = !allow
            downloadManager.onSettingsChanged()
        }
    }
}
