package com.nexastream.app.fragments.shorttv

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.extractors.GengaExtractor
import com.nexastream.app.extractors.MovieBoxExtractor
import com.nexastream.app.models.Movie
import com.nexastream.app.models.ShortTvItem
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.providers.MovieBoxProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ShortTvViewModel @Inject constructor() : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Success(val items: List<ShortTvItem>) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var currentCategoryIndex: Int = 0

    companion object {
        @Volatile
        private var cachedReelsMap = mutableMapOf<Int, List<ShortTvItem>>()
    }

    init {
        loadShortReels(categoryIndex = 0)
    }

    fun loadShortReels(categoryIndex: Int = currentCategoryIndex, forceRefresh: Boolean = false) {
        currentCategoryIndex = categoryIndex
        viewModelScope.launch {
            val cached = cachedReelsMap[categoryIndex]
            if (!forceRefresh && !cached.isNullOrEmpty()) {
                _uiState.value = UiState.Success(cached)
                return@launch
            }

            _uiState.value = UiState.Loading

            try {
                val movieBoxItems = withContext(Dispatchers.IO) {
                    try {
                        val itemsList = mutableListOf<AppAdapter.Item>()

                        when (categoryIndex) {
                            0 -> { // All Shorts & Dramas
                                val queries = listOf("Short Drama", "Vertical Drama", "Mini Series", "Reels", "Short TV", "Drama Short", "Shorts", "Drama")
                                val searchResults = queries.flatMap { q ->
                                    runCatching { MovieBoxProvider.search(q, 1, null) }.getOrElse { emptyList() }
                                }
                                val tvShows = runCatching { MovieBoxProvider.getTvShows(1) }.getOrElse { emptyList() }
                                itemsList.addAll(searchResults)
                                itemsList.addAll(tvShows)
                            }
                            1 -> { // Short Dramas
                                val queries = listOf("Short Drama", "Vertical Drama", "Drama Short", "CEO", "Love", "Boss", "Secret", "Romance")
                                val searchResults = queries.flatMap { q ->
                                    runCatching { MovieBoxProvider.search(q, 1, null) }.getOrElse { emptyList() }
                                }
                                itemsList.addAll(searchResults)
                            }
                            2 -> { // Asian Dramas
                                val asianItems = runCatching { MovieBoxProvider.getCategory("asian", 1) }.getOrElse { emptyList() }
                                val queries = listOf("K-Drama", "Asian Drama", "Chinese Drama", "Korean Drama")
                                val searchResults = queries.flatMap { q ->
                                    runCatching { MovieBoxProvider.search(q, 1, null) }.getOrElse { emptyList() }
                                }
                                itemsList.addAll(asianItems)
                                itemsList.addAll(searchResults)
                            }
                            3 -> { // Mini Series
                                val queries = listOf("Mini Series", "Mini TV", "Short Series", "Reel Series", "Short")
                                val searchResults = queries.flatMap { q ->
                                    runCatching { MovieBoxProvider.search(q, 1, null) }.getOrElse { emptyList() }
                                }
                                itemsList.addAll(searchResults)
                            }
                            else -> {
                                val tvShows = runCatching { MovieBoxProvider.getTvShows(1) }.getOrElse { emptyList() }
                                itemsList.addAll(tvShows)
                            }
                        }

                        val distinctItems = itemsList.map { item ->
                            when (item) {
                                is Movie -> item.copy().apply { providerName = "MovieBox" }
                                is TvShow -> item.copy().apply { providerName = "MovieBox" }
                                else -> item
                            }
                        }.distinctBy {
                            when (it) {
                                is Movie -> it.id
                                is TvShow -> it.id
                                else -> it.hashCode().toString()
                            }
                        }

                        coroutineScope {
                            distinctItems.mapIndexed { index, item ->
                                async(Dispatchers.IO) {
                                    val title = when (item) {
                                        is Movie -> item.title
                                        is TvShow -> item.title
                                        else -> return@async null
                                    }
                                    val description = when (item) {
                                        is Movie -> item.overview.orEmpty()
                                        is TvShow -> item.overview.orEmpty()
                                    }
                                    val poster = when (item) {
                                        is Movie -> item.poster.orEmpty()
                                        is TvShow -> item.poster.orEmpty()
                                    }
                                    val id = when (item) {
                                        is Movie -> item.id
                                        is TvShow -> item.id
                                    }

                                    val videoType = if (item is Movie) {
                                        Video.Type.Movie(
                                            id = id,
                                            title = title,
                                            releaseDate = "2026",
                                            poster = poster,
                                            imdbId = null
                                        )
                                    } else {
                                        Video.Type.Episode(
                                            id = id,
                                            number = 1,
                                            title = title,
                                            poster = poster,
                                            overview = description,
                                            tvShow = Video.Type.Episode.TvShow(
                                                id = id,
                                                title = title,
                                                poster = poster,
                                                banner = poster,
                                                releaseDate = "2026",
                                                imdbId = null
                                            ),
                                            season = Video.Type.Episode.Season(number = 1, title = "Season 1")
                                        )
                                    }

                                    val (streamUrl, streamHeaders) = resolveMovieBoxStream(id, title, videoType, item is Movie)

                                    if (streamUrl.isBlank()) return@async null

                                    ShortTvItem(
                                        id = "moviebox_${categoryIndex}_$id",
                                        subjectId = id,
                                        title = title,
                                        description = description.ifBlank { "MovieBox Short Drama" },
                                        videoUrl = streamUrl,
                                        posterUrl = poster,
                                        headers = streamHeaders,
                                        episodeNumber = index + 1,
                                        totalEpisodes = 10,
                                        mediaType = if (item is Movie) "movie" else "tv",
                                        likeCount = (800..5000).random()
                                    )
                                }
                            }.awaitAll().filterNotNull()
                        }
                    } catch (e: Exception) {
                        Log.e("ShortTvViewModel", "Error fetching MovieBox ShortTV reels: ${e.message}", e)
                        emptyList()
                    }
                }

                if (movieBoxItems.isNotEmpty()) {
                    cachedReelsMap[categoryIndex] = movieBoxItems
                    _uiState.value = UiState.Success(movieBoxItems)
                } else {
                    _uiState.value = UiState.Error("No MovieBox short streams available for this category")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.localizedMessage ?: "Failed to load MovieBox short reels")
            }
        }
    }

    private suspend fun resolveMovieBoxStream(
        id: String,
        title: String,
        videoType: Video.Type,
        isMovie: Boolean
    ): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        // 1. Try MovieBox provider server first
        try {
            val servers = MovieBoxProvider.getServers(id, videoType)
            val mbServer = servers.find { 
                it.name.contains("MovieBox", ignoreCase = true) || it.name.contains("Genga", ignoreCase = true) 
            } ?: servers.firstOrNull()

            if (mbServer != null) {
                val video = MovieBoxProvider.getVideo(mbServer)
                val url = video.streams?.firstOrNull()?.url ?: video.source
                val headers = video.headers.orEmpty()
                if (url.isNotBlank()) return@withContext Pair(url, headers)
            }
        } catch (e: Exception) {
            Log.w("ShortTvViewModel", "MovieBox server lookup failed for $id ($title): ${e.message}")
        }

        // 2. Direct MovieBoxExtractor fallback
        try {
            val link = if (isMovie) "movie|$id|$title|0|0" else "tv|$id|$title|1|1"
            val video = MovieBoxExtractor().extract(link)
            val url = video.streams?.firstOrNull()?.url ?: video.source
            if (url.isNotBlank()) return@withContext Pair(url, video.headers.orEmpty())
        } catch (e: Exception) {
            Log.w("ShortTvViewModel", "Direct MovieBoxExtractor failed for $id ($title): ${e.message}")
        }

        // 3. Direct GengaExtractor fallback
        try {
            val link = if (isMovie) "movie|$id|$title|0|0" else "tv|$id|$title|1|1"
            val video = GengaExtractor().extract(link)
            val url = video.streams?.firstOrNull()?.url ?: video.source
            if (url.isNotBlank()) return@withContext Pair(url, video.headers.orEmpty())
        } catch (e: Exception) {
            Log.w("ShortTvViewModel", "Direct GengaExtractor failed for $id ($title): ${e.message}")
        }

        Pair("", emptyMap())
    }

    fun selectEpisode(reelId: String, episodeNumber: Int) {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState is UiState.Success) {
                val targetItem = currentState.items.firstOrNull { it.id == reelId } ?: return@launch

                val videoType = Video.Type.Episode(
                    id = targetItem.subjectId,
                    number = episodeNumber,
                    title = targetItem.title,
                    poster = targetItem.posterUrl,
                    overview = targetItem.description,
                    tvShow = Video.Type.Episode.TvShow(
                        id = targetItem.subjectId,
                        title = targetItem.title,
                        poster = targetItem.posterUrl,
                        banner = targetItem.posterUrl,
                        releaseDate = "2026",
                        imdbId = null
                    ),
                    season = Video.Type.Episode.Season(number = 1, title = "Season 1")
                )

                val (streamUrl, streamHeaders) = withContext(Dispatchers.IO) {
                    try {
                        val servers = MovieBoxProvider.getServers(targetItem.subjectId, videoType)
                        val mbServer = servers.find { 
                            it.name.contains("MovieBox", ignoreCase = true) || it.name.contains("Genga", ignoreCase = true) 
                        } ?: servers.firstOrNull()

                        if (mbServer != null) {
                            val video = MovieBoxProvider.getVideo(mbServer)
                            val url = video.streams?.firstOrNull()?.url ?: video.source
                            if (url.isNotBlank()) return@withContext Pair(url, video.headers.orEmpty())
                        }

                        val link = "tv|${targetItem.subjectId}|${targetItem.title}|1|$episodeNumber"
                        val video = GengaExtractor().extract(link)
                        val url = video.streams?.firstOrNull()?.url ?: video.source
                        if (url.isNotBlank()) Pair(url, video.headers.orEmpty())
                        else Pair(targetItem.videoUrl, targetItem.headers)
                    } catch (e: Exception) {
                        Log.e("ShortTvViewModel", "Error selecting episode $episodeNumber for ${targetItem.subjectId}: ${e.message}")
                        Pair(targetItem.videoUrl, targetItem.headers)
                    }
                }

                val updatedItems = currentState.items.map { item ->
                    if (item.id == reelId) {
                        item.copy(
                            episodeNumber = episodeNumber,
                            videoUrl = streamUrl,
                            headers = streamHeaders
                        )
                    } else item
                }
                cachedReelsMap[currentCategoryIndex] = updatedItems
                _uiState.value = UiState.Success(updatedItems)
            }
        }
    }

    fun toggleLike(reelId: String) {
        val currentState = _uiState.value
        if (currentState is UiState.Success) {
            val updatedItems = currentState.items.map { item ->
                if (item.id == reelId) {
                    val newIsLiked = !item.isLiked
                    val newCount = if (newIsLiked) item.likeCount + 1 else item.likeCount - 1
                    item.copy(isLiked = newIsLiked, likeCount = newCount)
                } else item
            }
            cachedReelsMap[currentCategoryIndex] = updatedItems
            _uiState.value = UiState.Success(updatedItems)
        }
    }

    fun cycleSpeed(reelId: String) {
        val currentState = _uiState.value
        if (currentState is UiState.Success) {
            val updatedItems = currentState.items.map { item ->
                if (item.id == reelId) {
                    val nextSpeed = when (item.speed) {
                        1.0f -> 1.25f
                        1.25f -> 1.5f
                        1.5f -> 2.0f
                        else -> 1.0f
                    }
                    item.copy(speed = nextSpeed)
                } else item
            }
            cachedReelsMap[currentCategoryIndex] = updatedItems
            _uiState.value = UiState.Success(updatedItems)
        }
    }
}
