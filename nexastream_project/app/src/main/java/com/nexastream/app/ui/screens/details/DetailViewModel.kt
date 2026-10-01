package com.nexastream.app.ui.screens.details

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.DownloadManager
import com.nexastream.app.models.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.DownloadQualityFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

data class DetailUiState(
    val isLoading: Boolean = false,
    val show: Show? = null,
    val servers: List<Video.Server> = emptyList(),
    val isDownloading: Boolean = false,
    val error: String? = null
)

@OptIn(UnstableApi::class)
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val rawId: String = savedStateHandle.get<String>("id") ?: ""
    private val id: String = runCatching { URLDecoder.decode(rawId, "UTF-8") }.getOrDefault(rawId)
    
    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState

    init {
        loadDetails()
    }

    fun retry() {
        loadDetails()
    }

    private fun loadDetails() {
        val provider = UserPreferences.currentProvider ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Try to get from DB first to check type and favorite/watch status
                val dbMovie = withContext(Dispatchers.IO) { database.movieDao().getById(id) }
                val dbTvShow = withContext(Dispatchers.IO) { database.tvShowDao().getById(id) }

                val show: Show? = try {
                    withContext(Dispatchers.IO) {
                        if (dbMovie != null) {
                            provider.getMovie(id).apply { merge(dbMovie) }
                        } else if (dbTvShow != null) {
                            provider.getTvShow(id).apply { merge(dbTvShow) }
                        } else if (id.contains("/movie/")) {
                            provider.getMovie(id)
                        } else if (id.contains("/series/") || id.contains("/tv/")) {
                            provider.getTvShow(id)
                        } else {
                            runCatching { provider.getMovie(id) }.getOrNull()
                                ?: provider.getTvShow(id)
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to local DB if network request failed
                    dbMovie ?: dbTvShow ?: throw e
                }

                _uiState.value = DetailUiState(show = show, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = DetailUiState(error = e.message ?: "Failed to load details", isLoading = false)
            }
        }
    }

    fun toggleFavorite() {
        val currentShow = _uiState.value.show ?: return
        val provider = UserPreferences.currentProvider ?: return
        
        viewModelScope.launch {
            val isFavorite = !currentShow.isFavorite
            
            val updatedShow: Show = when (currentShow) {
                is Movie -> {
                    val newMovie = currentShow.copy(isFavorite = isFavorite)
                    if (isFavorite) {
                        newMovie.favoritedAtMillis = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            database.movieDao().insert(newMovie)
                            UserDataCache.addMovieToFavorites(context, provider, newMovie)
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            database.movieDao().delete(newMovie)
                            UserDataCache.removeMovieFromFavorites(context, provider, newMovie.id)
                        }
                    }
                    newMovie
                }
                is TvShow -> {
                    val newTvShow = currentShow.copy(isFavorite = isFavorite)
                    if (isFavorite) {
                        newTvShow.favoritedAtMillis = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            database.tvShowDao().insert(newTvShow)
                            UserDataCache.addTvShowToFavorites(context, provider, newTvShow)
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            database.tvShowDao().delete(newTvShow)
                            UserDataCache.removeTvShowFromFavorites(context, provider, newTvShow.id)
                        }
                    }
                    newTvShow
                }
            }
            
            _uiState.value = _uiState.value.copy(show = updatedShow)
        }
    }

    fun startDownload(server: Video.Server, type: Video.Type) {
        if (!Provider.supportsDownloads(UserPreferences.currentProvider)) {
            _uiState.value = _uiState.value.copy(isDownloading = false)
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isDownloading = true)
                val video = withContext(Dispatchers.IO) {
                    UserPreferences.currentProvider!!.getVideo(server)
                }

                val (downloadId, downloadTitle, downloadPoster) = when (type) {
                    is Video.Type.Movie -> Triple(type.id, type.title, type.poster)
                    is Video.Type.Episode -> {
                        val title = "${type.tvShow.title} - S${type.season.number}E${type.number}"
                        Triple(type.id, title, type.poster)
                    }
                }

                downloadManager.startDownload(
                    id = downloadId,
                    title = downloadTitle,
                    poster = downloadPoster,
                    url = video.source,
                    quality = DownloadQualityFormatter.qualityLabel(server, video),
                    headers = video.headers,
                    mimeType = video.type
                )
                _uiState.value = _uiState.value.copy(isDownloading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Download failed: ${e.message}", isDownloading = false)
            }
        }
    }

    fun loadServers(id: String, type: Video.Type) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(servers = emptyList(), error = null)
                val servers = withContext(Dispatchers.IO) {
                    UserPreferences.currentProvider!!.getServers(id, type)
                }
                _uiState.value = _uiState.value.copy(servers = servers)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}
