package com.nexastream.app.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.WatchItem
import com.nexastream.app.providers.Provider
import com.nexastream.app.ui.UserDataNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object UserDataCache {

    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, UserData>()

    data class UserData(
        val favoritesMovies: List<CachedMovie> = emptyList(),
        val favoritesTvShows: List<CachedTvShow> = emptyList(),
        val continueWatchingMovies: List<CachedMovie> = emptyList(),
        val continueWatchingEpisodes: List<CachedEpisode> = emptyList(),
    )

    private fun UserData.normalized(): UserData = copy(
        favoritesMovies = favoritesMovies.sortedByDescending { it.favoritedAtMillis ?: 0L },
        favoritesTvShows = favoritesTvShows.sortedByDescending { it.favoritedAtMillis ?: 0L },
        continueWatchingMovies = continueWatchingMovies.sortedByDescending { it.lastEngagementTimeUtcMillis ?: 0L },
        continueWatchingEpisodes = continueWatchingEpisodes.sortedByDescending { it.lastEngagementTimeUtcMillis ?: 0L },
    )

    // -------------------------
    // CACHE FILE
    // -------------------------

    private fun cacheKey(provider: Provider): String {
        val baseUrlKey = provider.baseUrl.trim().trimEnd('/')
        return listOf(provider.name, baseUrlKey)
            .filter { it.isNotEmpty() }
            .joinToString("__")
    }

    private fun cacheFile(context: Context, cacheKey: String): File {
        val safeName = cacheKey.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
        val file = File(context.filesDir, "user-data-cache/$safeName.json")
        Log.d("CACHE_PATH", file.absolutePath)
        return file
    }

    // -------------------------
    // READ / WRITE
    // -------------------------

    suspend fun read(context: Context, provider: Provider): UserData? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val key = cacheKey(provider)

        memoryCache[key]?.let { return@withContext it }

        val file = cacheFile(context, key)
        if (!file.exists()) return@withContext null

        runCatching {
            gson.fromJson(file.readText(), UserData::class.java).normalized().also {
                memoryCache[key] = it
            }
        }.getOrNull()
    }

    suspend fun write(context: Context, provider: Provider, newData: UserData) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val key = cacheKey(provider)
        val normalizedData = newData.normalized()
        val oldData = memoryCache[key]

        // ✅ prevent spam
        if (oldData == normalizedData) return@withContext

        memoryCache[key] = normalizedData

        runCatching {
            cacheFile(context, key).apply {
                parentFile?.mkdirs()
                writeText(gson.toJson(normalizedData))
            }
        }
        com.nexastream.app.ui.UserDataNotifier.notifyChanged()
    }

    suspend fun clear(context: Context, provider: Provider) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val key = cacheKey(provider)
        memoryCache.remove(key)
        cacheFile(context, key).delete()
    }

    suspend fun clearAll(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        memoryCache.clear()
        val cacheDir = File(context.filesDir, "user-data-cache")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }

    fun userDataFlow(context: Context, provider: Provider): Flow<UserData?> {
        return flow {
            com.nexastream.app.ui.UserDataNotifier.updates.collect {
                emit(read(context, provider))
            }
        }
    }

    // -------------------------
    // WRITE HELPERS (FIXED)
    // -------------------------

    suspend fun writeMovies(context: Context, provider: Provider, movies: List<Movie>) {
        val current = read(context, provider) ?: UserData()
        val moviesById = movies.associateBy { it.id }

        val newData = current.copy(
            favoritesMovies = current.favoritesMovies.mapNotNull { cached ->
                moviesById[cached.id]?.takeIf { it.isFavorite }?.toCached()
            } + movies.filter { it.isFavorite && current.favoritesMovies.none { cached -> cached.id == it.id } }
                .map { it.toCached() },
            continueWatchingMovies = current.continueWatchingMovies.mapNotNull { cached ->
                moviesById[cached.id]?.takeIf { it.watchHistory != null }?.toCached()
            } + movies.filter { it.watchHistory != null && current.continueWatchingMovies.none { cached -> cached.id == it.id } }
                .map { it.toCached() }
        )

        write(context, provider, newData)
    }

    suspend fun writeTvShows(context: Context, provider: Provider, tvShows: List<TvShow>) {
        val current = read(context, provider) ?: UserData()
        val tvShowsById = tvShows.associateBy { it.id }

        val newData = current.copy(
            favoritesTvShows = current.favoritesTvShows.mapNotNull { cached ->
                tvShowsById[cached.id]?.takeIf { it.isFavorite }?.toCached()
            } + tvShows.filter { it.isFavorite && current.favoritesTvShows.none { cached -> cached.id == it.id } }
                .map { it.toCached() }
        )

        write(context, provider, newData)
    }

    suspend fun writeEpisodes(context: Context, provider: Provider, episodes: List<Episode>) {
        val current = read(context, provider) ?: UserData()
        val episodesById = episodes.associateBy { it.id }

        val newData = current.copy(
            continueWatchingEpisodes = current.continueWatchingEpisodes.mapNotNull { cached ->
                episodesById[cached.id]?.takeIf { it.watchHistory != null }?.toCached()
            } + episodes.filter { it.watchHistory != null && current.continueWatchingEpisodes.none { cached -> cached.id == it.id } }
                .map { it.toCached() }
        )

        write(context, provider, newData)
    }

    // -------------------------
    // MOVIES
    // -------------------------

    suspend fun removeMovieFromContinueWatching(context: Context, provider: Provider, id: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val current = read(context, provider) ?: return@withContext

        runCatching {
            val db = AppDatabase.getInstance(context)
            db.movieDao().getById(id)?.let { movie ->
                movie.watchHistory = null
                movie.isWatched = false
                movie.watchedDate = null
                db.movieDao().update(movie)
            }
        }

        write(context, provider, current.copy(
            continueWatchingMovies = current.continueWatchingMovies.filter { it.id != id }
        ))
        WatchNextUtils.deleteProgramByContentId(context, id)
    }

    suspend fun addMovieToContinueWatching(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            continueWatchingMovies = (current.continueWatchingMovies + movie.toCached())
                .distinctBy { it.id }
        ))
    }

    suspend fun removeMovieFromFavorites(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: return

        write(context, provider, current.copy(
            favoritesMovies = current.favoritesMovies.filter { it.id != id }
        ))
    }

    suspend fun addMovieToFavorites(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()
        val favoritedMovie = movie.copy().apply {
            isFavorite = true
            favoritedAtMillis = favoritedAtMillis ?: System.currentTimeMillis()
        }

        write(context, provider, current.copy(
            favoritesMovies = (current.favoritesMovies + favoritedMovie.toCached())
                .distinctBy { it.id }
        ))
    }

    // -------------------------
    // EPISODES
    // -------------------------

    suspend fun removeEpisodeFromContinueWatching(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: return

        write(context, provider, current.copy(
            continueWatchingEpisodes = current.continueWatchingEpisodes.filter { it.id != id }
        ))
        WatchNextUtils.deleteProgramByContentId(context, id)
    }

    suspend fun addEpisodeToContinueWatching(context: Context, provider: Provider, episode: Episode) {
        val current = read(context, provider) ?: UserData()

        write(context, provider, current.copy(
            continueWatchingEpisodes = (current.continueWatchingEpisodes + episode.toCached())
                .distinctBy { it.id }
        ))
    }

    // -------------------------
    // TV SHOWS
    // -------------------------

    suspend fun removeTvShowFromFavorites(context: Context, provider: Provider, id: String) {
        val current = read(context, provider) ?: return

        write(context, provider, current.copy(
            favoritesTvShows = current.favoritesTvShows.filter { it.id != id }
        ))
    }

    suspend fun addTvShowToFavorites(context: Context, provider: Provider, tvShow: TvShow) {
        val current = read(context, provider) ?: UserData()
        val favoritedTvShow = tvShow.copy().apply {
            isFavorite = true
            favoritedAtMillis = favoritedAtMillis ?: System.currentTimeMillis()
        }

        write(context, provider, current.copy(
            favoritesTvShows = (current.favoritesTvShows + favoritedTvShow.toCached())
                .distinctBy { it.id }
        ))
    }

    // -------------------------
    // CACHE SYNC (Keep cache & DB in sync)
    // -------------------------

    suspend fun syncMovieToCache(context: Context, provider: Provider, movie: Movie) {
        val current = read(context, provider) ?: UserData()
        
        val updatedContinueWatching = if (movie.watchHistory != null) {
            (current.continueWatchingMovies.filter { it.id != movie.id } + movie.toCached())
                .distinctBy { it.id }
        } else {
            current.continueWatchingMovies.filter { it.id != movie.id }
        }
        
        val updatedFavorites = if (movie.isFavorite) {
            (current.favoritesMovies.filter { it.id != movie.id } + movie.toCached().copy(
                favoritedAtMillis = movie.favoritedAtMillis
                    ?: current.favoritesMovies.firstOrNull { it.id == movie.id }?.favoritedAtMillis
                    ?: System.currentTimeMillis()
            ))
                .distinctBy { it.id }
        } else {
            current.favoritesMovies.filter { it.id != movie.id }
        }
        
        write(context, provider, current.copy(
            continueWatchingMovies = updatedContinueWatching,
            favoritesMovies = updatedFavorites
        ))

        if (movie.watchHistory != null && !movie.isWatched) {
            WatchNextUtils.updateWatchNext(context, movie)
        } else {
            WatchNextUtils.deleteProgramByContentId(context, movie.id)
        }
    }

    suspend fun syncEpisodeToCache(context: Context, provider: Provider, episode: Episode) {
        val current = read(context, provider) ?: UserData()
        
        val updatedContinueWatching = if (episode.watchHistory != null) {
            (current.continueWatchingEpisodes.filter { it.id != episode.id } + episode.toCached())
                .distinctBy { it.id }
        } else {
            current.continueWatchingEpisodes.filter { it.id != episode.id }
        }
        
        write(context, provider, current.copy(
            continueWatchingEpisodes = updatedContinueWatching
        ))

        if (episode.watchHistory != null && !episode.isWatched) {
            WatchNextUtils.updateWatchNext(context, episode)
        } else {
            WatchNextUtils.deleteProgramByContentId(context, episode.id)
        }
    }

    suspend fun syncTvShowToCache(context: Context, provider: Provider, tvShow: TvShow) {
        val current = read(context, provider) ?: UserData()

        val updatedFavorites = if (tvShow.isFavorite) {
            (current.favoritesTvShows.filter { it.id != tvShow.id } + tvShow.toCached().copy(
                favoritedAtMillis = tvShow.favoritedAtMillis
                    ?: current.favoritesTvShows.firstOrNull { it.id == tvShow.id }?.favoritedAtMillis
                    ?: System.currentTimeMillis()
            ))
                .distinctBy { it.id }
        } else {
            current.favoritesTvShows.filter { it.id != tvShow.id }
        }

        write(context, provider, current.copy(
            favoritesTvShows = updatedFavorites
        ))
    }

    data class CachedMovie(
        val id: String,
        val title: String,
        val overview: String? = null,
        val released: String? = null,
        val runtime: Int? = null,
        val trailer: String? = null,
        val quality: String? = null,
        val rating: Double? = null,
        val poster: String? = null,
        val banner: String? = null,
        val isFavorite: Boolean = false,
        val isWatched: Boolean = false,
        val favoritedAtMillis: Long? = null,
        val lastEngagementTimeUtcMillis: Long? = null,
        val lastPlaybackPositionMillis: Long? = null,
        val durationMillis: Long? = null,
    )

    data class CachedTvShow(
        val id: String,
        val title: String,
        val overview: String? = null,
        val released: String? = null,
        val runtime: Int? = null,
        val trailer: String? = null,
        val quality: String? = null,
        val rating: Double? = null,
        val poster: String? = null,
        val banner: String? = null,
        val isFavorite: Boolean = false,
        val favoritedAtMillis: Long? = null,
    )

    data class CachedEpisode(
        val id: String,
        val number: Int,
        val title: String? = null,
        val released: String? = null,
        val poster: String? = null,
        val overview: String? = null,
        val isWatched: Boolean = false,
        val lastEngagementTimeUtcMillis: Long? = null,
        val lastPlaybackPositionMillis: Long? = null,
        val durationMillis: Long? = null,
        val tvShowId: String? = null,
        val tvShowTitle: String? = null,
        val tvShowPoster: String? = null,
        val tvShowBanner: String? = null,
        val seasonId: String? = null,
        val seasonNumber: Int? = null,
        val seasonTitle: String? = null,
        val seasonPoster: String? = null,
    )

fun UserDataCache.CachedMovie.toMovie() = Movie(
    id = id,
    title = title,
    overview = overview,
    released = released,
    runtime = runtime,
    trailer = trailer,
    quality = quality,
    rating = rating,
    poster = poster,
    banner = banner,
).apply {
    isFavorite = this@toMovie.isFavorite
    favoritedAtMillis = this@toMovie.favoritedAtMillis
    isWatched = this@toMovie.isWatched
    if (this@toMovie.lastEngagementTimeUtcMillis != null) {
        watchHistory = WatchItem.WatchHistory(
            lastEngagementTimeUtcMillis = this@toMovie.lastEngagementTimeUtcMillis,
            lastPlaybackPositionMillis = this@toMovie.lastPlaybackPositionMillis ?: 0,
            durationMillis = this@toMovie.durationMillis ?: 0
        )
    }
}

fun UserDataCache.CachedTvShow.toTvShow() = TvShow(
    id = id,
    title = title,
    overview = overview,
    released = released,
    runtime = runtime,
    trailer = trailer,
    quality = quality,
    rating = rating,
    poster = poster,
    banner = banner,
).apply {
    isFavorite = this@toTvShow.isFavorite
    favoritedAtMillis = this@toTvShow.favoritedAtMillis
}

fun UserDataCache.CachedEpisode.toEpisode() = Episode(
    id = id,
    number = number,
    title = title,
    released = released,
    poster = poster,
    overview = overview,
).apply {
    isWatched = this@toEpisode.isWatched
    if (this@toEpisode.lastEngagementTimeUtcMillis != null) {
        watchHistory = WatchItem.WatchHistory(
            lastEngagementTimeUtcMillis = this@toEpisode.lastEngagementTimeUtcMillis,
            lastPlaybackPositionMillis = this@toEpisode.lastPlaybackPositionMillis ?: 0,
            durationMillis = this@toEpisode.durationMillis ?: 0
        )
    }
    tvShow = this@toEpisode.tvShowId?.let {
        TvShow(
            id = it,
            title = this@toEpisode.tvShowTitle.orEmpty(),
            poster = this@toEpisode.tvShowPoster,
            banner = this@toEpisode.tvShowBanner,
        )
    }
    season = this@toEpisode.seasonId?.let {
        Season(
            id = it,
            number = this@toEpisode.seasonNumber ?: 0,
            title = this@toEpisode.seasonTitle.orEmpty(),
            poster = this@toEpisode.seasonPoster,
        )
    }
}

fun Movie.toCached() = UserDataCache.CachedMovie(
    id = id,
    title = title,
    overview = overview,
    released = released?.format("yyyy-MM-dd"),
    runtime = runtime,
    trailer = trailer,
    quality = quality,
    rating = rating,
    poster = poster,
    banner = banner,
    isFavorite = isFavorite,
    isWatched = isWatched,
    favoritedAtMillis = favoritedAtMillis,
    lastEngagementTimeUtcMillis = watchHistory?.lastEngagementTimeUtcMillis,
    lastPlaybackPositionMillis = watchHistory?.lastPlaybackPositionMillis,
    durationMillis = watchHistory?.durationMillis
)

fun TvShow.toCached() = UserDataCache.CachedTvShow(
    id = id,
    title = title,
    overview = overview,
    released = released?.format("yyyy-MM-dd"),
    runtime = runtime,
    trailer = trailer,
    quality = quality,
    rating = rating,
    poster = poster,
    banner = banner,
    isFavorite = isFavorite,
    favoritedAtMillis = favoritedAtMillis,
)

fun Episode.toCached() = UserDataCache.CachedEpisode(
    id = id,
    number = number,
    title = title,
    released = released?.format("yyyy-MM-dd"),
    poster = poster,
    overview = overview,
    isWatched = isWatched,
    lastEngagementTimeUtcMillis = watchHistory?.lastEngagementTimeUtcMillis,
    lastPlaybackPositionMillis = watchHistory?.lastPlaybackPositionMillis,
    durationMillis = watchHistory?.durationMillis,

    tvShowId = tvShow?.id,
    tvShowTitle = tvShow?.title,
    tvShowPoster = tvShow?.poster,
    tvShowBanner = tvShow?.banner,

    seasonId = season?.id,
    seasonNumber = season?.number,
    seasonTitle = season?.title,
    seasonPoster = season?.poster,
)
}
