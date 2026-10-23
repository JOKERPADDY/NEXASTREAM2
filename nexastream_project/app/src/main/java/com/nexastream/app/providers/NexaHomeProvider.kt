package com.nexastream.app.providers

import android.util.Log
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
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object NexaHomeProvider : Provider {
    private const val PHASE0_TIMEOUT_MOBILE_MS = 2_500L
    private const val PHASE0_TIMEOUT_TV_MS = 12_000L
    private const val IPTV_FALLBACK_TIMEOUT_MOBILE_MS = 2_000L
    private const val IPTV_FALLBACK_TIMEOUT_TV_MS = 10_000L

    private const val LOGO_URL = "https://i.ibb.co/39Ld2wbt/MAGISTV.png"

    override val baseUrl: String = ""
    override val name: String = "HOME"
    override val logo: String = LOGO_URL
    override val language: String = "en"

    private val tmdb = TmdbProvider("en")
    private val fetchSemaphore = Semaphore(8)

    private suspend inline fun <T> limited(crossinline block: suspend () -> T): T = fetchSemaphore.withPermit { block() }

    private fun isTvUi(): Boolean =
        UserPreferences.forceTvUi || runCatching {
            NexastreamApp.instance.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }.getOrDefault(false)

    override suspend fun getHome(): List<Category> = coroutineScope {
        val phase0 = getHomePhase0()
        val phase1 = getHomePhase1(phase0)
        getHomePhase2(phase1)
    }

    suspend fun getHomeProgressive(
        onPhase0: suspend (List<Category>) -> Unit,
        onPhase1: suspend (List<Category>) -> Unit
    ): List<Category> = coroutineScope {
        val phase0 = getHomePhase0()
        onPhase0(phase0)
        val phase1 = getHomePhase1(phase0)
        onPhase1(phase1)
        val full = getHomePhase2(phase1)
        full
    }

    suspend fun getHomeProgressive(
        onPhase1: suspend (List<Category>) -> Unit
    ): List<Category> = getHomeProgressive(onPhase0 = onPhase1, onPhase1 = onPhase1)

    suspend fun getHomePhase0(): List<Category> = coroutineScope {
        val isTv = isTvUi()
        val phase0Timeout = if (isTv) PHASE0_TIMEOUT_TV_MS else PHASE0_TIMEOUT_MOBILE_MS
        val iptvFallbackTimeout = if (isTv) IPTV_FALLBACK_TIMEOUT_TV_MS else IPTV_FALLBACK_TIMEOUT_MOBILE_MS

        // Phase 0: Fast Hero Banner + CDN Live Channels (~1s)
        val cdnHomeDeferred = async { limited {
            val cdnList = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(phase0Timeout) {
                    CdnLiveTvProvider.getHome()
                }
            }.getOrNull().orEmpty()
            if (cdnList.isNotEmpty() && cdnList.any { it.list.isNotEmpty() }) {
                cdnList
            } else {
                Log.w("NexaHomeProvider", "CDN Live TV empty or failed, falling back to IPTV All World")
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(iptvFallbackTimeout) {
                        IptvOrgProvider.getHome()
                    }
                }.getOrElse { emptyList() } ?: emptyList()
            }
        } }

        val moviesBannerDef = async { limited {
            runCatching { kotlinx.coroutines.withTimeoutOrNull(phase0Timeout) { tmdb.getFeaturedMovies() } }.getOrNull()
        } }
        val seriesBannerDef = async { limited {
            runCatching { kotlinx.coroutines.withTimeoutOrNull(phase0Timeout) { tmdb.getFeaturedTvShows() } }.getOrNull()
        } }
        val liveSportsMatchesDef = async { limited {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(2500) {
                    AkSportsLiveProvider.getLiveMatches()
                }
            }.getOrNull().orEmpty()
        } }
        val upcomingSportsMatchesDef = async { limited {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(2500) {
                    AkSportsLiveProvider.getUpcomingMatches()
                }
            }.getOrNull().orEmpty()
        } }

        val cdnHome: List<Category> = cdnHomeDeferred.await() ?: emptyList()
        val moviesBanner = moviesBannerDef.await()
        val seriesBanner = seriesBannerDef.await()
        val liveSportsMatches = liveSportsMatchesDef.await()
        val upcomingSportsMatches = upcomingSportsMatchesDef.await()
        AkSportsLiveProvider.prefetchResolverStreams((liveSportsMatches + upcomingSportsMatches).take(8))

        val categories = mutableListOf<Category>()

        moviesBanner?.let { categories.add(it.copy(name = "Movies Banner", list = it.list.safeSubList(0, 10))) }
        seriesBanner?.let { categories.add(it.copy(name = "Series Banner", list = it.list.safeSubList(0, 10))) }

        if (liveSportsMatches.isNotEmpty()) {
            categories.add(
                Category(
                    name = "Live Sports",
                    list = liveSportsMatches
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
        }

        // LIVE CHANNELS
        var livestreamCategory: Category? = null
        cdnHome.find { it.name == "Livestream" || it.name == "CDN Live Channels" || it.name == "Live Sports & TV" }?.let { cat ->
            val priorityOrder = listOf(
                listOf("Sky Sports Premier", "Sky Sport Premier", "Sky Premier", "Premier League"),
                listOf("Nickelodeon", "Nick"),
                listOf("National Geographic", "Nat Geo", "National Graphic"),
                listOf("Sky Sport Mix", "Sky Sports"),
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

            val filteredList = cat.list.filter { item ->
                val title = (item as? TvShow)?.title ?: ""
                !(title.contains("Canal Premier", ignoreCase = true) || title.contains("Canal Premier League", ignoreCase = true))
            }

            val sortedList = filteredList.sortedWith(compareBy<AppAdapter.Item> { item ->
                val title = (item as? TvShow)?.title ?: ""
                getPriorityIndex(title)
            })

            livestreamCategory = cat.copy(name = "Livestream", list = sortedList)
        } ?: run {
            val liveCats = cdnHome.filter { it.name.contains("Sports", ignoreCase = true) || it.name.contains("News", ignoreCase = true) || it.name.contains("Entertainment", ignoreCase = true) }.take(3)
            if (liveCats.isNotEmpty()) {
                livestreamCategory = Category(name = "Livestream", list = liveCats.flatMap { it.list })
            }
        }

        livestreamCategory?.let { categories.add(it) }

        if (upcomingSportsMatches.isNotEmpty()) {
            categories.add(
                Category(
                    name = "Upcoming Matches",
                    list = upcomingSportsMatches
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
        }

        categories
    }

    suspend fun getHomePhase1(phase0Categories: List<Category>): List<Category> = coroutineScope {
        val timeout = if (isTvUi()) PHASE0_TIMEOUT_TV_MS else PHASE0_TIMEOUT_MOBILE_MS
        // Phase 1: Core Content (Trending, Popular, Cinema, New Seasons)
        val movieBoxHomeDeferred = async { limited {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(timeout) { MovieBoxProvider.getHome() }
            }.getOrDefault(emptyList())
        } }

        val tmdbHomeDeferred = async { limited { runCatching { kotlinx.coroutines.withTimeoutOrNull(timeout) { tmdb.getHome() } }.getOrElse { emptyList() } ?: emptyList() } }
        val latestMoviesDeferred = async { limited { runCatching { kotlinx.coroutines.withTimeoutOrNull(timeout) { tmdb.getLatestMovies() } }.getOrNull() } }
        val allCinemaDeferred = async { limited { runCatching { kotlinx.coroutines.withTimeoutOrNull(timeout) { tmdb.getAllCinema() } }.getOrNull() } }
        val newSeasonDeferred = async { limited { runCatching { kotlinx.coroutines.withTimeoutOrNull(timeout) { tmdb.getNewSeasonsAndEpisodes() } }.getOrNull() } }

        val tmdbHome = tmdbHomeDeferred.await() ?: emptyList()
        val latestMovies = latestMoviesDeferred.await()
        val allCinema = allCinemaDeferred.await()
        val newSeasonAndEpisodes = newSeasonDeferred.await()

        val categories = mutableListOf<Category>()
        categories.addAll(phase0Categories)

        tmdbHome.find { it.name == Category.FEATURED }?.let {
            if (categories.none { cat -> cat.name == Category.FEATURED }) {
                categories.add(0, it)
            }
        }

        tmdbHome.find { it.name == "Trending" || it.name == "Di tendenza" || it.name == "Tendencias" }?.let { 
            categories.add(it.copy(name = "Trending Today")) 
        }

        tmdbHome.find { it.name == "Popular Movies" || it.name == "Film popolari" || it.name == "Películas populares" }?.let {
            categories.add(it)
        }

        tmdbHome.find { it.name == "Popular TV Shows" || it.name == "Serie TV popolari" || it.name == "Series de TV populares" }?.let {
            categories.add(it.copy(name = "Trending Series"))
        }

        latestMovies?.let { categories.add(it) }
        allCinema?.let { categories.add(it) }
        newSeasonAndEpisodes?.let { categories.add(it) }

        // DYNAMIC DEDICATED ROWS (Phase 1)
        val becauseYouWatchedDeferred = async { limited { runCatching { fetchBecauseYouWatchedRow() }.getOrNull() } }
        val recommendedForYouDeferred = async { limited { runCatching { fetchRecommendedForYouRow() }.getOrNull() } }
        val timeOfDayVibeDeferred = async { limited { runCatching { fetchTimeOfDayVibeRow() }.getOrNull() } }
        val upcomingReleasesDeferred = async { limited { runCatching { fetchUpcomingReleasesRow() }.getOrNull() } }

        becauseYouWatchedDeferred.await()?.let { categories.add(it) }
        recommendedForYouDeferred.await()?.let { categories.add(it) }
        timeOfDayVibeDeferred.await()?.let { categories.add(it) }
        upcomingReleasesDeferred.await()?.let { categories.add(it) }

        // NETWORK ROWS
        tmdbHome.filter { it.name.startsWith("Popular on") || it.name.startsWith("Popolari su") || it.name.startsWith("Popular en") }.forEach {
            categories.add(it)
        }

        val db = runCatching {
            com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance)
        }.getOrNull()
        val profile = com.nexastream.app.utils.RecommendationEngine.buildUserInterestProfile(db)
        com.nexastream.app.utils.RecommendationEngine.rankCategories(categories, profile)
    }

    private suspend fun fetchMovieBoxCategory(
        name: String,
        query: String,
        isMovie: Boolean? = null,
        tmdbFallback: suspend () -> Category?
    ): Category = coroutineScope {
        val tmdbCat = runCatching { tmdbFallback() }.getOrNull()
        tmdbCat ?: Category(name = name, list = emptyList())
    }

    suspend fun getHomePhase2(phase1Categories: List<Category>): List<Category> = coroutineScope {
        val isTv = try {
            NexastreamApp.instance.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        } catch (_: Exception) { false }

        // Secondary Rows Parallel Fetching (Phase 2)
        val kidsContentDef = async { limited {
            val mbKids = runCatching { MovieBoxProvider.getCategory("kids", 1) }.getOrNull().orEmpty()
            if (mbKids.isNotEmpty()) {
                Category(name = "Kids Section", list = mbKids)
            } else {
                runCatching { tmdb.getKidsContent() }.getOrNull()
            }
        } }

        val animeContentDef = async { limited {
            val mbAnime = runCatching { MovieBoxProvider.getCategory("anime", 1) }.getOrNull().orEmpty()
            if (mbAnime.isNotEmpty()) {
                Category(name = "Anime Section", list = mbAnime)
            } else {
                runCatching { tmdb.getAnimeContent() }.getOrNull()
            }
        } }

        val trendingTeenRomanceDef = async { limited { runCatching { tmdb.getTeenRomance(isMovie = false, name = "Teen Romance") }.getOrNull() } }
        val animeRowsDef = async { fetchAnimeRows() }
        val kidsRowsDef = async { fetchKidsRows() }
        val seriesRowsDef = async { fetchSeriesMegaRows() }
        val movieRowsDef = async { fetchMovieMegaRows() }

        val topRatedMoviesDef = async { limited { fetchMovieBoxCategory("Top Rated Movies", "Top Rated Movies", true) {
            val results = TMDb3.Discover.movie(language = "en", sortBy = TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC, voteCount = TMDb3.Params.Range(gte = 500)).results.mapNotNull { tmdb.mapMulti(it) }
            Category(name = "Top Rated Movies", list = results)
        } } }

        val topRatedTvDef = async { limited { fetchMovieBoxCategory("Top Rated TV Shows", "Top Rated TV Shows", false) {
            val results = TMDb3.TvSeriesLists.topRated(language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            Category(name = "Top Rated TV Shows", list = results)
        } } }

        val actionDef = async { limited { fetchMovieBoxCategory("Action & Adventure", "Action", null) {
            val cat = tmdb.getGenre("28")
            Category(name = "Action & Adventure", list = cat.shows)
        } } }

        val comedyDef = if (!isTv) async { limited { fetchMovieBoxCategory("Comedy", "Comedy", null) {
            val cat = tmdb.getGenre("35")
            Category(name = "Comedy", list = cat.shows)
        } } } else null

        val cinematicUniversesDef = async { limited { runCatching { fetchCinematicUniversesRows() }.getOrNull().orEmpty() } }
        val watchAgainDef = async { limited { runCatching { fetchWatchAgainRow() }.getOrNull() } }
        val wweRowDef = async { limited { runCatching { fetchWweMovieBoxRow() }.getOrNull() } }
        val nollywoodRowDef = async { limited { runCatching { fetchNollywoodMovieBoxRow() }.getOrNull() } }
        val kenyanRowDef = async { limited { runCatching { fetchKenyanSeriesMovieBoxRow() }.getOrNull() } }

        val kidsContent = kidsContentDef.await()
        val animeContent = animeContentDef.await()
        val trendingTeenRomance = trendingTeenRomanceDef.await()
        val animeRows = animeRowsDef.await()
        val kidsRows = kidsRowsDef.await()
        val seriesRows = seriesRowsDef.await()
        val movieRows = movieRowsDef.await()
        val topRatedMovies = topRatedMoviesDef.await()
        val topRatedTv = topRatedTvDef.await()
        val actionCategory = actionDef.await()
        val comedyCategory = comedyDef?.await()
        val cinematicUniverses = cinematicUniversesDef.await()
        val watchAgain = watchAgainDef.await()
        val wweRow = wweRowDef.await()
        val nollywoodRow = nollywoodRowDef.await()
        val kenyanRow = kenyanRowDef.await()

        val categories = mutableListOf<Category>()
        categories.addAll(phase1Categories)

        watchAgain?.let { categories.add(it) }
        cinematicUniverses.forEach { categories.add(it) }
        wweRow?.let { categories.add(it) }
        nollywoodRow?.let { categories.add(it) }
        kenyanRow?.let { categories.add(it) }

        kidsContent?.let { 
            if (categories.none { cat -> cat.name == "Kids Banner" }) {
                categories.add(it.copy(name = "Kids Banner", list = it.list.safeSubList(0, 10))) 
            }
        }
        animeContent?.let { 
            if (categories.none { cat -> cat.name == "Anime Banner" }) {
                categories.add(it.copy(name = "Anime Banner", list = it.list.safeSubList(0, 10))) 
            }
        }

        trendingTeenRomance?.let { categories.add(it) }
        topRatedMovies.let { categories.add(it) }
        topRatedTv.let { categories.add(it) }

        seriesRows.let { categories.addAll(it.filterNotNull()) }
        movieRows.let { categories.addAll(it.filterNotNull()) }

        kidsContent?.let { categories.add(it.copy(name = "Kids Section", list = it.list.safeSubList(0, 5))) }
        animeContent?.let { categories.add(it.copy(name = "Anime Section", list = it.list.safeSubList(0, 4))) }

        animeRows.let { categories.addAll(it.filterNotNull()) }
        kidsRows.let { categories.addAll(it.filterNotNull()) }

        actionCategory.let { cat ->
            if (cat.list.isNotEmpty()) categories.add(cat)
        }
        comedyCategory?.let { cat ->
            if (cat.list.isNotEmpty()) categories.add(cat)
        }

        animeRows.let { rows ->
            if (categories.none { it.name == "Anime" }) {
                categories.add(Category(name = "Anime", list = rows.filterNotNull().flatMap { it.list }.distinct().safeSubList(0, 20)))
            }
        }
        kidsRows.let { rows ->
            if (categories.none { it.name == "Kids" }) {
                categories.add(Category(name = "Kids", list = rows.filterNotNull().flatMap { it.list }.distinct().safeSubList(0, 20)))
            }
        }

        val db = runCatching {
            com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance)
        }.getOrNull()
        val profile = com.nexastream.app.utils.RecommendationEngine.buildUserInterestProfile(db)
        com.nexastream.app.utils.RecommendationEngine.rankCategories(categories, profile)
    }

    private suspend fun fetchAnimeRows(): List<Category?> = coroutineScope {
        listOf(
            async { limited { fetchMovieBoxCategory("Anime Movies", "Anime Movie", true) { tmdb.getAnimeMovies() } } },
            async { limited { fetchMovieBoxCategory("Japanese Anime", "Japanese Anime", null) { tmdb.getJapaneseAnime() } } },
            async { limited { fetchMovieBoxCategory("Western Anime", "Animation", null) { tmdb.getWesternAnime() } } },
            async { limited { fetchMovieBoxCategory("Dragon Ball", "Dragon Ball", null) { tmdb.getSearchContent("Dragon Ball", "Dragon Ball") } } },
            async { limited { fetchMovieBoxCategory("Naruto", "Naruto", null) { tmdb.getSearchContent("Naruto", "Naruto") } } },
            async { limited { fetchMovieBoxCategory("One Piece", "One Piece", null) { tmdb.getSearchContent("One Piece", "One Piece") } } }
        ).awaitAll()
    }

    private suspend fun fetchKidsRows(): List<Category?> = coroutineScope {
        listOf(
            async { limited { fetchMovieBoxCategory("Cartoon Movies", "Cartoon Movie", true) { tmdb.getCartoonMovies() } } },
            async { limited { fetchMovieBoxCategory("Cartoon Series", "Cartoon Series", false) { tmdb.getCartoonSeries() } } },
            async { limited { fetchMovieBoxCategory("Pixar Studio", "Pixar", true) { tmdb.getStudioContent("Pixar", 3) } } },
            async { limited { fetchMovieBoxCategory("DreamWorks Studio", "DreamWorks", true) { tmdb.getStudioContent("DreamWorks", 521) } } },
            async { limited { fetchMovieBoxCategory("Illumination Studio", "Illumination", true) { tmdb.getStudioContent("Illumination", 6704) } } },
            async { limited { fetchMovieBoxCategory("Kung Fu Panda", "Kung Fu Panda", null) { tmdb.getSearchContent("Kung Fu Panda", "Kung Fu Panda") } } },
            async { limited { fetchMovieBoxCategory("Cars", "Cars", null) { tmdb.getSearchContent("Cars", "Cars") } } },
            async { limited { fetchMovieBoxCategory("Frozen", "Frozen", null) { tmdb.getSearchContent("Frozen", "Frozen") } } },
            async { limited { fetchMovieBoxCategory("Minions", "Minions", null) { tmdb.getSearchContent("Minions", "Minions") } } }
        ).awaitAll()
    }

    private suspend fun fetchSeriesMegaRows(): List<Category?> = coroutineScope {
        listOf(
            async { limited { fetchMovieBoxCategory("Top Rated TV Shows", "Top Rated", false) { 
                val results = TMDb3.TvSeriesLists.topRated(language = "en").results.mapNotNull { tmdb.mapMulti(it) }
                Category(name = "Top Rated TV Shows", list = results)
            } } },
            async { limited { fetchMovieBoxCategory("Trending Series", "Popular Series", false) { 
                val results = TMDb3.TvSeriesLists.popular(language = "en").results.mapNotNull { tmdb.mapMulti(it) }
                Category(name = "Trending Series", list = results)
            } } },
            async { limited { runCatching { tmdb.getNewSeasonsAndEpisodes() }.getOrNull() } },
            
            // Networks
            async { limited { fetchMovieBoxCategory("Netflix Series", "Netflix", false) { tmdb.getNetworkTv(213, "Netflix Series") } } },
            async { limited { fetchMovieBoxCategory("Prime Video Series", "Prime Video", false) { tmdb.getNetworkTv(1024, "Prime Video Series") } } },
            async { limited { fetchMovieBoxCategory("Disney+ Series", "Disney+", false) { tmdb.getNetworkTv(2739, "Disney+ Series") } } },
            async { limited { fetchMovieBoxCategory("Max Series", "HBO Max", false) { tmdb.getNetworkTv(49, "Max Series") } } },
            async { limited { fetchMovieBoxCategory("Paramount+ Series", "Paramount+", false) { tmdb.getNetworkTv(4330, "Paramount+ Series") } } },
            async { limited { fetchMovieBoxCategory("Apple TV Series", "Apple TV", false) { tmdb.getNetworkTv(2552, "Apple TV Series") } } },
            async { limited { fetchMovieBoxCategory("Hulu Series", "Hulu", false) { tmdb.getNetworkTv(453, "Hulu Series") } } },
            
            // Genres
            async { limited { fetchMovieBoxCategory("Action & Adventure Series", "Action Series", false) { tmdb.getGenreTv(10759, "Action & Adventure Series") } } },
            async { limited { fetchMovieBoxCategory("Comedy Series", "Comedy Series", false) { tmdb.getGenreTv(35, "Comedy Series") } } },
            async { limited { fetchMovieBoxCategory("Crime Series", "Crime Series", false) { tmdb.getGenreTv(80, "Crime Series") } } },
            async { limited { fetchMovieBoxCategory("Teen Romance Series", "Teen Romance Series", false) { tmdb.getTeenRomance(isMovie = false, name = "Teen Romance Series") } } },
            async { limited { fetchMovieBoxCategory("Documentary Series", "Documentary Series", false) { tmdb.getGenreTv(99, "Documentary Series") } } },
            async { limited { fetchMovieBoxCategory("Drama Series", "Drama Series", false) { tmdb.getGenreTv(18, "Drama Series") } } },
            async { limited { fetchMovieBoxCategory("Family Series", "Family Series", false) { tmdb.getGenreTv(10751, "Family Series") } } },
            async { limited { fetchMovieBoxCategory("Sci-Fi & Fantasy Series", "Sci-Fi Series", false) { tmdb.getGenreTv(10765, "Sci-Fi & Fantasy Series") } } },
            async { limited { fetchMovieBoxCategory("Horror Series", "Horror Series", false) { tmdb.getGenreTv(27, "Horror Series") } } },
            async { limited { fetchMovieBoxCategory("Mystery Series", "Mystery Series", false) { tmdb.getGenreTv(9648, "Mystery Series") } } },
            async { limited { fetchMovieBoxCategory("Romance Series", "Romance Series", false) { tmdb.getGenreTv(10749, "Romance Series") } } },
            async { limited { fetchMovieBoxCategory("Thriller Series", "Thriller Series", false) { tmdb.getGenreTv(53, "Thriller Series") } } },
            async { limited { fetchMovieBoxCategory("War & Politics Series", "War Series", false) { tmdb.getGenreTv(10768, "War & Politics Series") } } },
            async { limited { fetchMovieBoxCategory("Biography Series", "Biography Series", false) { tmdb.getBiography(false, "Biography Series") } } },
            async { limited { fetchMovieBoxCategory("Reality TV", "Reality TV", false) { tmdb.getGenreTv(10764, "Reality TV") } } },
            async { limited { fetchMovieBoxCategory("Sport Series", "Sport Series", false) { tmdb.getSport(false, "Sport Series") } } },
            async { limited { fetchMovieBoxCategory("Western Series", "Western Series", false) { tmdb.getGenreTv(37, "Western Series") } } }
        ).awaitAll()
    }

    private suspend fun fetchMovieMegaRows(): List<Category?> = coroutineScope {
        listOf(
            async { limited { fetchMovieBoxCategory("Latest Movies", "Latest Movies", true) { tmdb.getLatestMovies() } } },
            async { limited { fetchMovieBoxCategory("All Cinema", "Blockbuster Movies", true) { tmdb.getAllCinema() } } },
            async { limited { fetchMovieBoxCategory("Netflix Movies", "Netflix Movies", true) { tmdb.getWatchProviderMovies(8, "Netflix Movies") } } },
            async { limited { fetchMovieBoxCategory("Disney+ Movies", "Disney Movies", true) { tmdb.getWatchProviderMovies(337, "Disney+ Movies") } } },
            async { limited { fetchMovieBoxCategory("Paramount+ Movies", "Paramount Movies", true) { tmdb.getWatchProviderMovies(531, "Paramount+ Movies") } } },
            async { limited { fetchMovieBoxCategory("Action Movies", "Action Movies", true) { tmdb.getGenreMovies(28, "Action Movies") } } },
            async { limited { fetchMovieBoxCategory("Crime Movies", "Crime Movies", true) { tmdb.getGenreMovies(80, "Crime Movies") } } },
            async { limited { fetchMovieBoxCategory("Drama Movies", "Drama Movies", true) { tmdb.getGenreMovies(18, "Drama Movies") } } },
            async { limited { fetchMovieBoxCategory("Adventure Movies", "Adventure Movies", true) { tmdb.getGenreMovies(12, "Adventure Movies") } } },
            async { limited { fetchMovieBoxCategory("Comedy Movies", "Comedy Movies", true) { tmdb.getGenreMovies(35, "Comedy Movies") } } },
            async { limited { fetchMovieBoxCategory("Thriller Movies", "Thriller Movies", true) { tmdb.getGenreMovies(53, "Thriller Movies") } } },
            async { limited { fetchMovieBoxCategory("Romance Movies", "Romance Movies", true) { tmdb.getGenreMovies(10749, "Romance Movies") } } },
            async { limited { fetchMovieBoxCategory("Sci-Fi Movies", "Sci-Fi Movies", true) { tmdb.getGenreMovies(878, "Sci-Fi Movies") } } },
            async { limited { fetchMovieBoxCategory("Documentary Movies", "Documentary Movies", true) { tmdb.getGenreMovies(99, "Documentary Movies") } } },
            async { limited { fetchMovieBoxCategory("Horror Movies", "Horror Movies", true) { tmdb.getGenreMovies(27, "Horror Movies") } } },
            async { limited { fetchMovieBoxCategory("Fantasy Movies", "Fantasy Movies", true) { tmdb.getGenreMovies(14, "Fantasy Movies") } } },
            async { limited { fetchMovieBoxCategory("Family Movies", "Family Movies", true) { tmdb.getGenreMovies(10751, "Family Movies") } } },
            async { limited { fetchMovieBoxCategory("History Movies", "History Movies", true) { tmdb.getGenreMovies(36, "History Movies") } } },
            async { limited { fetchMovieBoxCategory("Mystery Movies", "Mystery Movies", true) { tmdb.getGenreMovies(9648, "Mystery Movies") } } },
            async { limited { fetchMovieBoxCategory("War Movies", "War Movies", true) { tmdb.getGenreMovies(10752, "War Movies") } } },
            async { limited { fetchMovieBoxCategory("Biography Movies", "Biography Movies", true) { tmdb.getBiography(true, "Biography Movies") } } },
            async { limited { fetchMovieBoxCategory("Sport Movies", "Sport Movies", true) { tmdb.getSport(true, "Sport Movies") } } },
            async { limited { fetchMovieBoxCategory("Western Movies", "Western Movies", true) { tmdb.getGenreMovies(37, "Western Movies") } } },
            async { limited { fetchMovieBoxCategory("Teen Romance Movies", "Teen Romance Movies", true) { tmdb.getTeenRomance(isMovie = true, name = "Teen Romance Movies") } } }
        ).awaitAll()
    }

    override suspend fun getTvShow(id: String): TvShow {
        val homeChannel = HomeIptvChannels.channels.find { it.id == id || it.name.equals(id, ignoreCase = true) }
        if (homeChannel != null) {
            return TvShow(
                id = homeChannel.id,
                title = homeChannel.name,
                poster = homeChannel.logo ?: "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg",
                quality = "LIVE",
                providerName = name
            ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
        }
        if (id.startsWith("cdn:")) {
            // First try CDN directly
            val cdnResult = runCatching { CdnLiveTvProvider.getTvShow(id) }
            if (cdnResult.isSuccess) return cdnResult.getOrThrow()

            // Fallback: Try to find matching channel in IPTV by name
            Log.w("NexaHomeProvider", "CDN getTvShow failed for $id, trying IPTV fallback")
            val cdnChannel = cdnResult.getOrNull() ?: TvShow(id = id, title = "Unknown Channel")
            val iptvChannels = runCatching { IptvOrgProvider.search(cdnChannel.title, 1, null) }.getOrNull().orEmpty()
            val matchingIptv = iptvChannels.firstOrNull() as? TvShow
            return matchingIptv ?: cdnChannel
        }
        return runCatching { MovieBoxProvider.getTvShow(id) }.getOrElse { tmdb.getTvShow(id) }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val homeChannel = HomeIptvChannels.channels.find { it.id == id || it.name.equals(id, ignoreCase = true) }
        if (homeChannel != null) {
            return listOf(
                Video.Server(id = homeChannel.url, name = "Live 1080p (Default)", src = homeChannel.name),
                Video.Server(id = "${homeChannel.url}#480p", name = "Live 480p", src = homeChannel.name)
            )
        }
        if (id.startsWith("cdn:")) {
            val cdnChannel = runCatching { CdnLiveTvProvider.getTvShow(id) }.getOrNull()
            val channelTitle = cdnChannel?.title ?: id.removePrefix("cdn:")

            val cdnResult = runCatching { CdnLiveTvProvider.getServers(id, videoType) }
            val cdnServers = cdnResult.getOrNull().orEmpty().map { srv ->
                srv.copy(src = channelTitle)
            }

            // Also fetch IPTV All World fallback servers by matching channel title
            val iptvChannels = if (channelTitle.isNotBlank()) {
                runCatching { IptvOrgProvider.search(channelTitle, 1, null) }.getOrNull().orEmpty()
            } else emptyList()

            val matchingIptv = iptvChannels.firstOrNull() as? TvShow
            val iptvServers = if (matchingIptv != null) {
                val dummyType = com.nexastream.app.models.Video.Type.Movie(matchingIptv.id, matchingIptv.title, "", matchingIptv.poster ?: "", null)
                runCatching { IptvOrgProvider.getServers(matchingIptv.id, dummyType) }.getOrNull().orEmpty().map { srv ->
                    srv.copy(name = "IPTV All World (${srv.name})", src = channelTitle)
                }
            } else emptyList()

            val combined = mutableListOf<Video.Server>()
            combined.addAll(cdnServers)
            combined.addAll(iptvServers)
            if (combined.isNotEmpty()) return combined
            throw IllegalStateException("No live stream servers available for this channel.")
        }
        val mbServers = runCatching { MovieBoxProvider.getServers(id, videoType) }.getOrElse { emptyList() }
        val tmdbServers = runCatching { tmdb.getServers(id, videoType) }.getOrElse { emptyList() }
        val servers = mbServers + tmdbServers
        return sortHomeServers(servers)
    }

    private fun sortHomeServers(servers: List<Video.Server>): List<Video.Server> {
        val preferredServer = UserPreferences.preferredServerName
        return servers.distinctBy { if (it.name.isNotBlank()) it.name.uppercase().trim() else it.id }.sortedWith { s1, s2 ->
            val n1 = s1.name.uppercase()
            val n2 = s2.name.uppercase()

            // 1. VixSrc ALWAYS takes absolute top precedence as default server (#1)
            if (n1.contains("VIXSRC") && !n2.contains("VIXSRC")) return@sortedWith -1
            if (n2.contains("VIXSRC") && !n1.contains("VIXSRC")) return@sortedWith 1

            // 2. MovieBox as fallback stream server (#2)
            if (n1.contains("MOVIEBOX") && !n2.contains("MOVIEBOX")) return@sortedWith -1
            if (n2.contains("MOVIEBOX") && !n1.contains("MOVIEBOX")) return@sortedWith 1

            // 3. User preferred server (if set and not VixSrc/MovieBox)
            if (!preferredServer.isNullOrBlank()) {
                val pref = preferredServer.uppercase()
                if (!pref.contains("VIXSRC") && !pref.contains("MOVIEBOX")) {
                    if (n1 == pref && n2 != pref) return@sortedWith -1
                    if (n2 == pref && n1 != pref) return@sortedWith 1
                    if (n1.contains(pref) && !n2.contains(pref)) return@sortedWith -1
                    if (n2.contains(pref) && !n1.contains(pref)) return@sortedWith 1
                }
            }

            0
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val homeChannel = HomeIptvChannels.channels.find { 
            it.id == server.id.substringBefore("#480p") || 
            it.url == server.id.substringBefore("#480p") || 
            it.name.equals(server.src, ignoreCase = true) 
        }
        if (homeChannel != null) {
            val url = server.id.substringBefore("#480p")
            return@withContext Video(
                source = url,
                headers = mapOf(
                    "User-Agent" to (homeChannel.userAgent ?: "Lavf/56.15.102"),
                    "Referer" to "https://cdnlivetv.tv/",
                    "Origin" to "https://cdnlivetv.tv"
                )
            )
        }

        if (server.name.contains("IPTV All World")) {
            return@withContext runCatching { IptvOrgProvider.getVideo(server) }.getOrNull()
                ?: throw IllegalStateException("IPTV stream is currently offline.")
        }

        if (server.name.contains("CDN") || server.id.contains("cdnlivetv.tv") || server.id.startsWith("http")) {
            // First try CDN directly
            val cdnResult = runCatching { CdnLiveTvProvider.getVideo(server) }
            val video = cdnResult.getOrNull()
            if (cdnResult.isSuccess && video != null && video.source.isNotEmpty() &&
                !video.source.contains("/player/") && !video.source.contains("cdnlivetv.tv/channels/player")) {
                return@withContext video
            }

            // Fallback: Try IPTV All World (IptvOrgProvider) using channel title
            val channelTitle = server.src.ifBlank {
                runCatching { CdnLiveTvProvider.getTvShow("cdn:${server.id}").title }.getOrNull().orEmpty()
            }

            Log.w("NexaHomeProvider", "CDN getVideo failed for '${server.id}' (title: '$channelTitle'), attempting IPTV All World fallback")

            if (channelTitle.isNotBlank()) {
                val iptvVideo = runCatching {
                    val channels = IptvOrgProvider.search(channelTitle, 1, null).orEmpty()
                    val matching = channels.firstOrNull() as? TvShow
                    if (matching != null) {
                        val dummyType = com.nexastream.app.models.Video.Type.Movie(matching.id, matching.title, "", matching.poster ?: "", null)
                        val servers = IptvOrgProvider.getServers(matching.id, dummyType)
                        val srv = servers.firstOrNull()
                        if (srv != null) {
                            IptvOrgProvider.getVideo(srv)
                        } else null
                    } else null
                }.getOrNull()

                if (iptvVideo != null && iptvVideo.source.isNotEmpty()) {
                    Log.d("NexaHomeProvider", "IPTV All World fallback succeeded for '$channelTitle': ${iptvVideo.source}")
                    return@withContext iptvVideo
                }
            }

            if (video != null && video.source.isNotEmpty() && !video.source.contains("/player/")) {
                return@withContext video
            }

            throw IllegalStateException("Live stream source unavailable or stream is currently offline.")
        } else {
            tmdb.getVideo(server)
        }
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> = coroutineScope {
        runCatching { tmdb.search(query, page, filters) }.getOrElse { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = coroutineScope {
        runCatching { tmdb.getMovies(page) }.getOrElse { emptyList() }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = coroutineScope {
        runCatching { tmdb.getTvShows(page) }.getOrElse { emptyList() }
    }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        tmdb.getMovie(id)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = coroutineScope {
        val mbEps = runCatching { MovieBoxProvider.getEpisodesBySeason(seasonId) }.getOrElse { emptyList() }
        if (mbEps.isNotEmpty()) mbEps else tmdb.getEpisodesBySeason(seasonId)
    }
    override suspend fun getGenre(id: String, page: Int): Genre = when {
        id == "cdn_all_channels" -> {
            runCatching { CdnLiveTvProvider.getGenre(id, page) }.getOrElse {
                Log.w("NexaHomeProvider", "CDN getGenre failed for $id, trying IPTV fallback")
                // Fallback to IPTV sports/news categories
                val iptvHome = runCatching { IptvOrgProvider.getHome() }.getOrNull().orEmpty()
                val allChannels = iptvHome.flatMap { it.list }.filterIsInstance<TvShow>()
                Genre(id = id, name = "Live Channels", shows = allChannels.take(50))
            }
        }
        id == "cdn_sports" -> {
            runCatching { CdnLiveTvProvider.getGenre(id, page) }.getOrElse {
                Log.w("NexaHomeProvider", "CDN getGenre failed for $id, trying IPTV fallback")
                // Fallback to IPTV sports category
                runCatching { IptvOrgProvider.getGenre("Sports", page) }.getOrNull() ?: Genre(id = id, name = "Sports", shows = emptyList())
            }
        }
        id == "tmdb_recommended_for_you" || id == "Recommended For You" || id == "✨ Recommended For You" -> {
            val db = runCatching {
                com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance)
            }.getOrNull()
            val profile = com.nexastream.app.utils.RecommendationEngine.buildUserInterestProfile(db)
            val topGenres = profile.genreWeights.entries.sortedByDescending { it.value }.take(3).map { it.key }

            val baseResults = if (topGenres.isNotEmpty()) {
                val aiIntent = com.nexastream.app.utils.AiSearchEngine.parseQuery(topGenres.joinToString(" "))
                com.nexastream.app.utils.AiSearchEngine.discoverByAiIntent(aiIntent, language = "en", page = page)
            } else {
                TMDb3.Trending.all(TMDb3.Params.TimeWindow.WEEK, page = page, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            }

            val nollywoodFeatured = runCatching { fetchNollywoodMovieBoxRow()?.list.orEmpty().take(3) }.getOrDefault(emptyList())
            val kenyanFeatured = runCatching { fetchKenyanSeriesMovieBoxRow()?.list.orEmpty().take(3) }.getOrDefault(emptyList())

            val combined = (baseResults + nollywoodFeatured + kenyanFeatured).distinctBy { item ->
                when (item) {
                    is com.nexastream.app.models.Movie -> "movie:${item.id}"
                    is com.nexastream.app.models.TvShow -> "tv:${item.id}"
                    else -> item.hashCode().toString()
                }
            }
            Genre(id = id, name = "Recommended For You", shows = combined.filterIsInstance<com.nexastream.app.models.Show>())
        }
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
            val cat = tmdb.getTeenRomance(isMovie = true, page = page, name = "Teen Romance Movies")
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id == "teen_romance_series" -> {
            val cat = tmdb.getTeenRomance(isMovie = false, page = page, name = "Teen Romance Series")
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id == "wwe_moviebox" -> {
            val queries = listOf("WWE", "WrestleMania", "WWE Raw", "WWE SmackDown", "AEW", "Wrestling")
            val items = queries.flatMap { q ->
                runCatching { MovieBoxProvider.search(q, page, null) }.getOrElse { emptyList() }
            }.distinctBy { when (it) { is Movie -> it.id; is TvShow -> it.id; else -> it.hashCode().toString() } }
            Genre(id = id, name = "WWE Live & Replay", shows = items)
        }
        id == "nollywood_movies" -> {
            val tmdbCat = runCatching { tmdb.getNollywoodMovies(page = page, name = "Nollywood Movies") }.getOrNull()
            val tmdbItems = tmdbCat?.list.orEmpty().filterIsInstance<Movie>()

            val queries = listOf("Nollywood", "Nigerian", "Lagos", "Anikulapo", "King of Boys", "The Wedding Party", "Brotherhood", "Gangs of Lagos", "The Black Book", "A Tribe Called Judah", "Jagun Jagun", "Lisabi", "Breath of Life")
            val mbItems = queries.flatMap { q ->
                runCatching { MovieBoxProvider.search(q, page, null) }.getOrElse { emptyList() }
            }.filterIsInstance<Movie>().map { item ->
                item.copy().apply { providerName = "MovieBox" }
            }

            val existingNormalizedTitles = tmdbItems.mapTo(mutableSetOf()) {
                it.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            }

            val extraMbItems = mbItems.filter { item ->
                val norm = item.title.lowercase().replace(Regex("[^a-z0-9]"), "")
                norm.isNotEmpty() && existingNormalizedTitles.add(norm)
            }

            val combined = (tmdbItems + extraMbItems).distinctBy { it.id }
            Genre(id = id, name = "Nollywood Movies", shows = combined)
        }
        id == "kenyan_series" -> {
            val tmdbCat = runCatching { tmdb.getKenyanContent(page = page, name = "Kenyan Movies & Series") }.getOrNull()
            val tmdbItems = tmdbCat?.list.orEmpty().filterIsInstance<com.nexastream.app.models.Show>()

            val queries = listOf(
                "Country Queen", "Country 49", "Crime and Justice", "Single Kiasi", "Pepeta",
                "Subterranea", "Volume", "Mpakani", "Kash Money", "Nairobi Half Life", "Click Click Bang",
                "Disconnect", "Poacher", "Kina", "Salem", "Selina", "Zora", "Njoro Wa Uba",
                "Softie", "Supa Modo", "Kati Kati", "Faithless"
            )
            val mbItems = queries.flatMap { q ->
                runCatching { MovieBoxProvider.search(q, page, null) }.getOrElse { emptyList() }
            }.filter { item ->
                val title = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }.lowercase()
                val overview = when (item) { is Movie -> item.overview; is TvShow -> item.overview; else -> "" }?.lowercase().orEmpty()
                val combined = "$title $overview"
                !combined.contains("plug") && !combined.contains("news") && !combined.contains("vlog") &&
                !combined.contains("interview") && !combined.contains("podcast") && !combined.contains("reaction") &&
                !combined.contains("tiktok") && !combined.contains("short") && !combined.contains("skit") &&
                !combined.contains("part ") && !combined.contains("full video") && !combined.contains("whatsapp") &&
                !combined.contains("status") && !combined.contains("funny") && !combined.contains("prank") &&
                !combined.contains("gossip") && !combined.contains("viral") && !combined.contains("update") &&
                !combined.contains("music") && !combined.contains("song") && !combined.contains("album") &&
                !combined.contains("audio") && !combined.contains("concert") && !combined.contains("remix") &&
                !combined.contains("mixtape") && !combined.contains("dj ") && !combined.contains("ft.") &&
                !combined.contains("feat.") && !combined.contains("official video") && !combined.contains("soundtrack") &&
                !combined.contains("amejibeba") && !combined.contains("waithira") && !combined.contains("local news")
            }.map { item ->
                when (item) {
                    is Movie -> item.copy().apply { providerName = "MovieBox" }
                    is TvShow -> item.copy().apply { providerName = "MovieBox" }
                    else -> item
                }
            }

            val existingNormalizedTitles = tmdbItems.mapTo(mutableSetOf()) { item ->
                val title = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }
                title.lowercase().replace(Regex("[^a-z0-9]"), "")
            }

            val extraMbItems = mbItems.filter { item ->
                val title = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }
                val norm = title.lowercase().replace(Regex("[^a-z0-9]"), "")
                norm.isNotEmpty() && existingNormalizedTitles.add(norm)
            }

            val combined = (tmdbItems + extraMbItems).distinctBy { item ->
                when (item) {
                    is Movie -> "movie:${item.id}"
                    is TvShow -> "tv:${item.id}"
                    else -> item.hashCode().toString()
                }
            }
            Genre(id = id, name = "Kenyan Movies & Series", shows = combined)
        }
        id == "upcoming_matches" || id.equals("Upcoming Matches", ignoreCase = true) || id.equals("Upcoming Sports", ignoreCase = true) || id.equals("Daily Agenda", ignoreCase = true) -> {
            val matches = runCatching { AkSportsLiveProvider.getUpcomingMatches() }.getOrDefault(emptyList())
            Genre(id = id, name = "Upcoming Matches", shows = matches)
        }
        id == "continue_watching" || id.equals("Continue Watching", ignoreCase = true) -> {
            val db = runCatching { com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance) }.getOrNull()
            val movies = runCatching { db?.movieDao()?.getAll() }.getOrNull().orEmpty().filter { it.watchHistory != null || it.isWatched }
            val tvShows = runCatching { db?.tvShowDao()?.getAllForBackup() }.getOrNull().orEmpty().filter { it.isWatching }
            Genre(id = id, name = "Continue Watching", shows = (movies + tvShows))
        }
        id == "favorite_movies" || id.equals(Category.FAVORITE_MOVIES, ignoreCase = true) -> {
            val db = runCatching { com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance) }.getOrNull()
            val movies = runCatching { db?.movieDao()?.getAll() }.getOrNull().orEmpty().filter { it.isFavorite }
            Genre(id = id, name = Category.FAVORITE_MOVIES, shows = movies)
        }
        id == "favorite_tv_shows" || id.equals(Category.FAVORITE_TV_SHOWS, ignoreCase = true) -> {
            val db = runCatching { com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance) }.getOrNull()
            val tvShows = runCatching { db?.tvShowDao()?.getAllForBackup() }.getOrNull().orEmpty().filter { it.isFavorite }
            Genre(id = id, name = Category.FAVORITE_TV_SHOWS, shows = tvShows)
        }
        id == "mcu" || id.contains("Marvel", ignoreCase = true) -> {
            val cat = tmdb.getFranchiseContent("⚡ Marvel Cinematic Universe", companyId = 420)
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id == "star_wars" || id.contains("Star Wars", ignoreCase = true) -> {
            val cat = tmdb.getFranchiseContent("⚔️ Star Wars Universe", keywordId = 161176)
            Genre(id = id, name = cat.name, shows = cat.list)
        }
        id == "dc" || id.contains("DC Extended", ignoreCase = true) -> {
            val cat = tmdb.getFranchiseContent("🦇 DC Extended Universe", companyId = 128064)
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
            val mbMovies = runCatching { MovieBoxProvider.getMovies(page) }.getOrElse { emptyList() }
            val shows = if (mbMovies.isNotEmpty()) mbMovies else {
                TMDb3.MovieLists.popular(page = page, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            }
            Genre(id = id, name = "Popular Movies", shows = shows)
        }
        id == "tmdb_tv_top_rated" -> {
            val mbShows = runCatching { MovieBoxProvider.getCategory("western", page).filterIsInstance<TvShow>() }.getOrElse { emptyList() }
            val shows = if (mbShows.isNotEmpty()) mbShows else {
                TMDb3.TvSeriesLists.topRated(page = page, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            }
            Genre(id = id, name = "Top Rated TV Shows", shows = shows)
        }
        id == "tmdb_tv_popular" -> {
            val mbShows = runCatching { MovieBoxProvider.getTvShows(page) }.getOrElse { emptyList() }
            val shows = if (mbShows.isNotEmpty()) mbShows else {
                TMDb3.TvSeriesLists.popular(page = page, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            }
            Genre(id = id, name = "Popular TV Shows", shows = shows)
        }
        id == "tmdb_kids_family" -> {
            val mbKids = runCatching { MovieBoxProvider.getCategory("kids", page) }.getOrElse { emptyList() }
            val list = if (mbKids.isNotEmpty()) mbKids else tmdb.getKidsContent().list
            Genre(id = id, name = "Kids & Family", shows = list)
        }
        id == "tmdb_anime_universe" -> {
            val mbAnime = runCatching { MovieBoxProvider.getCategory("anime", page) }.getOrElse { emptyList() }
            val list = if (mbAnime.isNotEmpty()) mbAnime else tmdb.getAnimeContent().list
            Genre(id = id, name = "Anime", shows = list)
        }
        id == "tmdb_cartoon_movies" -> {
            val mbKids = runCatching { MovieBoxProvider.getCategory("kids", page).filterIsInstance<Movie>() }.getOrElse { emptyList() }
            val list = if (mbKids.isNotEmpty()) mbKids else tmdb.getCartoonMovies().list
            Genre(id = id, name = "Cartoon Movies", shows = list)
        }
        id == "tmdb_cartoon_series" -> {
            val mbKids = runCatching { MovieBoxProvider.getCategory("kids", page).filterIsInstance<TvShow>() }.getOrElse { emptyList() }
            val list = if (mbKids.isNotEmpty()) mbKids else tmdb.getCartoonSeries().list
            Genre(id = id, name = "Cartoon Series", shows = list)
        }
        id == "tmdb_japanese_anime" -> {
            val mbAnime = runCatching { MovieBoxProvider.getCategory("anime", page) }.getOrElse { emptyList() }
            val list = if (mbAnime.isNotEmpty()) mbAnime else tmdb.getJapaneseAnime().list
            Genre(id = id, name = "Japanese Anime", shows = list)
        }
        id == "tmdb_western_anime" -> {
            val mbAnime = runCatching { MovieBoxProvider.getCategory("anime", page) }.getOrElse { emptyList() }
            val list = if (mbAnime.isNotEmpty()) mbAnime else tmdb.getWesternAnime().list
            Genre(id = id, name = "Western Anime", shows = list)
        }
        id == "tmdb_anime_age_7_12" -> tmdb.getAnimeAge7to12().let { Genre(id = id, name = it.name, shows = it.list) }
        id == "upcoming_releases" || id == "tmdb_upcoming_movies" || id == "Upcoming & Most Anticipated" || id == "Coming Soon" || id == "📅 Coming Soon" -> {
            val cat = tmdb.getUpcomingMovies()
            Genre(id = id, name = "Coming Soon", shows = cat.list)
        }
        id == "watch_again" || id == "🔄 Watch Again" -> {
            val cat = fetchWatchAgainRow()
            Genre(id = id, name = "Watch Again", shows = cat?.list ?: emptyList())
        }
        id == "cdn_all_channels" || id.equals("CDN Live Channels", ignoreCase = true) || id.equals("Livestream", ignoreCase = true) -> {
            val cdnGenre = runCatching { CdnLiveTvProvider.getGenre("cdn_all_channels", page) }.getOrNull()?.shows.orEmpty().filterIsInstance<TvShow>()
            val homeIptvShows = HomeIptvChannels.getTvShows()
            val combined = (cdnGenre + homeIptvShows).distinctBy { it.title }
            Genre(id = id, name = "CDN Live TV & Channels", shows = combined)
        }
        id == "cdn_sports" || id.equals("Live Sports (CDN)", ignoreCase = true) || id.equals("Live Sports", ignoreCase = true) || id.equals("Sports", ignoreCase = true) -> {
            val cdnSports = runCatching { CdnLiveTvProvider.getGenre("cdn_sports", page) }.getOrNull()?.shows.orEmpty().filterIsInstance<TvShow>()
            val sportsChannels = HomeIptvChannels.getTvShows("Sports") + HomeIptvChannels.getTvShows("Sky Sports") + HomeIptvChannels.getTvShows("TNT Sports") + HomeIptvChannels.getTvShows("Match!") + HomeIptvChannels.getTvShows("Bein Sports") + HomeIptvChannels.getTvShows("US Sports")
            val combined = (cdnSports + sportsChannels).distinctBy { it.title }
            Genre(id = id, name = "Live Sports", shows = combined)
        }
        id.startsWith("cdn_") -> {
            runCatching { CdnLiveTvProvider.getGenre(id, page) }.getOrElse {
                Log.w("NexaHomeProvider", "CDN getGenre failed for $id, trying IPTV fallback")
                when {
                    id.contains("sports", ignoreCase = true) -> runCatching { IptvOrgProvider.getGenre("Sports", page) }.getOrNull() ?: Genre(id = id, name = "Sports", shows = emptyList())
                    id.contains("news", ignoreCase = true) -> runCatching { IptvOrgProvider.getGenre("News", page) }.getOrNull() ?: Genre(id = id, name = "News", shows = emptyList())
                    else -> {
                        val iptvHome = runCatching { IptvOrgProvider.getHome() }.getOrNull().orEmpty()
                        val allChannels = iptvHome.flatMap { it.list }.filterIsInstance<TvShow>()
                        Genre(id = id, name = "Live Channels", shows = allChannels.take(50))
                    }
                }
            }
        }
        else -> {
            val groupChannels = HomeIptvChannels.channels.filter { 
                it.group.equals(id, ignoreCase = true) || 
                id.contains(it.group, ignoreCase = true) ||
                it.name.contains(id, ignoreCase = true) 
            }
            if (groupChannels.isNotEmpty() && !id.startsWith("tmdb_")) {
                val shows = groupChannels.map { data ->
                    TvShow(
                        id = data.id,
                        title = data.name,
                        poster = data.logo ?: "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg",
                        quality = "LIVE",
                        providerName = name
                    ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
                }
                Genre(id = id, name = id, shows = shows)
            } else {
                runCatching {
                    tmdb.getGenre(id, page)
                }.getOrElse {
                    val cat = tmdb.getSearchContent(id, id)
                    Genre(id = id, name = id, shows = cat.list)
                }
            }
        }
    }

    private suspend fun fetchBecauseYouWatchedRow(): Category? = coroutineScope {
        val db = runCatching {
            com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance)
        }.getOrNull() ?: return@coroutineScope null

        val watchingMovies = runCatching { db.movieDao().getWatchingMovies().firstOrNull().orEmpty() }.getOrDefault(emptyList())
        val recentMovie = watchingMovies.maxByOrNull {
            it.watchHistory?.lastEngagementTimeUtcMillis ?: it.watchedDate?.timeInMillis ?: 0L
        }

        val watchingEpisodes = runCatching { db.episodeDao().getWatchingEpisodes().firstOrNull().orEmpty() }.getOrDefault(emptyList())
        val recentEpisode = watchingEpisodes.maxByOrNull {
            it.watchHistory?.lastEngagementTimeUtcMillis ?: it.watchedDate?.timeInMillis ?: 0L
        }

        val movieTime = recentMovie?.watchHistory?.lastEngagementTimeUtcMillis
            ?: recentMovie?.watchedDate?.timeInMillis ?: 0L
        val episodeTime = recentEpisode?.watchHistory?.lastEngagementTimeUtcMillis
            ?: recentEpisode?.watchedDate?.timeInMillis ?: 0L

        if (movieTime == 0L && episodeTime == 0L) return@coroutineScope null

        if (movieTime >= episodeTime && recentMovie != null) {
            val movieId = recentMovie.id.toIntOrNull() ?: return@coroutineScope null
            val movieTitle = recentMovie.title.ifBlank {
                runCatching { db.movieDao().getById(recentMovie.id)?.title }.getOrNull().orEmpty()
            }
            val rec = runCatching { tmdb.getMovieRecommendations(movieId, movieTitle) }.getOrNull()
            if (rec != null && rec.list.isNotEmpty()) {
                rec.apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            } else null
        } else if (recentEpisode != null) {
            val tvShow = recentEpisode.tvShow
            val seriesId = tvShow?.id?.toIntOrNull() ?: recentEpisode.id.toIntOrNull() ?: return@coroutineScope null
            var showTitle = tvShow?.title.orEmpty()
            if (showTitle.isBlank()) {
                val dbShow = runCatching { db.tvShowDao().getById(seriesId.toString()) }.getOrNull()
                showTitle = dbShow?.title.orEmpty().ifBlank { recentEpisode.title.orEmpty() }
            }
            val rec = runCatching { tmdb.getTvRecommendations(seriesId, showTitle) }.getOrNull()
            if (rec != null && rec.list.isNotEmpty()) {
                rec.apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            } else null
        } else null
    }

    private suspend fun fetchRecommendedForYouRow(): Category? = coroutineScope {
        val db = runCatching {
            com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance)
        }.getOrNull()
        val profile = com.nexastream.app.utils.RecommendationEngine.buildUserInterestProfile(db)
        val topGenres = profile.genreWeights.entries.sortedByDescending { it.value }.take(3).map { it.key }

        val baseResults: List<AppAdapter.Item> = if (topGenres.isNotEmpty()) {
            val aiIntent = com.nexastream.app.utils.AiSearchEngine.parseQuery(topGenres.joinToString(" "))
            com.nexastream.app.utils.AiSearchEngine.discoverByAiIntent(aiIntent, language = "en", page = 1)
        } else {
            runCatching {
                TMDb3.Trending.all(TMDb3.Params.TimeWindow.WEEK, page = 1, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            }.getOrNull().orEmpty()
        }

        val nollywoodFeatured = runCatching { fetchNollywoodMovieBoxRow()?.list.orEmpty().take(3) }.getOrDefault(emptyList())
        val kenyanFeatured = runCatching { fetchKenyanSeriesMovieBoxRow()?.list.orEmpty().take(3) }.getOrDefault(emptyList())

        val combinedResults = (baseResults + nollywoodFeatured + kenyanFeatured).distinctBy { item ->
            when (item) {
                is com.nexastream.app.models.Movie -> "movie:${item.id}"
                is com.nexastream.app.models.TvShow -> "tv:${item.id}"
                else -> item.hashCode().toString()
            }
        }

        if (combinedResults.isNotEmpty()) {
            Category(name = "✨ Recommended For You", list = combinedResults.filterIsInstance<com.nexastream.app.models.Show>())
                .apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
        } else null
    }

    private suspend fun fetchTimeOfDayVibeRow(): Category? = coroutineScope {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)

        val (title, query) = when {
            hour >= 22 || hour < 4 -> "✨ Late Night Sci-Fi & Thrillers" to "mind-bending sci-fi space horror thriller movies"
            dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY ->
                "🍿 Weekend Family Blockbusters" to "top rated animated family adventure movies"
            dayOfWeek == java.util.Calendar.FRIDAY && hour >= 17 -> "🔥 Friday Night Action" to "high octane action blockbuster movies"
            else -> "⭐ Recommended Vibes" to "popular trending movies and tv series"
        }

        val aiIntent = com.nexastream.app.utils.AiSearchEngine.parseQuery(query)
        val items = runCatching {
            com.nexastream.app.utils.AiSearchEngine.discoverByAiIntent(aiIntent, language = "en", page = 1)
        }.getOrNull().orEmpty()

        if (items.isNotEmpty()) {
            Category(name = title, list = items).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
        } else null
    }

    private suspend fun fetchCinematicUniversesRows(): List<Category> = coroutineScope {
        val mcuDef = async {
            runCatching {
                tmdb.getFranchiseContent(
                    franchiseName = "⚡ Marvel Cinematic Universe",
                    companyId = 420
                )
            }.getOrNull()
        }
        val starWarsDef = async {
            runCatching {
                tmdb.getFranchiseContent(
                    franchiseName = "⚔️ Star Wars Universe",
                    keywordId = 161176
                )
            }.getOrNull()
        }
        val dcDef = async {
            runCatching {
                tmdb.getFranchiseContent(
                    franchiseName = "🦇 DC Extended Universe",
                    companyId = 128064
                )
            }.getOrNull()
        }

        listOfNotNull(mcuDef.await(), starWarsDef.await(), dcDef.await())
            .filter { it.list.isNotEmpty() }
            .map { it.apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM } }
    }

    private suspend fun fetchUpcomingReleasesRow(): Category? = coroutineScope {
        val cat = runCatching { tmdb.getUpcomingMovies() }.getOrNull()
        if (cat != null && cat.list.isNotEmpty()) {
            cat.copy(name = "Coming Soon").apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
        } else null
    }

    private suspend fun fetchWatchAgainRow(): Category? = coroutineScope {
        val db = runCatching {
            com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance)
        }.getOrNull() ?: return@coroutineScope null

        val now = System.currentTimeMillis()
        val fourteenDaysMillis = 14L * 24 * 60 * 60 * 1000

        val movies = runCatching { db.movieDao().getAll() }.getOrDefault(emptyList())
        val watchAgainMovies = movies.filter { movie ->
            val history = movie.watchHistory
            val isFinished = history != null && history.durationMillis > 0 && history.lastPlaybackPositionMillis >= (history.durationMillis * 0.9)
            val isWatched = movie.isWatched || isFinished
            val lastTime = history?.lastEngagementTimeUtcMillis ?: movie.watchedDate?.timeInMillis ?: now
            val isOldEnough = (now - lastTime) > fourteenDaysMillis
            (isWatched && isOldEnough) || movie.isFavorite
        }

        val tvShows = runCatching { db.tvShowDao().getAllForBackup() }.getOrDefault(emptyList())
        val watchAgainTvShows = tvShows.filter { tv ->
            val isWatching = tv.isWatching || tv.isFavorite
            val isOldEnough = (now - (tv.favoritedAtMillis ?: now)) > fourteenDaysMillis
            isWatching && isOldEnough
        }

        val combined: List<AppAdapter.Item> = (watchAgainMovies + watchAgainTvShows).take(15)
        if (combined.isNotEmpty()) {
            Category(name = "🔄 Watch Again", list = combined).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
        } else null
    }



    private suspend fun fetchWweMovieBoxRow(): Category? = coroutineScope {
        val queries = listOf("WWE", "WrestleMania", "WWE Raw", "WWE SmackDown", "AEW", "Wrestling")
        val items = queries.flatMap { q ->
            runCatching { MovieBoxProvider.search(q, 1, null) }.getOrElse { emptyList() }
        }.map { item ->
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

        if (items.isNotEmpty()) {
            Category(name = "⚡ WWE Live & Replay", list = items).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
        } else null
    }

    private suspend fun fetchNollywoodMovieBoxRow(): Category? = coroutineScope {
        val tmdbCat = runCatching { tmdb.getNollywoodMovies(page = 1, name = "🎬 Nollywood Movies") }.getOrNull()
        val tmdbItems = tmdbCat?.list.orEmpty().filterIsInstance<Movie>()

        val queries = listOf("Nollywood", "Nigerian", "Lagos", "Anikulapo", "King of Boys", "The Wedding Party", "Brotherhood", "Gangs of Lagos", "The Black Book", "A Tribe Called Judah", "Jagun Jagun", "Lisabi", "Breath of Life")
        val mbItems = queries.flatMap { q ->
            runCatching { MovieBoxProvider.search(q, 1, null) }.getOrElse { emptyList() }
        }.filterIsInstance<Movie>().map { item ->
            item.copy().apply { providerName = "MovieBox" }
        }

        val existingNormalizedTitles = tmdbItems.mapTo(mutableSetOf()) {
            it.title.lowercase().replace(Regex("[^a-z0-9]"), "")
        }

        val extraMbItems = mbItems.filter { item ->
            val norm = item.title.lowercase().replace(Regex("[^a-z0-9]"), "")
            norm.isNotEmpty() && existingNormalizedTitles.add(norm)
        }

        val combined = (tmdbItems + extraMbItems).distinctBy { it.id }

        if (combined.isNotEmpty()) {
            Category(name = "🎬 Nollywood Movies", list = combined).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
        } else null
    }

    private suspend fun fetchKenyanSeriesMovieBoxRow(): Category? = coroutineScope {
        val tmdbCat = runCatching { tmdb.getKenyanContent(page = 1, name = "🌍 Kenyan Movies & Series") }.getOrNull()
        val tmdbItems = tmdbCat?.list.orEmpty().filterIsInstance<com.nexastream.app.models.Show>()

        val queries = listOf(
            "Country Queen", "Country 49", "Crime and Justice", "Single Kiasi", "Pepeta",
            "Subterranea", "Volume", "Mpakani", "Kash Money", "Nairobi Half Life", "Click Click Bang",
            "Disconnect", "Poacher", "Kina", "Salem", "Selina", "Zora", "Njoro Wa Uba",
            "Softie", "Supa Modo", "Kati Kati", "Faithless"
        )
        val mbItems = queries.flatMap { q ->
            runCatching { MovieBoxProvider.search(q, 1, null) }.getOrElse { emptyList() }
        }.filter { item ->
            val title = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }.lowercase()
            val overview = when (item) { is Movie -> item.overview; is TvShow -> item.overview; else -> "" }?.lowercase().orEmpty()
            val combined = "$title $overview"
            !combined.contains("plug") && !combined.contains("news") && !combined.contains("vlog") &&
            !combined.contains("interview") && !combined.contains("podcast") && !combined.contains("reaction") &&
            !combined.contains("tiktok") && !combined.contains("short") && !combined.contains("skit") &&
            !combined.contains("part ") && !combined.contains("full video") && !combined.contains("whatsapp") &&
            !combined.contains("status") && !combined.contains("funny") && !combined.contains("prank") &&
            !combined.contains("gossip") && !combined.contains("viral") && !combined.contains("update") &&
            !combined.contains("music") && !combined.contains("song") && !combined.contains("album") &&
            !combined.contains("audio") && !combined.contains("concert") && !combined.contains("remix") &&
            !combined.contains("mixtape") && !combined.contains("dj ") && !combined.contains("ft.") &&
            !combined.contains("feat.") && !combined.contains("official video") && !combined.contains("soundtrack") &&
            !combined.contains("amejibeba") && !combined.contains("waithira") && !combined.contains("local news")
        }.map { item ->
            when (item) {
                is Movie -> item.copy().apply { providerName = "MovieBox" }
                is TvShow -> item.copy().apply { providerName = "MovieBox" }
                else -> item
            }
        }

        val existingNormalizedTitles = tmdbItems.mapTo(mutableSetOf()) { item ->
            val title = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }
            title.lowercase().replace(Regex("[^a-z0-9]"), "")
        }

        val extraMbItems = mbItems.filter { item ->
            val title = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }
            val norm = title.lowercase().replace(Regex("[^a-z0-9]"), "")
            norm.isNotEmpty() && existingNormalizedTitles.add(norm)
        }

        val combined = (tmdbItems + extraMbItems).distinctBy { item ->
            when (item) {
                is Movie -> "movie:${item.id}"
                is TvShow -> "tv:${item.id}"
                else -> item.hashCode().toString()
            }
        }

        if (combined.isNotEmpty()) {
            Category(name = "🌍 Kenyan Movies & Series", list = combined).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
        } else null
    }

    override suspend fun getPeople(id: String, page: Int): People = tmdb.getPeople(id, page)
}
