package com.nexastream.app.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Download
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.models.WatchItem
import com.nexastream.app.providers.CdnLiveTvProvider
import com.nexastream.app.providers.Provider
import com.nexastream.app.trakt.TraktManager
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.UserDataCache.toEpisode
import com.nexastream.app.utils.UserDataCache.toMovie
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.WatchNextUtils
import com.nexastream.app.utils.format
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Calendar
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

data class PlayerUiState(
    val isLoading: Boolean = false,
    val video: Video? = null,
    val movie: Movie? = null,
    val tvShow: TvShow? = null,
    val currentEpisode: Episode? = null,
    val servers: List<Video.Server> = emptyList(),
    val currentServer: Video.Server? = null,
    val error: String? = null,
    val isOffline: Boolean = false
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase,
    val dataSourceFactory: androidx.media3.datasource.DataSource.Factory
) : ViewModel() {

    private val id: String = URLDecoder.decode(checkNotNull(savedStateHandle["id"]), "UTF-8")
    private var videoType: Video.Type? = null
    
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private val provider: Provider
        get() {
            if (id.startsWith("cdn:")) {
                return CdnLiveTvProvider
            }
            return UserPreferences.currentProvider
        }

    init {
        loadVideo()
    }

    fun retry() {
        loadVideo()
    }

    suspend fun resolveResumePosition(): Long = withContext(Dispatchers.IO) {
        val currentProvider = provider
        val currentVideoType = videoType ?: return@withContext 0L

        val watchItem: WatchItem? = when (currentVideoType) {
            is Video.Type.Movie -> {
                val movie = currentProvider?.let {
                    UserDataCache.read(context, it)?.continueWatchingMovies
                        ?.find { cachedMovie -> cachedMovie.id == currentVideoType.id }?.toMovie()
                }
                movie ?: database.movieDao().getById(currentVideoType.id)
            }
            is Video.Type.Episode -> {
                val episode = provider?.let {
                    UserDataCache.read(context, it)?.continueWatchingEpisodes
                        ?.find { cachedEpisode -> cachedEpisode.id == currentVideoType.id }?.toEpisode()
                }
                episode ?: database.episodeDao().getById(currentVideoType.id)
            }
        }

        watchItem?.watchHistory
            ?.let { it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds }
            ?.coerceAtLeast(0L)
            ?: 0L
    }

    fun saveProgress(
        position: Long,
        duration: Long,
        hasStarted: Boolean,
        hasFinished: Boolean,
        hasReallyFinished: Boolean
    ) {
        val currentVideoType = videoType ?: return
        viewModelScope.launch(Dispatchers.IO) {
            persistPlaybackState(
                videoType = currentVideoType,
                hasStarted = hasStarted,
                hasFinished = hasFinished,
                hasReallyFinished = hasReallyFinished,
                playbackPosition = position,
                playbackDuration = duration
            )
        }
    }

    private suspend fun persistPlaybackState(
        videoType: Video.Type,
        hasStarted: Boolean,
        hasFinished: Boolean,
        hasReallyFinished: Boolean,
        playbackPosition: Long,
        playbackDuration: Long,
    ) {
        val watchItem: WatchItem? = when (videoType) {
            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
        }

        when {
            hasStarted && !hasFinished -> {
                watchItem?.isWatched = false
                watchItem?.watchedDate = null
                watchItem?.watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = playbackPosition,
                    durationMillis = playbackDuration,
                )
            }

            hasFinished -> {
                watchItem?.isWatched = true
                watchItem?.watchedDate = Calendar.getInstance()
                watchItem?.watchHistory = null

                val tmdbId = when (videoType) {
                    is Video.Type.Movie -> videoType.id.toIntOrNull()
                    is Video.Type.Episode -> videoType.tvShow.id.toIntOrNull()
                }
                tmdbId?.let { id ->
                    if (videoType is Video.Type.Movie) TraktManager.syncMovieToHistory(id)
                    else TraktManager.syncEpisodeToHistory(id)
                }
            }
        }

        when (videoType) {
            is Video.Type.Movie -> {
                val provider = UserPreferences.currentProvider ?: return
                val movie = watchItem as? Movie
                movie?.let {
                    database.movieDao().update(it)
                    UserDataCache.syncMovieToCache(context, provider, it)
                    WatchNextUtils.updateWatchNext(context, it)
                }
            }

            is Video.Type.Episode -> {
                val provider = UserPreferences.currentProvider ?: return
                val episode = watchItem as? Episode
                episode?.let {
                    if (hasFinished) {
                        database.episodeDao().resetProgressionFromEpisode(videoType.id)
                        UserDataCache.removeEpisodeFromContinueWatching(context, provider, it.id)
                    }
                    database.episodeDao().update(it)
                    if (!hasFinished) {
                        UserDataCache.syncEpisodeToCache(context, provider, it)
                    }
                    WatchNextUtils.updateWatchNext(context, it)

                    it.tvShow?.let { tvShow ->
                        database.tvShowDao().getById(tvShow.id)
                    }?.let { tvShow ->
                        val episodeDao = database.episodeDao()
                        val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                        database.tvShowDao().save(tvShow.copy().apply {
                            merge(tvShow)
                            isWatching = !hasReallyFinished || isStillWatching
                        })
                    }
                }
            }
        }
    }

    fun selectServer(server: Video.Server) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null, currentServer = server) }
            try {
                val video = UserPreferences.currentProvider.getVideo(server)
                if (video.source.isEmpty()) throw Exception("Empty source from ${server.name}")
                _uiState.update { it.copy(video = video, isLoading = false) }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Manual server switch failed: ${e.message}")
                _uiState.update { it.copy(error = "Server ${server.name} failed: ${e.message}", isLoading = false) }
            }
        }
    }

    fun onPlayerError() {
        // Automatically try next server on error
        val currentServers = _uiState.value.servers
        val currentServer = _uiState.value.currentServer
        val currentIndex = currentServers.indexOf(currentServer)
        
        if (currentIndex != -1 && currentIndex < currentServers.size - 1) {
            val nextServer = currentServers[currentIndex + 1]
            Log.i("PlayerViewModel", "Playback error, auto-rotating to: ${nextServer.name}")
            selectServer(nextServer)
        } else {
            _uiState.update { it.copy(error = "All servers failed. Please try again later.", isLoading = false) }
        }
    }

    private fun loadVideo() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Check if downloaded
            val download = database.downloadDao().getById(id)
            if (download?.isPlayableOffline == true) {
                Log.d("PlayerViewModel", "Found completed download, playing offline from Media3 cache")
                _uiState.update {
                    it.copy(
                        video = Video(
                            source = download.url,
                            headers = download.headers,
                            type = download.mimeType,
                        ),
                        isLoading = false,
                        isOffline = true
                    )
                }
                return@launch
            }

            val provider = UserPreferences.currentProvider ?: return@launch
            try {
                var movie: Movie? = null
                var tvShow: TvShow? = null
                var currentEpisode: Episode? = null

                // Determine type based on ID content
                if (id.contains("/movie/")) {
                    movie = provider.getMovie(id)
                    this@PlayerViewModel.videoType = Video.Type.Movie(
                        id = movie.id,
                        title = movie.title,
                        releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                        poster = movie.poster ?: "",
                        imdbId = movie.imdbId
                    )
                } else if (id.contains("/series/") || id.contains("/tv/") || id.contains("episode_id=")) {
                    if (id.contains("episode_id=")) {
                        this@PlayerViewModel.videoType = Video.Type.Episode(
                            id = id,
                            number = 1,
                            title = "",
                            poster = null,
                            overview = null,
                            tvShow = Video.Type.Episode.TvShow(id="", title="", poster=null, banner=null, releaseDate=null, imdbId=null),
                            season = Video.Type.Episode.Season(number=1, title=null)
                        )
                    } else {
                        tvShow = provider.getTvShow(id)
                        val firstEpisode = tvShow.seasons.firstOrNull()?.episodes?.firstOrNull()
                        this@PlayerViewModel.videoType = Video.Type.Episode(
                            id = firstEpisode?.id ?: id,
                            number = firstEpisode?.number ?: 1,
                            title = firstEpisode?.title,
                            poster = firstEpisode?.poster,
                            overview = firstEpisode?.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = tvShow.id,
                                title = tvShow.title,
                                poster = tvShow.poster,
                                banner = tvShow.banner,
                                releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                                imdbId = tvShow.imdbId
                            ),
                            season = Video.Type.Episode.Season(
                                number = tvShow.seasons.firstOrNull()?.number ?: 1,
                                title = tvShow.seasons.firstOrNull()?.title
                            )
                        )
                    }
                } else {
                    runCatching {
                        movie = provider.getMovie(id)
                        this@PlayerViewModel.videoType = Video.Type.Movie(
                            id = movie!!.id,
                            title = movie!!.title,
                            releaseDate = movie!!.released?.format("yyyy-MM-dd") ?: "",
                            poster = movie!!.poster ?: "",
                            imdbId = movie!!.imdbId
                        )
                    }.onFailure {
                        tvShow = provider.getTvShow(id)
                        this@PlayerViewModel.videoType = Video.Type.Episode(
                            id = id, number=1, title="", poster=null, overview=null,
                            tvShow=Video.Type.Episode.TvShow(id="", title="", poster=null, banner=null, releaseDate=null, imdbId=null),
                            season=Video.Type.Episode.Season(number=1, title=null)
                        )
                    }
                }

                val videoType = this@PlayerViewModel.videoType!!
                Log.d("PlayerViewModel", "Fetching servers for ID: $id with type: $videoType")
                val servers = provider.getServers(id, videoType!!)
                if (servers.isEmpty()) throw Exception("No servers found for this content")

                _uiState.update { it.copy(servers = servers, movie = movie, tvShow = tvShow) }
                
                // Start with first server
                selectServer(servers.first())

            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Initial load failed: ${e.message}", e)
                _uiState.update { 
                    it.copy(
                        error = e.message ?: "Failed to load movie servers",
                        isLoading = false
                    )
                }
            }
        }
    }
}
