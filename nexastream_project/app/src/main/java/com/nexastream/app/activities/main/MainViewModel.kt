package com.nexastream.app.activities.main

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.BuildConfig
import com.nexastream.app.models.UpdateInfo
import com.nexastream.app.utils.InAppUpdater
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow<State>(State.CheckingUpdate)
    val state: Flow<State> = _state

    sealed class State {
        data object CheckingUpdate : State()
        data class SuccessCheckingUpdate(
            val updateInfo: UpdateInfo
        ) : State()

        data class DownloadingUpdate(val progress: Int = 0) : State()
        data class SuccessDownloadingUpdate(val apk: File) : State()

        data object InstallingUpdate : State()

        data class FailedUpdate(val error: Exception) : State()
    }

    fun checkUpdate() = viewModelScope.launch(Dispatchers.IO) {
        if (!UserPreferences.updateCheckEnabled) return@launch

        val now = System.currentTimeMillis()
        if (!BuildConfig.DEBUG && (now - UserPreferences.lastUpdateCheckMillis < 30 * 60 * 1000)) {
            Log.d("MainViewModel", "checkUpdate skip: last check was less than 30 minutes ago")
            return@launch
        }

        UserPreferences.lastUpdateCheckMillis = now
        _state.emit(State.CheckingUpdate)

        try {
            val updateInfo = InAppUpdater.getRemoteUpdate()
            if (updateInfo != null) {
                _state.emit(State.SuccessCheckingUpdate(updateInfo))
            } else {
                Log.d("MainViewModel", "App is up to date.")
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "checkUpdate failed: ${e.message}")
        }
    }

    fun downloadUpdate(
        context: Context,
        apkUrl: String
    ) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.DownloadingUpdate(0))

        try {
            val apk = InAppUpdater.downloadApk(context, apkUrl) { progress ->
                viewModelScope.launch(Dispatchers.IO) {
                    _state.emit(State.DownloadingUpdate(progress))
                }
            }

            _state.emit(State.SuccessDownloadingUpdate(apk))
        } catch (e: Exception) {
            Log.e("MainViewModel", "downloadUpdate failed: ${e.message}", e)
            _state.emit(State.FailedUpdate(e))
        }
    }

    fun installUpdate(
        context: Context,
        apk: File
    ) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.InstallingUpdate)

        try {
            InAppUpdater.installApk(context, Uri.fromFile(apk))
        } catch (e: Exception) {
            Log.e("MainViewModel", "installUpdate failed: ${e.message}", e)
            _state.emit(State.FailedUpdate(e))
        }
    }
}
