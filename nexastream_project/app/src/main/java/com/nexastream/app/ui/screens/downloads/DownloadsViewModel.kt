package com.nexastream.app.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.utils.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val database: AppDatabase,
    private val downloadManager: DownloadManager
) : ViewModel() {

    val downloads = database.downloadDao().getAll()

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
