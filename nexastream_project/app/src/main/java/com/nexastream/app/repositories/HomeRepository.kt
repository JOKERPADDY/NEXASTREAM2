package com.nexastream.app.repositories

import android.content.Context
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.WatchItem
import com.nexastream.app.providers.Provider
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.utils.HomeCacheStore
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.UserDataCache.toCached
import com.nexastream.app.utils.UserDataCache.toEpisode
import com.nexastream.app.utils.UserDataCache.toMovie
import com.nexastream.app.utils.UserDataCache.toTvShow
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class HomeRepository(
    private val context: Context,
    private val database: AppDatabase
) {
    private val continueWatchingTvShowCache = ConcurrentHashMap<String, TvShow>()
    private val continueWatchingSeasonEpisodesCache = ConcurrentHashMap<String, List<Episode>>()

    fun clearCache() {
        continueWatchingTvShowCache.clear()
        continueWatchingSeasonEpisodesCache.clear()
    }

    suspend fun search(provider: Provider, query: String): List<AppAdapter.Item> {
        return provider.search(query).mapNotNull { item ->
            when (item) {
                is Movie -> item
                is TvShow -> item
                is com.nexastream.app.models.Genre -> item
                else -> null
            }
        }
    }

    suspend fun getHome(provider: Provider): List<Category> {
        val categories = provider.getHome()
        if (categories.isEmpty()) {
            val cached = HomeCacheStore.read(context, provider)
            if (!cached.isNullOrEmpty()) {
                return cached
            }
        } else {
            HomeCacheStore.write(context, provider, categories)
        }
        return categories
    }

    fun getHomeFlow(provider: Provider): Flow<List<Category>> = kotlinx.coroutines.flow.flow {
        val cached = HomeCacheStore.read(context, provider)
        if (!cached.isNullOrEmpty()) {
            emit(cached)
        }

        if (provider is com.nexastream.app.providers.NexaHomeProvider) {
            val fullCategories = com.nexastream.app.providers.NexaHomeProvider.getHomeProgressive(
                onPhase0 = { phase0Categories ->
                    if (phase0Categories.isNotEmpty()) {
                        emit(phase0Categories)
                    }
                },
                onPhase1 = { phase1Categories ->
                    if (phase1Categories.isNotEmpty()) {
                        emit(phase1Categories)
                    }
                }
            )
            if (fullCategories.isNotEmpty()) {
                emit(fullCategories)
                HomeCacheStore.write(context, provider, fullCategories)
            }
        } else {
            val categories = provider.getHome()
            if (categories.isNotEmpty()) {
                emit(categories)
                HomeCacheStore.write(context, provider, categories)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getCachedHome(provider: Provider): List<Category>? {
        return HomeCacheStore.read(context, provider)
    }

    suspend fun enrichContinueWatchingEpisodes(
        provider: Provider,
        episodes: List<Episode>
    ): List<Episode> {
        val targetList = if (episodes.size > 10) episodes.take(10) else episodes
        
        return targetList.map { episode ->
            val tvShowId = episode.tvShow?.id
            val cachedTvShow = tvShowId?.let { continueWatchingTvShowCache[it] }

            val mergedTvShow = cachedTvShow?.copy().apply {
                this?.let { show ->
                    episode.tvShow?.let { existingTvShow -> show.merge(existingTvShow) }
                }
            } ?: episode.tvShow

            val resolvedSeason = episode.season?.let { season ->
                mergedTvShow?.seasons?.firstOrNull { it.id == season.id || it.number == season.number }
                    ?: season
            }

            val cachedEpisodes = resolvedSeason?.id?.let { continueWatchingSeasonEpisodesCache[it] }
            val resolvedEpisode = cachedEpisodes?.firstOrNull { seasonEpisode ->
                seasonEpisode.id == episode.id || seasonEpisode.number == episode.number
            }

            episode.copy(
                title = resolvedEpisode?.title ?: episode.title,
                overview = resolvedEpisode?.overview ?: episode.overview,
                poster = resolvedEpisode?.poster ?: episode.poster,
                tvShow = mergedTvShow,
                season = resolvedSeason,
            )
        }
    }

    fun getUserDataFlow(provider: Provider): Flow<UserDataCache.UserData?> {
        // Implementation of loading data from DB/Cache as in HomeViewModel
        // This can be further refined with Hilt/Injecting Daos
        return UserDataCache.userDataFlow(context, provider)
    }

    suspend fun updateUserDataCache(provider: Provider, currentCache: UserDataCache.UserData?) = withContext(Dispatchers.IO) {
        val movies = database.movieDao().getFavorites().first()
        val tvShows = database.tvShowDao().getFavorites().first()
        val watchingMovies = database.movieDao().getWatchingMovies().first()
        val watchingEpisodes = database.episodeDao().getWatchingEpisodes().first()

        val newData = UserDataCache.UserData(
            favoritesMovies = preserveCacheOrder(
                cached = currentCache?.favoritesMovies ?: emptyList(),
                incoming = movies.filter { it.isFavorite }.map { it.toCached() },
                idOf = { it.id },
            ),
            favoritesTvShows = preserveCacheOrder(
                cached = currentCache?.favoritesTvShows ?: emptyList(),
                incoming = tvShows.filter { it.isFavorite }.map { it.toCached() },
                idOf = { it.id },
            ),
            continueWatchingMovies = preserveCacheOrder(
                cached = currentCache?.continueWatchingMovies ?: emptyList(),
                incoming = (movies + watchingMovies)
                    .filter { it.watchHistory != null }
                    .map { it.toCached() },
                idOf = { it.id },
            ),
            continueWatchingEpisodes = preserveCacheOrder(
                cached = currentCache?.continueWatchingEpisodes ?: emptyList(),
                incoming = watchingEpisodes
                    .filter { it.watchHistory != null }
                    .map { it.toCached() },
                idOf = { it.id },
            ),
        )

        UserDataCache.write(context, provider, newData)
    }

    private fun <T> preserveCacheOrder(
        cached: List<T>,
        incoming: List<T>,
        idOf: (T) -> String,
    ): List<T> {
        val incomingById = incoming.associateBy(idOf)
        val orderedExisting = cached.mapNotNull { cachedItem -> incomingById[idOf(cachedItem)] }
        val appendedNew = incoming.filter { incomingItem ->
            cached.none { cachedItem -> idOf(cachedItem) == idOf(incomingItem) }
        }
        return orderedExisting + appendedNew
    }
}
