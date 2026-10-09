package com.nexastream.app.fragments.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.providers.Provider
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.utils.ParentalControlUtils
import com.nexastream.app.utils.ProviderChangeNotifier
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.UserDataCache.toEpisode
import com.nexastream.app.utils.UserDataCache.toMovie
import com.nexastream.app.utils.UserDataCache.toTvShow
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.combine6
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val database: AppDatabase,
    private val repository: HomeRepository
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    private val _userDataCache = MutableStateFlow<UserDataCache.UserData?>(null)
    private var currentProvider: Provider? = null

    private val continueWatchingFlow: Flow<List<AppAdapter.Item>> = combine(
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.continueWatchingMovies.isNotEmpty()) {
                emit(cache.continueWatchingMovies.map { it.toMovie() })
            } else {
                emitAll(database.movieDao().getWatchingMovies())
            }
        }.onStart { emit(emptyList()) },
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
            } else {
                emitAll(database.episodeDao().getWatchingEpisodes())
            }
        }.onStart { emit(emptyList()) },
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
            } else {
                emitAll(database.episodeDao().getNextEpisodesToWatch())
            }
        }.onStart { emit(emptyList()) },
        database.tvShowDao().getAll().onStart { emit(emptyList()) },
    ) { watchingMovies: List<Movie>, watchingEpisodes: List<Episode>, watchNextEpisodes: List<Episode>, tvShows: List<TvShow> ->
        val allEpisodes = (watchingEpisodes + watchNextEpisodes).distinctBy { e -> e.id }
        Triple(watchingMovies, allEpisodes, tvShows)
    }.flatMapLatest { (watchingMovies, allEpisodes, tvShows) ->
        val seasonIds = allEpisodes.mapNotNull { e -> e.season?.id }.distinct()
        database.seasonDao().getByIdsAsFlow(seasonIds).map { seasons ->
            val tvShowsMap = tvShows.associateBy { t -> t.id }
            val seasonsMap = seasons.associateBy { s -> s.id }

            val enrichedEpisodes = currentProvider?.let { provider ->
                repository.enrichContinueWatchingEpisodes(
                    provider,
                    allEpisodes.map { episode ->
                        episode.copy(
                            tvShow = episode.tvShow?.id?.let { id -> tvShowsMap[id] } ?: episode.tvShow,
                            season = episode.season?.id?.let { id -> seasonsMap[id] } ?: episode.season,
                        ).apply { merge(episode) }
                    }
                )
            } ?: emptyList()

            (watchingMovies + enrichedEpisodes)
                .sortedByDescending { item ->
                    when (item) {
                        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis ?: item.watchedDate?.timeInMillis ?: 0L
                        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis ?: item.watchedDate?.timeInMillis ?: 0L
                        else -> 0L
                    }
                } as List<AppAdapter.Item>
        }
    }.onStart { emit(emptyList()) }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine6(
        _state,
        continueWatchingFlow,
        // FAVORITES
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.favoritesMovies.isNotEmpty()) {
                emit(cache.favoritesMovies.map { it.toMovie() })
            } else {
                emitAll(database.movieDao().getFavorites())
            }
        }.onStart { emit(emptyList()) }.flowOn(Dispatchers.IO),
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.favoritesTvShows.isNotEmpty()) {
                emit(cache.favoritesTvShows.map { it.toTvShow() })
            } else {
                emitAll(database.tvShowDao().getFavorites())
            }
        }.onStart { emit(emptyList()) }.flowOn(Dispatchers.IO),

        // MOVIES DB
        _state.transformLatest { state ->
            if (state is State.SuccessLoading) {
                val ids = state.categories.flatMap { it.list }.filterIsInstance<Movie>().map { it.id }
                if (ids.isEmpty()) emit(emptyList()) else emitAll(database.movieDao().getByIds(ids))
            } else emit(emptyList<Movie>())
        }.onStart { emit(emptyList()) }.flowOn(Dispatchers.IO),

        // TV SHOWS DB
        _state.transformLatest { state ->
            if (state is State.SuccessLoading) {
                val ids = state.categories.flatMap { it.list }.filterIsInstance<TvShow>().map { it.id }
                if (ids.isEmpty()) emit(emptyList()) else emitAll(database.tvShowDao().getByIds(ids))
            } else emit(emptyList<TvShow>())
        }.onStart { emit(emptyList()) }.flowOn(Dispatchers.IO),

    ) { state: State, continueWatching: List<AppAdapter.Item>, favoritesMovies: List<Movie>, favoriteTvShows: List<TvShow>, moviesDb: List<Movie>, tvShowsDb: List<TvShow> ->
        if (state is State.SuccessLoading) {
            val moviesMap = moviesDb.associateBy { m -> m.id }
            val tvShowsMap = tvShowsDb.associateBy { t -> t.id }

            fun mergeItem(item: AppAdapter.Item): AppAdapter.Item = when (item) {
                is Movie -> moviesMap[item.id]?.takeIf { m -> !item.isSame(m) }?.let { m -> item.copy().merge(m) } ?: item
                is TvShow -> tvShowsMap[item.id]?.takeIf { t -> !item.isSame(t) }?.let { t -> item.copy().merge(t) } ?: item
                else -> item
            }

            val categories = mutableListOf<Category>()
            
            // 1. Featured (Hero Banner at Index 0)
            val heroCategory = state.categories.find { c -> 
                c.name == Category.FEATURED || 
                c.name == "Movies Banner" || 
                c.name == "Series Banner" || 
                c.name.endsWith("Banner", ignoreCase = true) 
            }
            
            heroCategory?.let { c ->
                categories.add(c.copy(list = c.list.map(::mergeItem))) 
            }

            // 1.5. Livestream (Priority)
            state.categories.find { it.name == "Livestream" && it != heroCategory }?.let { c ->
                categories.add(c.copy(list = c.list.map(::mergeItem)))
            }
            
            // 2. Continue Watching
            val continueWatchingList = continueWatching.distinctBy { item ->
                when (item) {
                    is Episode -> item.tvShow?.id ?: item.id
                    is Movie -> item.id
                    else -> item.hashCode().toString()
                }
            }
            if (continueWatchingList.isNotEmpty()) {
                categories.add(Category(
                    name = Category.CONTINUE_WATCHING,
                    list = continueWatchingList
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
            }
            
            // 3. Add other categories from provider (CDN, Trending Today, Genres)
            // and insert Favorites after Trending Today
            state.categories.filter { c -> c != heroCategory && c.name != "Livestream" }.forEach { c ->
                val mergedCategory = c.copy(list = c.list.map(::mergeItem))
                categories.add(mergedCategory)
                
                if (mergedCategory.name == "Trending Today") {
                    if (favoritesMovies.isNotEmpty()) {
                        categories.add(Category(name = Category.FAVORITE_MOVIES, list = favoritesMovies.map(::mergeItem))
                            .apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
                    }
                    if (favoriteTvShows.isNotEmpty()) {
                        categories.add(Category(name = Category.FAVORITE_TV_SHOWS, list = favoriteTvShows.map(::mergeItem))
                            .apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
                    }
                }
            }
            
            // If Trending Today wasn't found, add Favorites at the end
            if (categories.none { it.name == Category.FAVORITE_MOVIES } && (favoritesMovies.isNotEmpty() || favoriteTvShows.isNotEmpty())) {
                if (favoritesMovies.isNotEmpty()) categories.add(Category(name = Category.FAVORITE_MOVIES, list = favoritesMovies.map(::mergeItem)).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
                if (favoriteTvShows.isNotEmpty()) categories.add(Category(name = Category.FAVORITE_TV_SHOWS, list = favoriteTvShows.map(::mergeItem)).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
            }

            val filtered = if (UserPreferences.isParentalControlActive || UserPreferences.familyMode) {
                ParentalControlUtils.filterCategories(categories)
            } else {
                categories
            }

            val profile = _userInterestProfile.value
            val rankedCategories = com.nexastream.app.utils.RecommendationEngine.rankCategories(filtered, profile)
            State.SuccessLoading(rankedCategories)
        } else state
    }.flowOn(Dispatchers.IO)

    private val _userInterestProfile = MutableStateFlow(com.nexastream.app.utils.UserInterestProfile())

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val categories: List<Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        updateUserInterestProfile()
        val initialProvider = UserPreferences.currentProvider
        if (initialProvider != null) {
            currentProvider = initialProvider
            observeUserData(initialProvider)
        }
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                val provider = UserPreferences.currentProvider ?: return@collect
                currentProvider = provider
                observeUserData(provider)
                getHome(force = true)
            }
        }
        getHome()
    }

    private fun updateUserInterestProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2500)
            val profile = runCatching {
                com.nexastream.app.utils.RecommendationEngine.buildUserInterestProfile(database)
            }.getOrDefault(com.nexastream.app.utils.UserInterestProfile())
            _userInterestProfile.value = profile
        }
    }

    private var userDataJob: Job? = null
    private fun observeUserData(provider: Provider) {
        userDataJob?.cancel()
        userDataJob = viewModelScope.launch(Dispatchers.IO) {
            repository.getUserDataFlow(provider).collect {
                _userDataCache.value = it
                updateUserInterestProfile()
            }
        }
    }

    private var getHomeJob: Job? = null

    fun getHome(force: Boolean = false) {
        val provider = currentProvider ?: return
        if (!force && _state.value is State.SuccessLoading) return

        getHomeJob?.cancel()
        getHomeJob = viewModelScope.launch(Dispatchers.IO) {
            val cached = repository.getCachedHome(provider)
            if (!cached.isNullOrEmpty()) {
                _state.emit(State.SuccessLoading(cached))
            } else {
                _state.emit(State.SuccessLoading(emptyList()))
            }

            try {
                repository.getHomeFlow(provider).collect { categories ->
                    if (categories.isNotEmpty()) {
                        _state.emit(State.SuccessLoading(categories))
                        repository.updateUserDataCache(provider, _userDataCache.value)
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "getHome failed", e)
                if (_state.value !is State.SuccessLoading) {
                    _state.emit(State.FailedLoading(e))
                }
            }
        }
    }
}
