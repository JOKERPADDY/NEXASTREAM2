package com.nexastream.app.providers

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.NexastreamApp
import android.content.pm.PackageManager
import com.nexastream.app.utils.safeSubList
import com.nexastream.app.utils.TMDb3
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object NexaHomeProvider : Provider {
    private const val LOGO_URL = "https://i.ibb.co/39Ld2wbt/MAGISTV.png"

    override val baseUrl: String = ""
    override val name: String = "HOME"
    override val logo: String = LOGO_URL
    override val language: String = "en"

    private val tmdb = TmdbProvider("en")
    private val fetchSemaphore = Semaphore(6)

    private suspend inline fun <T> limited(crossinline block: suspend () -> T): T = fetchSemaphore.withPermit { block() }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val isTv = try {
            NexastreamApp.instance.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        } catch (_: Exception) { false }
        
        // 1. Core High-Priority Parallel Fetching
        val tmdbHomeDeferred = async { limited { runCatching { tmdb.getHome() }.getOrElse { emptyList() } } }
        val cdnHomeDeferred = async { limited { runCatching { CdnLiveTvProvider.getHome() }.getOrElse { emptyList() } } }
        val latestMoviesDeferred = async { limited { runCatching { tmdb.getLatestMovies() }.getOrNull() } }
        val allCinemaDeferred = async { limited { runCatching { tmdb.getAllCinema() }.getOrNull() } }
        val newSeasonDeferred = async { limited { runCatching { tmdb.getNewSeasonsAndEpisodes() }.getOrNull() } }

        // 2. Secondary Rows with bounded timeout so we never hang the UI
        val secondaryRowsDeferred = async {
            kotlinx.coroutines.withTimeoutOrNull(3500L) {
                coroutineScope {
                    val kidsContentDef = async { limited { runCatching { tmdb.getKidsContent() }.getOrNull() } }
                    val animeContentDef = async { limited { runCatching { tmdb.getAnimeContent() }.getOrNull() } }
                    val trendingTeenRomanceDef = async { limited { runCatching { tmdb.getTeenRomance(isMovie = false, name = "Teen Romance") }.getOrNull() } }
                    val animeRowsDef = async { fetchAnimeRows() }
                    val kidsRowsDef = async { fetchKidsRows() }
                    val seriesRowsDef = async { fetchSeriesMegaRows() }
                    val movieRowsDef = async { fetchMovieMegaRows() }

                    val topRatedMoviesDef = async { limited { runCatching { 
                        val results = TMDb3.Discover.movie(language = "en", sortBy = TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC, voteCount = TMDb3.Params.Range(gte = 500)).results.mapNotNull { tmdb.mapMulti(it) }
                        Category(name = "Top Rated Movies", list = results)
                    }.getOrNull() } }
                    val topRatedTvDef = async { limited { runCatching { 
                        val results = TMDb3.TvSeriesLists.topRated(language = "en").results.mapNotNull { tmdb.mapMulti(it) }
                        Category(name = "Top Rated TV Shows", list = results)
                    }.getOrNull() } }

                    val actionDef = async { limited { runCatching { tmdb.getGenre("28") }.getOrNull() } }
                    val comedyDef = if (!isTv) async { limited { runCatching { tmdb.getGenre("35") }.getOrNull() } } else null

                    SecondaryData(
                        kidsContent = kidsContentDef.await(),
                        animeContent = animeContentDef.await(),
                        trendingTeenRomance = trendingTeenRomanceDef.await(),
                        animeRows = animeRowsDef.await(),
                        kidsRows = kidsRowsDef.await(),
                        seriesRows = seriesRowsDef.await(),
                        movieRows = movieRowsDef.await(),
                        topRatedMovies = topRatedMoviesDef.await(),
                        topRatedTv = topRatedTvDef.await(),
                        actionGenre = actionDef.await(),
                        comedyGenre = comedyDef?.await()
                    )
                }
            }
        }

        // 3. Await Core Results
        val tmdbHome = tmdbHomeDeferred.await()
        val cdnHome = cdnHomeDeferred.await()
        val latestMovies = latestMoviesDeferred.await()
        val allCinema = allCinemaDeferred.await()
        val newSeasonAndEpisodes = newSeasonDeferred.await()

        val secondary = secondaryRowsDeferred.await()

        val categories = mutableListOf<Category>()

        // 4. Build Final List
        
        // FEATURED BANNER
        tmdbHome.find { it.name == Category.FEATURED }?.let { categories.add(it) }

        // LIVE CHANNELS
        cdnHome.find { it.name == "CDN Live Channels" }?.let { cat ->
            val priorityOrder = listOf(
                listOf("Sky Sport Premier", "Premier League"),
                listOf("Sky Sport Mix"),
                listOf("National Geographic"),
                listOf("Nickelodeon"),
                listOf("NBA TV"),
                listOf("Fox"),
                listOf("ESPN"),
                listOf("Disney Channel", "Disney")
            )

            fun getPriorityIndex(title: String): Int {
                val index = priorityOrder.indexOfFirst { keyList ->
                    keyList.any { key -> title.contains(key, ignoreCase = true) }
                }
                return if (index != -1) index else Int.MAX_VALUE
            }

            val sortedList = cat.list.sortedWith(compareBy<AppAdapter.Item> { item ->
                val title = (item as? TvShow)?.title ?: ""
                getPriorityIndex(title)
            })

            categories.add(cat.copy(name = "Livestream", list = sortedList))
        }

        // TRENDING / RECOMMENDED
        tmdbHome.find { it.name == "Trending" || it.name == "Di tendenza" || it.name == "Tendencias" }?.let { 
            categories.add(it.copy(name = "Trending Today")) 
        }

        secondary?.trendingTeenRomance?.let { categories.add(it) }
        secondary?.topRatedMovies?.let { categories.add(it) }
        secondary?.topRatedTv?.let { categories.add(it) }

        tmdbHome.find { it.name == "Popular Movies" || it.name == "Film popolari" || it.name == "Películas populares" }?.let {
            categories.add(it)
        }
        
        tmdbHome.find { it.name == "Popular TV Shows" || it.name == "Serie TV popolari" || it.name == "Series de TV populares" }?.let {
            categories.add(it.copy(name = "Trending Series"))
        }

        // LATEST CONTENT
        latestMovies?.let { categories.add(it) }
        allCinema?.let { categories.add(it) }
        newSeasonAndEpisodes?.let { categories.add(it) }

        // NETWORK ROWS
        tmdbHome.filter { it.name.startsWith("Popular on") || it.name.startsWith("Popolari su") || it.name.startsWith("Popular en") }.forEach {
            categories.add(it)
        }

        // SECONDARY ROWS
        secondary?.seriesRows?.let { categories.addAll(it.filterNotNull()) }
        secondary?.movieRows?.let { categories.addAll(it.filterNotNull()) }

        secondary?.kidsContent?.let { categories.add(it.copy(name = "Kids Banner", list = it.list.safeSubList(0, 5))) }
        secondary?.animeContent?.let { categories.add(it.copy(name = "Anime Banner", list = it.list.safeSubList(0, 4))) }

        secondary?.animeRows?.let { categories.addAll(it.filterNotNull()) }
        secondary?.kidsRows?.let { categories.addAll(it.filterNotNull()) }

        secondary?.actionGenre?.shows?.takeIf { it.isNotEmpty() }?.let {
            categories.add(Category(name = "Action & Adventure", list = it))
        }
        secondary?.comedyGenre?.shows?.takeIf { it.isNotEmpty() }?.let {
            categories.add(Category(name = "Comedy", list = it))
        }

        // Ensure aggregate rows are at the end
        secondary?.animeRows?.let { rows ->
            if (categories.none { it.name == "Anime" }) {
                categories.add(Category(name = "Anime", list = rows.filterNotNull().flatMap { it.list }.distinct().safeSubList(0, 20)))
            }
        }
        secondary?.kidsRows?.let { rows ->
            if (categories.none { it.name == "Kids" }) {
                categories.add(Category(name = "Kids", list = rows.filterNotNull().flatMap { it.list }.distinct().safeSubList(0, 20)))
            }
        }

        categories
    }

    private data class SecondaryData(
        val kidsContent: Category?,
        val animeContent: Category?,
        val trendingTeenRomance: Category?,
        val animeRows: List<Category?>,
        val kidsRows: List<Category?>,
        val seriesRows: List<Category?>,
        val movieRows: List<Category?>,
        val topRatedMovies: Category?,
        val topRatedTv: Category?,
        val actionGenre: Genre?,
        val comedyGenre: Genre?
    )

    private suspend fun fetchAnimeRows(): List<Category?> = coroutineScope {
        listOf(
            async { limited { runCatching { tmdb.getAnimeMovies() }.getOrNull() } },
            async { limited { runCatching { tmdb.getJapaneseAnime() }.getOrNull() } },
            async { limited { runCatching { tmdb.getWesternAnime() }.getOrNull() } },
            async { limited { runCatching { tmdb.getAnimeAge7to12() }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("Dragon Ball", "Dragon Ball") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("Naruto", "Naruto") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("One Piece", "One Piece") }.getOrNull() } }
        ).awaitAll()
    }

    private suspend fun fetchKidsRows(): List<Category?> = coroutineScope {
        listOf(
            async { limited { runCatching { tmdb.getCartoonMovies() }.getOrNull() } },
            async { limited { runCatching { tmdb.getCartoonSeries() }.getOrNull() } },
            async { limited { runCatching { tmdb.getKeywordContent("Baby", 10229) }.getOrNull() } },
            async { limited { runCatching { tmdb.getKidsContent().copy(name = "Age 2-6") }.getOrNull() } },
            async { limited { runCatching { tmdb.getStudioContent("Pixar", 3) }.getOrNull() } },
            async { limited { runCatching { tmdb.getStudioContent("DreamWorks", 521) }.getOrNull() } },
            async { limited { runCatching { tmdb.getStudioContent("Blue Sky Studios", 10378) }.getOrNull() } },
            async { limited { runCatching { tmdb.getStudioContent("Illumination", 6704) }.getOrNull() } },
            async { limited { runCatching { tmdb.getKeywordContent("Toys", 11134) }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("Kung Fu Panda", "Kung Fu Panda") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("Cars", "Cars") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("Frozen", "Frozen") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("Minions", "Minions") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("Peppa Pig", "Peppa Pig") }.getOrNull() } }
        ).awaitAll()
    }

    private suspend fun fetchSeriesMegaRows(): List<Category?> = coroutineScope {
        listOf(
            async { limited { runCatching { 
                val results = TMDb3.TvSeriesLists.topRated(language = "en").results.mapNotNull { tmdb.mapMulti(it) }
                Category(name = "Top Rated TV Shows", list = results)
            }.getOrNull() } },
            async { limited { runCatching { 
                val results = TMDb3.TvSeriesLists.popular(language = "en").results.mapNotNull { tmdb.mapMulti(it) }
                Category(name = "Trending Series", list = results)
            }.getOrNull() } },
            async { limited { runCatching { tmdb.getNewSeasonsAndEpisodes() }.getOrNull() } },
            
            // Networks
            async { limited { runCatching { tmdb.getNetworkTv(213, "Netflix Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getNetworkTv(1024, "Prime Video Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getNetworkTv(2739, "Disney+ Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getNetworkTv(49, "Max Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getNetworkTv(4330, "Paramount+ Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getNetworkTv(2552, "Apple TV Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getNetworkTv(453, "Hulu Series") }.getOrNull() } },
            
            // Genres
            async { limited { runCatching { tmdb.getGenreTv(10759, "Action & Adventure Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(35, "Comedy Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(80, "Crime Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getTeenRomance(isMovie = false, name = "Teen Romance Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(99, "Documentary Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(18, "Drama Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(10751, "Family Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(10765, "Sci-Fi & Fantasy Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(27, "Horror Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(9648, "Mystery Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(10749, "Romance Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(53, "Thriller Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(10768, "War & Politics Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getBiography(false, "Biography Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(10764, "Reality TV") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSport(false, "Sport Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreTv(37, "Western Series") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSearchContent("Musical Series", "Musical") }.getOrNull() } },
            async { limited { runCatching { Category(name = "All TV Shows", list = tmdb.getTvShows(1)) }.getOrNull() } }
        ).awaitAll()
    }

    private suspend fun fetchMovieMegaRows(): List<Category?> = coroutineScope {
        listOf(
            async { limited { runCatching { tmdb.getLatestMovies() }.getOrNull() } },
            async { limited { runCatching { tmdb.getAllCinema() }.getOrNull() } },
            async { limited { runCatching { tmdb.getWatchProviderMovies(8, "Netflix Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getWatchProviderMovies(337, "Disney+ Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getWatchProviderMovies(531, "Paramount+ Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(28, "Action Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(80, "Crime Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(18, "Drama Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(12, "Adventure Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(35, "Comedy Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(53, "Thriller Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(10749, "Romance Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(878, "Sci-Fi Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(99, "Documentary Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(27, "Horror Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(14, "Fantasy Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(10751, "Family Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(36, "History Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(9648, "Mystery Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(10752, "War Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getBiography(true, "Biography Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getSport(true, "Sport Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getGenreMovies(37, "Western Movies") }.getOrNull() } },
            async { limited { runCatching { tmdb.getTeenRomance(isMovie = true, name = "Teen Romance Movies") }.getOrNull() } },
            async { limited { runCatching { Category(name = "All Movies", list = tmdb.getMovies(1)) }.getOrNull() } }
        ).awaitAll()
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id.startsWith("cdn:")) return CdnLiveTvProvider.getTvShow(id)
        return tmdb.getTvShow(id)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id.startsWith("cdn:")) return CdnLiveTvProvider.getServers(id, videoType)
        return tmdb.getServers(id, videoType)
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        if (server.name.contains("CDN") || server.id.contains("cdnlivetv.tv")) return@withContext CdnLiveTvProvider.getVideo(server)
        tmdb.getVideo(server)
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> = tmdb.search(query, page, filters)
    override suspend fun getMovies(page: Int): List<Movie> = tmdb.getMovies(page)
    override suspend fun getTvShows(page: Int): List<TvShow> = tmdb.getTvShows(page)
    override suspend fun getMovie(id: String): Movie = tmdb.getMovie(id)
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = tmdb.getEpisodesBySeason(seasonId)
    override suspend fun getGenre(id: String, page: Int): Genre = when {
        id == "cdn_all_channels" -> CdnLiveTvProvider.getGenre(id, page)
        id == "cdn_sports" -> CdnLiveTvProvider.getGenre(id, page)
        id.startsWith("tmdb_movies_genre_") -> {
            val cat = tmdb.getGenreMovies(id.substringAfter("tmdb_movies_genre_").toInt(), "")
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id.startsWith("tmdb_tv_genre_") -> {
            val cat = tmdb.getGenreTv(id.substringAfter("tmdb_tv_genre_").toInt(), "")
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id.startsWith("tmdb_network_tv_") -> {
            val cat = tmdb.getNetworkTv(id.substringAfter("tmdb_network_tv_").toInt(), "")
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id.startsWith("tmdb_watch_provider_movies_") -> {
            val cat = tmdb.getWatchProviderMovies(id.substringAfter("tmdb_watch_provider_movies_").toInt(), "")
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id.startsWith("tmdb_studio_") -> {
            val cat = tmdb.getStudioContent("", id.substringAfter("tmdb_studio_").toInt())
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id.startsWith("tmdb_keyword_") -> {
            val cat = tmdb.getKeywordContent("", id.substringAfter("tmdb_keyword_").toInt())
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id.startsWith("search_") -> {
            val query = id.substringAfter("search_")
            val cat = tmdb.getSearchContent(query, query)
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id == "latest_movies" -> {
            if (page > 1) Genre(id = id, name = "Latest Movies", shows = emptyList())
            else {
                val cat = tmdb.getLatestMovies()
                Genre(id = id, name = cat.name, shows = cat.list)
            }
        }
        id == "all_cinema" -> {
            if (page > 1) Genre(id = id, name = "All Cinema", shows = emptyList())
            else {
                val cat = tmdb.getAllCinema()
                Genre(id = id, name = cat.name, shows = cat.list)
            }
        }
        id == "new_season_tv" -> {
            if (page > 1) Genre(id = id, name = "New Season and Episode", shows = emptyList())
            else {
                val cat = tmdb.getNewSeasonsAndEpisodes()
                Genre(id = id, name = cat.name, shows = cat.list)
            }
        }
        id == "teen_romance_movies" -> {
            val cat = tmdb.getTeenRomance(isMovie = true, page = page, name = "Teen Romance")
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id == "teen_romance_series" -> {
            val cat = tmdb.getTeenRomance(isMovie = false, page = page, name = "Teen Romance")
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id == "biography_movies" -> {
            if (page > 1) Genre(id = id, name = "Biography", shows = emptyList())
            else {
                val cat = tmdb.getBiography(isMovie = true, name = "Biography")
                Genre(id = id, name = cat.name, shows = cat.list)
            }
        }
        id == "biography_series" -> {
            if (page > 1) Genre(id = id, name = "Biography", shows = emptyList())
            else {
                val cat = tmdb.getBiography(isMovie = false, name = "Biography")
                Genre(id = id, name = cat.name, shows = cat.list)
            }
        }
        id == "sport_movies" -> {
            if (page > 1) Genre(id = id, name = "Sport", shows = emptyList())
            else {
                val cat = tmdb.getSport(isMovie = true, name = "Sport")
                Genre(id = id, name = cat.name, shows = cat.list)
            }
        }
        id == "sport_series" -> {
            if (page > 1) Genre(id = id, name = "Sport", shows = emptyList())
            else {
                val cat = tmdb.getSport(isMovie = false, name = "Sport")
                Genre(id = id, name = cat.name, shows = cat.list)
            }
        }
        id == "tmdb_movies_popular" -> {
            val results = TMDb3.MovieLists.popular(page = page, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            Genre(id = id, name = "Popular Movies", shows = results)
        }
        id == "tmdb_tv_top_rated" -> {
            val results = TMDb3.TvSeriesLists.topRated(page = page, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            Genre(id = id, name = "Top Rated TV Shows", shows = results)
        }
        id == "tmdb_tv_popular" -> {
            val results = TMDb3.TvSeriesLists.popular(page = page, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            Genre(id = id, name = "Popular TV Shows", shows = results)
        }
        id == "tmdb_kids_family" -> tmdb.getKidsContent().let { Genre(id = id, name = it.name, shows = it.list) }
        id == "tmdb_anime_universe" -> tmdb.getAnimeContent().let { Genre(id = id, name = it.name, shows = it.list) }
        id == "tmdb_cartoon_movies" -> tmdb.getCartoonMovies().let { Genre(id = id, name = it.name, shows = it.list) }
        id == "tmdb_cartoon_series" -> tmdb.getCartoonSeries().let { Genre(id = id, name = it.name, shows = it.list) }
        id == "tmdb_japanese_anime" -> tmdb.getJapaneseAnime().let { Genre(id = id, name = it.name, shows = it.list) }
        id == "tmdb_western_anime" -> tmdb.getWesternAnime().let { Genre(id = id, name = it.name, shows = it.list) }
        id == "tmdb_anime_age_7_12" -> tmdb.getAnimeAge7to12().let { Genre(id = id, name = it.name, shows = it.list) }
        id.startsWith("cdn_") -> CdnLiveTvProvider.getGenre(id, page)
        else -> tmdb.getGenre(id, page)
    }
    override suspend fun getPeople(id: String, page: Int): People = tmdb.getPeople(id, page)
}
