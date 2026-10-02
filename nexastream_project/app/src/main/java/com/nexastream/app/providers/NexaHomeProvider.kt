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
    private val fetchSemaphore = Semaphore(8)

    private suspend inline fun <T> limited(crossinline block: suspend () -> T): T = fetchSemaphore.withPermit { block() }

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
        // Phase 0: Fast Hero Banner + CDN Live Channels (~1s)
        val cdnHomeDeferred = async { limited {
            val cdnList = runCatching { CdnLiveTvProvider.getHome() }.getOrNull().orEmpty()
            if (cdnList.isNotEmpty() && cdnList.any { it.list.isNotEmpty() }) {
                cdnList
            } else {
                Log.w("NexaHomeProvider", "CDN Live TV empty or failed, falling back to IPTV All World")
                runCatching { IptvOrgProvider.getHome() }.getOrElse { emptyList() }
            }
        } }

        val moviesBannerDef = async { limited { runCatching { tmdb.getFeaturedMovies() }.getOrNull() } }
        val seriesBannerDef = async { limited { runCatching { tmdb.getFeaturedTvShows() }.getOrNull() } }

        val cdnHome = cdnHomeDeferred.await()
        val moviesBanner = moviesBannerDef.await()
        val seriesBanner = seriesBannerDef.await()

        val categories = mutableListOf<Category>()

        moviesBanner?.let { categories.add(it.copy(name = "Movies Banner", list = it.list.safeSubList(0, 10))) }
        seriesBanner?.let { categories.add(it.copy(name = "Series Banner", list = it.list.safeSubList(0, 10))) }

        // LIVE CHANNELS
        cdnHome.find { it.name == "CDN Live Channels" }?.let { cat ->
            val priorityOrder = listOf(
                listOf("Sky Sport Premier", "Sky Sports Premier"),
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

            val filteredList = cat.list.filter { item ->
                val title = (item as? TvShow)?.title ?: ""
                !(title.contains("Canal Premier", ignoreCase = true) || title.contains("Canal Premier League", ignoreCase = true))
            }

            val sortedList = filteredList.sortedWith(compareBy<AppAdapter.Item> { item ->
                val title = (item as? TvShow)?.title ?: ""
                getPriorityIndex(title)
            })

            categories.add(cat.copy(name = "Livestream", list = sortedList))
        } ?: run {
            cdnHome.filter { it.name.contains("Sports", ignoreCase = true) || it.name.contains("News", ignoreCase = true) || it.name.contains("Entertainment", ignoreCase = true) }
                .take(3)
                .forEach { cat ->
                    categories.add(cat.copy(name = "Livestream · ${cat.name}"))
                }
        }

        categories
    }

    suspend fun getHomePhase1(phase0Categories: List<Category>): List<Category> = coroutineScope {
        // Phase 1: Core Content (Trending, Popular, Cinema, New Seasons)
        val tmdbHomeDeferred = async { limited { runCatching { tmdb.getHome() }.getOrElse { emptyList() } } }
        val latestMoviesDeferred = async { limited { runCatching { tmdb.getLatestMovies() }.getOrNull() } }
        val allCinemaDeferred = async { limited { runCatching { tmdb.getAllCinema() }.getOrNull() } }
        val newSeasonDeferred = async { limited { runCatching { tmdb.getNewSeasonsAndEpisodes() }.getOrNull() } }

        val tmdbHome = tmdbHomeDeferred.await()
        val latestMovies = latestMoviesDeferred.await()
        val allCinema = allCinemaDeferred.await()
        val newSeasonAndEpisodes = newSeasonDeferred.await()

        val categories = mutableListOf<Category>()
        categories.addAll(phase0Categories)

        // FEATURED BANNER from tmdbHome if not present
        tmdbHome.find { it.name == Category.FEATURED }?.let {
            if (categories.none { cat -> cat.name == Category.FEATURED }) {
                categories.add(0, it)
            }
        }

        // TRENDING / RECOMMENDED
        tmdbHome.find { it.name == "Trending" || it.name == "Di tendenza" || it.name == "Tendencias" }?.let { 
            categories.add(it.copy(name = "Trending Today")) 
        }

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

        val db = runCatching {
            com.nexastream.app.database.AppDatabase.getInstance(NexastreamApp.instance)
        }.getOrNull()
        val profile = com.nexastream.app.utils.RecommendationEngine.buildUserInterestProfile(db)
        com.nexastream.app.utils.RecommendationEngine.rankCategories(categories, profile)
    }

    suspend fun getHomePhase2(phase1Categories: List<Category>): List<Category> = coroutineScope {
        val isTv = try {
            NexastreamApp.instance.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        } catch (_: Exception) { false }

        // Secondary Rows Parallel Fetching (Phase 2)
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

        val kidsContent = kidsContentDef.await()
        val animeContent = animeContentDef.await()
        val trendingTeenRomance = trendingTeenRomanceDef.await()
        val animeRows = animeRowsDef.await()
        val kidsRows = kidsRowsDef.await()
        val seriesRows = seriesRowsDef.await()
        val movieRows = movieRowsDef.await()
        val topRatedMovies = topRatedMoviesDef.await()
        val topRatedTv = topRatedTvDef.await()
        val actionGenre = actionDef.await()
        val comedyGenre = comedyDef?.await()

        val categories = mutableListOf<Category>()
        categories.addAll(phase1Categories)

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
        topRatedMovies?.let { categories.add(it) }
        topRatedTv?.let { categories.add(it) }

        seriesRows.let { categories.addAll(it.filterNotNull()) }
        movieRows.let { categories.addAll(it.filterNotNull()) }

        kidsContent?.let { categories.add(it.copy(name = "Kids Section", list = it.list.safeSubList(0, 5))) }
        animeContent?.let { categories.add(it.copy(name = "Anime Section", list = it.list.safeSubList(0, 4))) }

        animeRows.let { categories.addAll(it.filterNotNull()) }
        kidsRows.let { categories.addAll(it.filterNotNull()) }

        actionGenre?.shows?.takeIf { it.isNotEmpty() }?.let {
            categories.add(Category(name = "Action & Adventure", list = it))
        }
        comedyGenre?.shows?.takeIf { it.isNotEmpty() }?.let {
            categories.add(Category(name = "Comedy", list = it))
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
        return tmdb.getTvShow(id)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
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
        }
        return tmdb.getServers(id, videoType)
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        if (server.name.contains("IPTV All World")) {
            return@withContext runCatching { IptvOrgProvider.getVideo(server) }.getOrNull() ?: Video(source = server.id)
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

            return@withContext video ?: Video(source = server.id)
        } else {
            tmdb.getVideo(server)
        }
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> = tmdb.search(query, page, filters)
    override suspend fun getMovies(page: Int): List<Movie> = tmdb.getMovies(page)
    override suspend fun getTvShows(page: Int): List<TvShow> = tmdb.getTvShows(page)
    override suspend fun getMovie(id: String): Movie = tmdb.getMovie(id)
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = tmdb.getEpisodesBySeason(seasonId)
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

            val results = if (topGenres.isNotEmpty()) {
                val aiIntent = com.nexastream.app.utils.AiSearchEngine.parseQuery(topGenres.joinToString(" "))
                com.nexastream.app.utils.AiSearchEngine.discoverByAiIntent(aiIntent, language = "en", page = page)
            } else {
                TMDb3.Trending.all(TMDb3.Params.TimeWindow.WEEK, page = page, language = "en").results.mapNotNull { tmdb.mapMulti(it) }
            }
            Genre(id = id, name = "Recommended For You", shows = results.filterIsInstance<com.nexastream.app.models.Show>())
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
        id.startsWith("cdn_") -> {
            runCatching { CdnLiveTvProvider.getGenre(id, page) }.getOrElse {
                Log.w("NexaHomeProvider", "CDN getGenre failed for $id, trying IPTV fallback")
                // Fallback to IPTV based on the ID
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
        else -> tmdb.getGenre(id, page)
    }
    override suspend fun getPeople(id: String, page: Int): People = tmdb.getPeople(id, page)
}
