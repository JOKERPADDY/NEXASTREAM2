package com.nexastream.app.providers

import android.util.Log
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.extractors.Extractor
import com.nexastream.app.extractors.PrimeSrcExtractor
import com.nexastream.app.extractors.TwoEmbedExtractor
import com.nexastream.app.extractors.VidLinkExtractor
import com.nexastream.app.extractors.VidflixExtractor
import com.nexastream.app.extractors.VidsrcNetExtractor
import com.nexastream.app.extractors.VidsrcRuExtractor
import com.nexastream.app.extractors.VidsrcToExtractor
import com.nexastream.app.extractors.VixSrcExtractor
import com.nexastream.app.utils.TmdbUtils
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.Season
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.providers.streams.StreamsApi
import com.nexastream.app.providers.streams.StreamsSeasonRes
import com.nexastream.app.providers.streams.StreamsStream
import com.nexastream.app.providers.streams.StreamsSubject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class StreamsShortEpisodePlayback(
    val url: String,
    val mimeType: String,
    val requestHeaders: Map<String, String>
)

object StreamsProvider : Provider {

    override val baseUrl = "https://movie-box.joke.blitz.cloud"
    override val name = "STREAMS"
    override val logo = "https://themoviebox.xyz/favicon.ico"
    override val language = "en"

    private val api: StreamsApi by lazy { StreamsApi.create() }
    private val subjectCache = ConcurrentHashMap<String, StreamsSubject>()
    private data class SearchCategory(
        val name: String,
        val query: String,
        val subjectType: Int? = null,
        val requiredTitle: String? = null
    )

    private val searchCategories = mapOf(
        "streams_cartoon_movies" to SearchCategory("Cartoon Movies", "cartoon movies", subjectType = 1),
        "streams_cartoon_series" to SearchCategory("Cartoon Series", "cartoon series", subjectType = 2),
        "streams_pixar" to SearchCategory("Pixar Studio", "Pixar"),
        "streams_dreamworks" to SearchCategory("DreamWorks Studio", "DreamWorks"),
        "streams_illumination" to SearchCategory("Illumination Studio", "Illumination"),
        "streams_kung_fu_panda" to SearchCategory("Kung Fu Panda", "Kung Fu Panda", requiredTitle = "Kung Fu Panda"),
        "streams_cars" to SearchCategory("Cars", "Cars", requiredTitle = "Cars"),
        "streams_frozen" to SearchCategory("Frozen", "Frozen", requiredTitle = "Frozen"),
        "streams_minions" to SearchCategory("Minions", "Minions", requiredTitle = "Minions"),
        "streams_anime_movies" to SearchCategory("Anime Movies", "anime movies", subjectType = 1),
        "streams_japanese_anime" to SearchCategory("Japanese Anime", "Japanese anime"),
        "streams_western_anime" to SearchCategory("Western Anime", "western anime"),
        "streams_dragon_ball" to SearchCategory("Dragon Ball", "Dragon Ball", requiredTitle = "Dragon Ball"),
        "streams_naruto" to SearchCategory("Naruto", "Naruto", requiredTitle = "Naruto"),
        "streams_one_piece" to SearchCategory("One Piece", "One Piece", requiredTitle = "One Piece")
    )

    fun viewAllRoute(categoryName: String): Pair<String, String> {
        val name = categoryName.trim()
        val normalized = name.lowercase(Locale.US)
        val searchCategory = searchCategories.entries.firstOrNull {
            it.value.name.equals(name, ignoreCase = true)
        }

        return when {
            normalized in setOf("live sports", "live sports (cdn)", "sports") ->
                "cdn_sports" to "Live Sports"
            normalized in setOf("livestream", "cdn live channels", "live channels") ->
                "cdn_all_channels" to "CDN Live TV"
            normalized in setOf("upcoming matches", "upcoming sports", "daily agenda", "upcoming & recent") ->
                "upcoming_matches" to "Upcoming Matches"
            normalized == "trending today" ->
                "streams_trending_today" to name
            normalized in setOf("kenyan series & movies", "kenyan movies & series", "kenyan series", "kenyan movies") ->
                "streams_kenyan_content" to name
            searchCategory != null -> searchCategory.key to name
            normalized in setOf("streams kids", "kids", "kids & family", "kids banner", "cartoon", "family") ->
                "streams_kids" to name
            normalized in setOf("movies banner", "popular movie", "popular movies", "all movies", "top movies", "latest movies") ->
                "streams_catalog_movies" to name
            normalized in setOf("series banner", "popular series", "popular tv shows", "all tv shows", "top tv shows") ->
                "streams_catalog_series" to name
            normalized in setOf("anime banner", "anime", "anime universe", "streams anime") ->
                "streams_search:anime" to name
            else -> "streams_search:$name" to name
        }
    }

    private val kenyanMediaExclusions = listOf(
        "music", "song", "soundtrack", "album", "concert", "remix", "mixtape",
        "playlist", "dj ", "official video", "music video", "video mix", "hits mix",
        "throwback", "radio", "instrumental", "lyrics", "lyric video", "dancehall",
        "afrobeats", "hip hop", "hiphop", "drill", "gospel", "rnb", "r&b", "mix"
    )

    override suspend fun getHome(): List<Category> = try {
        val categories = mutableListOf<Category>()
        val homeRes = api.getHome()
        homeRes.operatingList.orEmpty().forEach { section ->
            val subjects = if (section.type == "BANNER") {
                section.banner?.items.orEmpty().mapNotNull { it.subject }
            } else {
                section.subjects.orEmpty()
            }
            val items = subjects.mapNotNull { subject ->
                cacheSubject(subject)
                subjectToAppItem(subject)
            }
            if (items.isNotEmpty()) {
                val name = when {
                    section.type == "BANNER" -> Category.FEATURED
                    section.title.isNullOrBlank() -> Category.FEATURED
                    else -> section.title
                }
                categories += Category(name, items)
            }
        }

        if (categories.none { it.name.equals("Trending Today", ignoreCase = true) }) {
            val trendingItems = categories
                .filter { it.name.contains("popular", ignoreCase = true) || it.name.contains("trending", ignoreCase = true) }
                .flatMap { it.list }
                .filter { it is Movie || it is TvShow }
                .distinctBy { item ->
                    when (item) {
                        is Movie -> "movie:${item.id}"
                        is TvShow -> "series:${item.id}"
                        else -> item.hashCode().toString()
                    }
                }
            if (trendingItems.isNotEmpty()) {
                categories += Category("Trending Today", trendingItems)
            }
        }

        if (categories.none { it.name == Category.FEATURED || it.name.endsWith("Banner", ignoreCase = true) }) {
            categories.firstOrNull()?.let { first ->
                if (first.list.isNotEmpty()) {
                    categories.add(0, Category(Category.FEATURED, first.list.take(10)))
                }
            }
        }

        val allItems = categories.flatMap { it.list }
        val movies = allItems.filterIsInstance<Movie>()
        val series = allItems.filterIsInstance<TvShow>()

        if (categories.none { it.name == "Movies Banner" } && movies.isNotEmpty()) {
            categories.add(0, Category("Movies Banner", movies.take(10)))
        }
        if (categories.none { it.name == "Series Banner" } && series.isNotEmpty()) {
            categories.add(0, Category("Series Banner", series.take(10)))
        }
        suspend fun addCatalogCategory(name: String, catalog: String, fallbackQuery: String? = null) {
            if (categories.any { it.name == name }) return
            try {
                val catalogItems = try {
                    api.getCatalog(catalog, 1).items.orEmpty()
                } catch (e: Exception) {
                    if (fallbackQuery == null) throw e
                    Log.w("StreamsProvider", "$catalog catalog unavailable; using search fallback", e)
                    api.search(fallbackQuery, 1).items.orEmpty()
                }
                val items = catalogItems
                    .distinctBy { it.detailPath ?: it.subjectId }
                    .mapNotNull { subject ->
                    cacheSubject(subject)
                    subjectToAppItem(subject)
                }
                if (items.isNotEmpty()) {
                    categories += Category(name, items)
                }
            } catch (e: Exception) {
                Log.w("StreamsProvider", "Could not load $name home category", e)
            }
        }

        addCatalogCategory("STREAMS Kids", "kids", fallbackQuery = "kids cartoons")

        val (liveMatches, upcomingMatches, liveTvCategories) = coroutineScope {
            val liveMatchesDeferred = async {
                try {
                    withTimeoutOrNull(2_500) { AkSportsLiveProvider.getLiveMatches() }.orEmpty()
                } catch (e: Exception) {
                    Log.w("StreamsProvider", "Could not load live sports home category", e)
                    emptyList()
                }
            }
            val upcomingMatchesDeferred = async {
                try {
                    withTimeoutOrNull(2_500) { AkSportsLiveProvider.getUpcomingMatches() }.orEmpty()
                } catch (e: Exception) {
                    Log.w("StreamsProvider", "Could not load upcoming matches home category", e)
                    emptyList()
                }
            }
            val liveTvDeferred = async {
                try {
                    withTimeoutOrNull(2_500) { CdnLiveTvProvider.getHome() }.orEmpty()
                } catch (e: Exception) {
                    Log.w("StreamsProvider", "Could not load livestream home category", e)
                    emptyList()
                }
            }
            Triple(
                liveMatchesDeferred.await(),
                upcomingMatchesDeferred.await(),
                liveTvDeferred.await()
            )
        }

        if (liveMatches.isNotEmpty()) {
            categories += Category("Live Sports", liveMatches)
        }

        val liveTvCategory = liveTvCategories.firstOrNull {
            it.name == "Livestream" || it.name == "CDN Live Channels" || it.name == "Live Sports & TV"
        } ?: liveTvCategories
            .filter { it.name.contains("Sports", true) || it.name.contains("News", true) || it.name.contains("Entertainment", true) }
            .take(3)
            .takeIf { it.isNotEmpty() }
            ?.let { matches -> Category("Livestream", matches.flatMap { it.list }) }

        liveTvCategory?.let { category ->
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
            val sortedChannels = category.list
                .filterNot { item ->
                    val title = (item as? TvShow)?.title.orEmpty()
                    title.contains("Canal Premier", true) || title.contains("Canal Premier League", true)
                }
                .sortedBy { item ->
                    val title = (item as? TvShow)?.title.orEmpty()
                    priorityOrder.indexOfFirst { keys -> keys.any { title.contains(it, true) } }
                        .takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
            if (sortedChannels.isNotEmpty()) {
                categories += category.copy(name = "Livestream", list = sortedChannels)
            }
        }

        if (upcomingMatches.isNotEmpty()) {
            categories += Category("Upcoming Matches", upcomingMatches)
        }

        val (searchCategoriesOnHome, kenyanContent) = coroutineScope {
            val kenyanDeferred = async {
                runCatching { getKenyanSubjects(page = 1) }
                    .onFailure { Log.w("StreamsProvider", "Could not load Kenyan home category", it) }
                    .getOrDefault(emptyList())
            }
            val categoriesDeferred = async {
                searchCategories.values.map { category ->
                    async {
                        val items = try {
                            searchCategoryItems(category, 1)
                        } catch (e: Exception) {
                            Log.w("StreamsProvider", "Could not load ${category.name} home category", e)
                            emptyList()
                        }
                        category to items
                    }
                }.awaitAll()
            }
            categoriesDeferred.await() to kenyanDeferred.await()
        }
        if (kenyanContent.isNotEmpty()) {
            categories += Category(
                "Kenyan Series & Movies",
                kenyanContent.mapNotNull { subject ->
                    cacheSubject(subject)
                    subjectToAppItem(subject)
                }
            )
        }
        searchCategoriesOnHome.forEach { (category, items) ->
            if (categories.none { it.name == category.name } && items.isNotEmpty()) {
                categories += Category(category.name, items)
            }
        }

        if (categories.none { it.name == "Kids Banner" }) {
            val kidsItems = allItems.filter { item ->
                val title = when(item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }.lowercase()
                title.contains("kids") || title.contains("animation") || title.contains("family") || title.contains("cartoon")
            }
            if (kidsItems.isNotEmpty()) {
                categories.add(Category("Kids Banner", kidsItems.take(10)))
            }
        }
        if (categories.none { it.name == "Anime Banner" }) {
            val animeItems = allItems.filter { item ->
                val title = when(item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }.lowercase()
                title.contains("anime") || title.contains("naruto") || title.contains("dragon ball") || title.contains("one piece")
            }
            if (animeItems.isNotEmpty()) {
                categories.add(Category("Anime Banner", animeItems.take(10)))
            }
        }

        categories
    } catch (e: Exception) {
        Log.e("StreamsProvider", "Error in getHome", e)
        emptyList()
    }

    private suspend fun searchCategoryItems(category: SearchCategory, page: Int): List<AppAdapter.Item> =
        searchCategorySubjects(category, page).mapNotNull { subject ->
            cacheSubject(subject)
            subjectToAppItem(subject)
        }

    private suspend fun searchCategorySubjects(category: SearchCategory, page: Int): List<StreamsSubject> =
        api.search(category.query, page).items.orEmpty().filter { subject ->
            (category.subjectType == null || subject.typeCode == category.subjectType) &&
                (category.requiredTitle == null ||
                    subject.title?.contains(category.requiredTitle, ignoreCase = true) == true)
        }

    private suspend fun getTrendingSubjects(page: Int): List<StreamsSubject> {
        if (page == 1) {
            try {
                val home = api.getHome()
                val trending = home.operatingList.orEmpty()
                    .filter { section ->
                        section.title.orEmpty().contains("popular", ignoreCase = true) ||
                            section.title.orEmpty().contains("trending", ignoreCase = true)
                    }
                    .flatMap { section ->
                        (if (section.type == "BANNER") {
                            section.banner?.items.orEmpty().mapNotNull { it.subject }
                        } else {
                            section.subjects.orEmpty()
                        }).filter { it.typeCode in setOf(1, 2) }
                    }
                    .distinctBy { it.detailPath ?: it.subjectId }
                if (trending.isNotEmpty()) return trending
            } catch (e: Exception) {
                Log.w("StreamsProvider", "Could not load trending subjects from home", e)
            }
        }

        return coroutineScope {
            val movies = async {
                runCatching { api.getCatalog("movies", page).items.orEmpty() }
                    .onFailure { Log.w("StreamsProvider", "Could not load trending movies page $page", it) }
                    .getOrDefault(emptyList())
            }
            val series = async {
                runCatching { api.getCatalog("series", page).items.orEmpty() }
                    .onFailure { Log.w("StreamsProvider", "Could not load trending series page $page", it) }
                    .getOrDefault(emptyList())
            }
            (movies.await() + series.await())
                .filter { it.typeCode in setOf(1, 2) }
                .distinctBy { it.detailPath ?: it.subjectId }
        }
    }

    private suspend fun getKenyanSubjects(page: Int): List<StreamsSubject> = coroutineScope {
        listOf("nairobi", "kenya", "kenyan series").map { query ->
            async {
                try {
                    api.search(query, page).items.orEmpty()
                } catch (e: Exception) {
                    Log.w("StreamsProvider", "Kenyan search failed for \"$query\"", e)
                    emptyList()
                }
            }
        }.awaitAll()
            .flatten()
            .filter(::isVerifiedKenyanTitle)
            .distinctBy { it.detailPath ?: it.subjectId }
    }

    private fun isVerifiedKenyanTitle(subject: StreamsSubject): Boolean {
        if (subject.typeCode !in setOf(1, 2)) return false
        if (!subject.country.equals("Kenya", ignoreCase = true)) return false

        val title = subject.title.orEmpty().lowercase(Locale.US)
        val genres = subject.genres.orEmpty().joinToString(" ").lowercase(Locale.US) +
            " " + subject.genre.orEmpty().lowercase(Locale.US)
        val searchableText = "$title $genres"
        if (kenyanMediaExclusions.any(searchableText::contains)) return false
        if (genres.contains("music")) return false

        return subject.typeCode != 1 || subject.durationSeconds?.let { it >= 20 * 60 } != false
    }

    override suspend fun getMovies(page: Int): List<Movie> = try {
        api.getCatalog("movies", page).items.orEmpty().mapNotNull { subject ->
            cacheSubject(subject)
            subjectToMovie(subject)
        }
    } catch (e: Exception) {
        Log.e("StreamsProvider", "Error in getMovies", e)
        emptyList()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = try {
        api.getCatalog("series", page).items.orEmpty().mapNotNull { subject ->
            cacheSubject(subject)
            subjectToTvShow(subject)
        }
    } catch (e: Exception) {
        Log.e("StreamsProvider", "Error in getTvShows", e)
        emptyList()
    }

    suspend fun getShortTvSeries(page: Int = 1): List<TvShow> {
        val shortSubjects = mutableListOf<StreamsSubject>()
        try {
            shortSubjects += api.getBrowse("short_series", page).items.orEmpty()
                .filter { it.isShortSeries() }
        } catch (e: Exception) {
            Log.w("StreamsProvider", "Failed to browse short TV page $page", e)
        }

        if (shortSubjects.isEmpty() && page == 1) {
            try {
                api.getHome().operatingList.orEmpty().forEach { section ->
                    val subjects = if (section.type == "BANNER") {
                        section.banner?.items.orEmpty().mapNotNull { it.subject }
                    } else {
                        section.subjects.orEmpty()
                    }
                    shortSubjects += subjects.filter { it.isShortSeries() }
                }
            } catch (e: Exception) {
                Log.w("StreamsProvider", "Failed to load short TV from home", e)
            }
        }

        if (shortSubjects.isEmpty()) {
            try {
                shortSubjects += api.search("short", page).items.orEmpty()
                    .filter { it.isShortSeries() }
            } catch (e: Exception) {
                Log.w("StreamsProvider", "Failed to search short TV page $page", e)
            }
        }

        return shortSubjects.distinctBy { it.detailPath ?: it.subjectId }.mapNotNull { subject ->
            cacheSubject(subject)
            subjectToTvShow(subject)
        }
    }

    private fun StreamsSubject.isShortSeries(): Boolean =
        subjectType == 7 || kind.equals("short_series", ignoreCase = true)

    suspend fun getShortTvEpisodeCount(id: String): Int? =
        loadDetail(id).second.mapNotNull { it.maxEp }.sum().takeIf { it > 0 }

    suspend fun getShortTvEpisodePlayback(id: String, episode: Int = 1): StreamsShortEpisodePlayback? {
        require(episode >= 1) { "Short TV episode must be positive" }
        val streams = api.getPlayback(id, season = 1, episode = episode).streams.orEmpty()
            .filter { it.vipLocked != true && !it.url.isNullOrBlank() }
        val stream = streams.minWithOrNull(
            compareBy<StreamsStream> { item ->
                when {
                    item.format.equals("mp4", ignoreCase = true) -> 0
                    item.format.orEmpty().contains("hls", ignoreCase = true) ||
                        item.format.orEmpty().contains("m3u8", ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenBy { item ->
                val resolution = Regex("""\d{3,4}""")
                    .find(item.resolutions.orEmpty())
                    ?.value
                    ?.toIntOrNull()
                when {
                    resolution == null -> Int.MAX_VALUE
                    resolution <= 480 -> 480 - resolution
                    else -> 10_000 + resolution
                }
            }
        ) ?: return null
        val url = stream.url?.takeIf(String::isNotBlank) ?: return null

        val format = stream.format.orEmpty()
        val mimeType = when {
            format.contains("dash", ignoreCase = true) -> "application/dash+xml"
            format.contains("hls", ignoreCase = true) || format.contains("m3u8", ignoreCase = true) ->
                "application/x-mpegURL"
            else -> "video/mp4"
        }
        val headers = stream.requestHeaders.orEmpty().toMutableMap().apply {
            putIfAbsent("Referer", "https://themoviebox.xyz/")
            putIfAbsent(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }
        return StreamsShortEpisodePlayback(
            url = url,
            mimeType = mimeType,
            requestHeaders = headers
        )
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: SearchFilters?
    ): List<AppAdapter.Item> = try {
        val results = api.search(query, page).items.orEmpty().mapNotNull { subject ->
            cacheSubject(subject)
            subjectToAppItem(subject)
        }
        if (results.isNotEmpty()) {
            results
        } else {
            MovieBoxProvider.search(query, page, filters)
        }
    } catch (e: Exception) {
        Log.e("StreamsProvider", "Error in search", e)
        MovieBoxProvider.search(query, page, filters)
    }

    override suspend fun getMovie(id: String): Movie {
        val subject = loadDetail(id).first ?: return MovieBoxProvider.getMovie(id)
        return subjectToMovie(subject) ?: throw IllegalStateException("STREAMS movie has no usable identifier")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val detail = loadDetail(id)
        val subject = detail.first ?: return MovieBoxProvider.getTvShow(id)
        val detailPath = subject.detailPath?.takeIf(String::isNotBlank) ?: subject.subjectId ?: id
        val seasons = detail.second.mapNotNull { resource ->
            val seasonNumber = resource.se ?: return@mapNotNull null
            val episodes = (1..(resource.maxEp ?: 0)).map { episodeNumber ->
                Episode(
                    id = "${detailPath}_${seasonNumber}_$episodeNumber",
                    number = episodeNumber,
                    title = "Episode $episodeNumber"
                )
            }
            Season(
                id = "${detailPath}_$seasonNumber",
                number = seasonNumber,
                title = "Season $seasonNumber",
                episodes = episodes
            )
        }
        val showWithSeasons = subjectToTvShow(subject, seasons)
            ?: throw IllegalStateException("STREAMS series has no usable identifier")
        seasons.forEach { season ->
            season.tvShow = showWithSeasons
            season.episodes.forEach {
                it.tvShow = showWithSeasons
                it.season = season
            }
        }
        if (showWithSeasons.seasons.isEmpty()) return TmdbUtils.enrichTvShow(MovieBoxProvider.getTvShow(id))
        return TmdbUtils.enrichTvShow(showWithSeasons)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("_")
        if (parts.size < 2) return emptyList()
        val seasonNumber = parts[1].toIntOrNull() ?: return emptyList()
        return getTvShow(parts.first()).seasons.firstOrNull { it.number == seasonNumber }?.episodes.orEmpty()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val normalizedId = id.lowercase()
        if (normalizedId in setOf("short_tv", "short_series", "shorts")) {
            return Genre(id = id, name = "Short TV", shows = getShortTvSeries(page))
        }
        if (normalizedId in setOf("cdn_all_channels", "cdn_sports", "upcoming_matches")) {
            return NexaHomeProvider.getGenre(id, page)
        }

        return try {
        val categoryKey = when (normalizedId) {
            "kids", "family", "cartoon", "streams_kids" -> "kids"
            "anime", "streams_anime" -> "anime"
            else -> id.lowercase()
        }
        val searchQuery = normalizedId
            .takeIf { it.startsWith("streams_search:") }
            ?.substringAfter("streams_search:")
            ?.trim()
        
        val selectedSearchCategory = searchCategories[categoryKey]
        val rawSubjects = if (searchQuery != null) {
            api.search(searchQuery, page).items.orEmpty()
        } else if (selectedSearchCategory != null) {
            searchCategorySubjects(selectedSearchCategory, page)
        } else {
            when (categoryKey) {
                "streams_trending_today" -> getTrendingSubjects(page)
                "streams_kenyan_content" -> getKenyanSubjects(page)
                "streams_catalog_movies" -> api.getCatalog("movies", page).items.orEmpty()
                "streams_catalog_series" -> api.getCatalog("series", page).items.orEmpty()
                else -> {
                    val catalogItems = try {
                        api.getCatalog(categoryKey, page).items.orEmpty()
                    } catch (_: Exception) {
                        emptyList()
                    }

                    if (catalogItems.isNotEmpty()) catalogItems else try {
                        api.search(if (categoryKey == "kids") "kids cartoons" else categoryKey, page).items.orEmpty()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }

        val items = rawSubjects.mapNotNull { subject ->
            cacheSubject(subject)
            subjectToAppItem(subject)
        }

        val shows = if (items.isNotEmpty()) {
            items
        } else {
            val homeCategories = getHome()
            val allItems = homeCategories.flatMap { it.list }
            when (categoryKey) {
                "kids" -> allItems.filter { item ->
                    val title = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }.lowercase()
                    title.contains("kids") || title.contains("animation") || title.contains("family") || title.contains("cartoon") || title.contains("disney") || title.contains("lego")
                }
                "anime" -> allItems.filter { item ->
                    val title = when (item) { is Movie -> item.title; is TvShow -> item.title; else -> "" }.lowercase()
                    title.contains("anime") || title.contains("naruto") || title.contains("dragon ball") || title.contains("one piece") || title.contains("pokemon") || title.contains("attack on titan")
                }
                "streams_trending_today", "streams_kenyan_content" -> emptyList()
                else -> emptyList()
            }
        }

        Genre(
            id = id,
            name = selectedSearchCategory?.name ?: when (categoryKey) {
                    "kids" -> "Kids"
                    "anime" -> "Anime"
                    "streams_trending_today" -> "Trending Today"
                    "streams_kenyan_content" -> "Kenyan Series & Movies"
                    "streams_catalog_movies" -> "Movies"
                    "streams_catalog_series" -> "Series"
                    else -> searchQuery?.replaceFirstChar { it.uppercase() }
                        ?: id.replaceFirstChar { it.uppercase() }
                },
            shows = if (searchQuery != null ||
                selectedSearchCategory != null ||
                categoryKey == "streams_trending_today" ||
                categoryKey == "streams_kenyan_content" ||
                categoryKey == "streams_catalog_movies" ||
                categoryKey == "streams_catalog_series" ||
                normalizedId.startsWith("streams_")
            ) {
                shows
            } else {
                shows.ifEmpty { NexaHomeProvider.getGenre(id, page).shows }
            }
        )
        } catch (e: Exception) {
            Log.e("StreamsProvider", "Error in getGenre for $id", e)
            if (normalizedId.startsWith("streams_") || normalizedId.startsWith("streams_search:")) {
                Genre(
                    id = id,
                    name = when (normalizedId) {
                        "streams_trending_today" -> "Trending Today"
                        "streams_kenyan_content" -> "Kenyan Series & Movies"
                        else -> id.removePrefix("streams_search:")
                            .replaceFirstChar { it.uppercase() }
                    },
                    shows = emptyList()
                )
            } else {
                runCatching { NexaHomeProvider.getGenre(id, page) }
                    .getOrElse { Genre(id = id, name = id, shows = emptyList()) }
            }
        }

    }

    override suspend fun getPeople(id: String, page: Int): People = People(id = id, name = id)

    private suspend fun resolveTmdbVideoType(videoType: Video.Type): Video.Type? {
        return try {
            when (videoType) {
                is Video.Type.Episode -> {
                    val rawId = videoType.tvShow.id
                    if (rawId.toIntOrNull() != null) {
                        videoType
                    } else {
                        val rawTitle = videoType.tvShow.title
                        val cleanTitle = rawTitle.replace(Regex("""\s*\[.*?\]"""), "").trim()
                        val tmdbShow = TmdbUtils.getTvShow(cleanTitle.ifBlank { rawTitle })
                        if (tmdbShow != null) {
                            videoType.copy(
                                tvShow = videoType.tvShow.copy(
                                    id = tmdbShow.id,
                                    imdbId = tmdbShow.imdbId ?: videoType.tvShow.imdbId
                                )
                            )
                        } else null
                    }
                }
                is Video.Type.Movie -> {
                    val rawId = videoType.id
                    if (rawId.toIntOrNull() != null) {
                        videoType
                    } else {
                        val rawTitle = videoType.title
                        val cleanTitle = rawTitle.replace(Regex("""\s*\[.*?\]"""), "").trim()
                        val tmdbMovie = TmdbUtils.getMovie(cleanTitle.ifBlank { rawTitle })
                        if (tmdbMovie != null) {
                            videoType.copy(
                                id = tmdbMovie.id,
                                imdbId = tmdbMovie.imdbId ?: videoType.imdbId
                            )
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("StreamsProvider", "Failed to resolve TMDB videoType", e)
            null
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()

        val tmdbVideoType = resolveTmdbVideoType(videoType)

        // 1. Primary main server: VixSrc
        if (tmdbVideoType != null) {
            try {
                servers.add(VixSrcExtractor().server(tmdbVideoType))
            } catch (e: Exception) {
                Log.w("StreamsProvider", "Failed to add VixSrc server", e)
            }
        }

        // 2. Playback servers from STREAMS / MovieBox / Genga
        val path = when (videoType) {
            is Video.Type.Episode -> videoType.tvShow.id.takeIf(String::isNotBlank)
                ?: id.substringBefore("_")
            else -> id.substringBefore("_")
        }
        val (season, episode) = when (videoType) {
            is Video.Type.Episode -> videoType.season.number to videoType.number
            else -> 0 to 0
        }
        val playbackServers = try {
            val rawPlayback = api.getPlayback(path, season, episode)
            val playback = if (rawPlayback.streams.isNullOrEmpty() && season == 0 && episode == 0) {
                runCatching { api.getPlayback(path, 1, 1) }.getOrDefault(rawPlayback)
            } else {
                rawPlayback
            }
            playback.streams.orEmpty().mapNotNull { stream ->
                val url = stream.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (stream.vipLocked == true) return@mapNotNull null
                val subtitles = try {
                    api.getCaptions(path, stream.id, season, episode).mapNotNull { caption ->
                        val captionUrl = caption.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        Video.Subtitle(
                            label = caption.lanName ?: caption.lan ?: "Subtitle",
                            file = captionUrl,
                            language = caption.lan,
                            default = caption.lan?.startsWith("en", ignoreCase = true) == true
                        )
                    }
                } catch (e: Exception) {
                    Log.w("StreamsProvider", "Could not load captions for $path", e)
                    emptyList()
                }
                val resolution = stream.resolutions?.toIntOrNull()
                    ?: Regex("""\d{3,4}""").find(stream.resolutions.orEmpty())?.value?.toIntOrNull()
                val format = stream.format.orEmpty()
                val mimeType = if (format.contains("m3u8", true) || format.contains("hls", true)) {
                    "application/x-mpegURL"
                } else {
                    "video/mp4"
                }
                Video.Server(
                    id = "streams_${stream.id ?: url}_$resolution",
                    name = buildString {
                        if (resolution != null) append("${resolution}p")
                        else append("STREAMS")
                        if (format.isNotBlank()) append(" ($format)")
                    },
                    src = url
                ).apply {
                    video = Video(
                        source = url,
                        subtitles = subtitles,
                        headers = mapOf(
                            "Referer" to "https://themoviebox.xyz/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        ),
                        type = mimeType
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("StreamsProvider", "Playback API failed for $path; using MovieBox fallback", e)
            emptyList()
        }

        if (playbackServers.isNotEmpty()) {
            servers.addAll(playbackServers)
        } else {
            try {
                servers.addAll(MovieBoxProvider.getServers(path, videoType))
            } catch (e: Exception) {
                Log.w("StreamsProvider", "MovieBox fallback failed for $path", e)
            }
        }

        // 3. Fallback multi-embed servers
        if (tmdbVideoType != null) {
            try {
                servers.addAll(
                    listOf(
                        TwoEmbedExtractor().server(tmdbVideoType),
                        VidsrcNetExtractor().server(tmdbVideoType),
                        VidLinkExtractor().server(tmdbVideoType),
                        VidsrcRuExtractor().server(tmdbVideoType),
                        VidflixExtractor().server(tmdbVideoType),
                        VidsrcToExtractor().server(tmdbVideoType),
                        PrimeSrcExtractor().server(tmdbVideoType)
                    )
                )
            } catch (e: Exception) {
                Log.w("StreamsProvider", "Failed to add multi-embed fallbacks", e)
            }
        }

        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.video != null) return server.video!!
        val url = server.src.ifEmpty { server.id }
        return Extractor.extract(url, server)
    }

    suspend fun getDownloadServers(
        title: String,
        releaseDate: String?,
        videoType: Video.Type,
        contentId: String? = null
    ): List<Video.Server> {
        val expectedType = if (videoType is Video.Type.Episode) 2 else 1
        val directPath = contentId?.takeIf {
            it.isNotBlank() && it.toLongOrNull() == null && !it.contains("_")
        }
        if (directPath != null) {
            val directSubject = subjectCache[directPath] ?: try {
                api.getDetail(directPath).subject
            } catch (e: Exception) {
                Log.w("StreamsProvider", "Could not verify HOME identifier $directPath", e)
                null
            }
            if (directSubject != null &&
                directSubject.typeCode == expectedType &&
                (title.isBlank() || titlesMatch(title, releaseDate, directSubject))
            ) {
                cacheSubject(directSubject)
                val directVideoType = when (videoType) {
                    is Video.Type.Episode -> videoType.copy(tvShow = videoType.tvShow.copy(id = directPath))
                    else -> videoType
                }
                val directServers = getMovieBoxDownloadServersOnly(directPath, directVideoType)
                if (directServers.isNotEmpty()) return directServers
            }
        }
        val releaseYear = releaseDate?.take(4)?.toIntOrNull()

        val cached = subjectCache.values.filter { subject ->
            subject.typeCode == expectedType && (title.isBlank() || titlesMatch(title, releaseDate, subject))
        }
        val cachedMatch = chooseTitleMatch(cached, releaseYear, title)
        val subject = cachedMatch ?: findSubject(title, expectedType, releaseYear)
        val detailPath = subject.detailPath?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("STREAMS has no playable path for \"$title\"")
        cacheSubject(subject)

        val streamsType = when (videoType) {
            is Video.Type.Episode -> videoType.copy(tvShow = videoType.tvShow.copy(id = detailPath))
            else -> videoType
        }
        val servers = getMovieBoxDownloadServersOnly(detailPath, streamsType)
        if (servers.isEmpty()) throw IllegalStateException("STREAMS returned no playable sources for \"$title\"")
        return servers
    }

    /**
     * Retrieves download servers strictly from the Vercel MovieBox API endpoint (movie-box),
     * excluding VixSrc and fallback extractors.
     */
    private suspend fun getMovieBoxDownloadServersOnly(
        path: String,
        videoType: Video.Type
    ): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()

        val (season, episode) = when (videoType) {
            is Video.Type.Episode -> videoType.season.number to videoType.number
            else -> 0 to 0
        }

        val playbackServers = try {
            val rawPlayback = api.getPlayback(path, season, episode)
            val playback = if (rawPlayback.streams.isNullOrEmpty() && season == 0 && episode == 0) {
                runCatching { api.getPlayback(path, 1, 1) }.getOrDefault(rawPlayback)
            } else {
                rawPlayback
            }
            playback.streams.orEmpty().mapNotNull { stream ->
                val url = stream.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (stream.vipLocked == true) return@mapNotNull null
                val subtitles = try {
                    api.getCaptions(path, stream.id, season, episode).mapNotNull { caption ->
                        val captionUrl = caption.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        Video.Subtitle(
                            label = caption.lanName ?: caption.lan ?: "Subtitle",
                            file = captionUrl,
                            language = caption.lan,
                            default = caption.lan?.startsWith("en", ignoreCase = true) == true
                        )
                    }
                } catch (e: Exception) {
                    Log.w("StreamsProvider", "Could not load captions for $path", e)
                    emptyList()
                }
                val resolution = stream.resolutions?.toIntOrNull()
                    ?: Regex("""\d{3,4}""").find(stream.resolutions.orEmpty())?.value?.toIntOrNull()
                val format = stream.format.orEmpty()
                val mimeType = if (format.contains("m3u8", true) || format.contains("hls", true)) {
                    "application/x-mpegURL"
                } else {
                    "video/mp4"
                }
                Video.Server(
                    id = "streams_${stream.id ?: url}_$resolution",
                    name = buildString {
                        if (resolution != null) append("${resolution}p")
                        else append("STREAMS")
                        if (format.isNotBlank()) append(" ($format)")
                    },
                    src = url
                ).apply {
                    video = Video(
                        source = url,
                        subtitles = subtitles,
                        headers = mapOf(
                            "Referer" to "https://themoviebox.xyz/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        ),
                        type = mimeType
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("StreamsProvider", "Playback API failed for download on $path", e)
            emptyList()
        }

        if (playbackServers.isNotEmpty()) {
            servers.addAll(playbackServers)
        } else {
            try {
                servers.addAll(MovieBoxProvider.getServers(path, videoType))
            } catch (e: Exception) {
                Log.w("StreamsProvider", "MovieBox fallback failed for download on $path", e)
            }
        }

        return servers
    }

    private suspend fun findSubject(title: String, type: Int, releaseYear: Int?): StreamsSubject {
        val candidates = mutableListOf<StreamsSubject>()

        try {
            candidates += api.search(title, 1).items.orEmpty()
        } catch (e: Exception) {
            Log.w("StreamsProvider", "Search failed while resolving HOME download for $title", e)
        }

        if (                candidates.none {
            it.typeCode == type && titlesMatch(title, releaseYear?.toString(), it)
        }) {
            try {
                val home = api.getHome()
                home.operatingList.orEmpty().forEach { section ->
                    if (section.type == "BANNER") {
                        candidates += section.banner?.items.orEmpty().mapNotNull { it.subject }
                    } else {
                        candidates += section.subjects.orEmpty()
                    }
                }
            } catch (e: Exception) {
                Log.w("StreamsProvider", "Home lookup failed while resolving HOME download for $title", e)
            }
        }

        val titleMatches = candidates.filter {
            it.typeCode == type && titlesMatch(title, releaseYear?.toString(), it)
        }
        if (titleMatches.isEmpty()) {
            val category = if (type == 2) "tv" else "movies"
            for (page in 1..5) {
                val result = try {
                    api.getCatalog(category, page)
                } catch (e: Exception) {
                    Log.w("StreamsProvider", "Catalog lookup failed for $title (page $page)", e)
                    break
                }
                candidates += result.items.orEmpty()
                if (result.items.isNullOrEmpty() || result.nextPage == null) break
                val matches = candidates.filter {
                    it.typeCode == type && titlesMatch(title, releaseYear?.toString(), it)
                }
                if (matches.isNotEmpty()) break
            }
        }

        if (candidates.none {
                it.typeCode == type && titlesMatch(title, releaseYear?.toString(), it)
            }
        ) {
            try {
                val movieBoxResults = MovieBoxProvider.search(title, 1, null)
                candidates += movieBoxResults.mapNotNull { item ->
                    val show = item as? Show ?: return@mapNotNull null
                    val itemType = when (show) {
                        is Movie -> 1
                        is TvShow -> 2
                    }
                    if (itemType != type) return@mapNotNull null
                    StreamsSubject(
                        subjectId = show.id,
                        subjectType = itemType,
                        title = show.title,
                        releaseDate = show.released?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it.time) },
                        detailPath = show.id
                    )
                }
            } catch (e: Exception) {
                Log.w("StreamsProvider", "MovieBox lookup failed while resolving HOME download for $title", e)
            }
        }

        return chooseTitleMatch(
            candidates.filter {
                it.typeCode == type && titlesMatch(title, releaseYear?.toString(), it)
            },
            releaseYear,
            title
        ) ?: throw IllegalStateException("Could not match \"$title\" to a STREAMS title")
    }

    private fun chooseTitleMatch(
        matches: List<StreamsSubject>,
        releaseYear: Int?,
        title: String
    ): StreamsSubject? {
        if (matches.isEmpty()) return null
        if (releaseYear != null) {
            val yearMatches = matches.filter { it.releaseDate?.take(4)?.toIntOrNull() == releaseYear }
            if (yearMatches.size == 1) return yearMatches.first()
            if (yearMatches.size > 1) {
                throw IllegalStateException("Multiple STREAMS titles match \"$title\" ($releaseYear)")
            }
        }
        return matches.distinctBy { it.detailPath ?: it.subjectId }.singleOrNull()
            ?: throw IllegalStateException("Multiple STREAMS titles match \"$title\"")
    }

    private suspend fun loadDetail(id: String): Pair<StreamsSubject?, List<StreamsSeasonRes>> {
        val path = id.trim()
        val cached = subjectCache[path]
        return try {
            val detail = api.getDetail(path)
            val subject = detail.subject ?: cached
            if (subject != null) cacheSubject(subject)
            subject to detail.seasonResources
        } catch (e: Exception) {
            Log.w("StreamsProvider", "Failed to fetch detail for $path", e)
            cached to emptyList()
        }
    }

    private fun cacheSubject(subject: StreamsSubject) {
        val path = subject.detailPath?.takeIf(String::isNotBlank) ?: subject.subjectId
        if (!path.isNullOrBlank()) subjectCache[path] = subject
    }

    private fun subjectToAppItem(subject: StreamsSubject): AppAdapter.Item? =
        if (subject.isTv) subjectToTvShow(subject) else subjectToMovie(subject)

    private fun subjectToMovie(subject: StreamsSubject): Movie? {
        val id = subject.detailPath?.takeIf(String::isNotBlank) ?: subject.subjectId ?: return null
        return Movie(
            id = id,
            title = subject.title ?: id,
            overview = subject.description,
            released = subject.releaseDate,
            rating = subject.imdbRatingValue?.toDoubleOrNull(),
            poster = subject.posterImageUrl,
            banner = subject.backdropImageUrl,
            providerName = name
        )
    }

    private fun subjectToTvShow(subject: StreamsSubject, seasons: List<Season> = emptyList()): TvShow? {
        val id = subject.detailPath?.takeIf(String::isNotBlank) ?: subject.subjectId ?: return null
        val genres = subject.genreText.orEmpty().split(",").map(String::trim).filter(String::isNotEmpty)
            .map { Genre(id = it, name = it) }
        return TvShow(
            id = id,
            title = subject.title ?: id,
            overview = subject.description,
            released = subject.releaseDate,
            rating = subject.imdbRatingValue?.toDoubleOrNull(),
            poster = subject.posterImageUrl,
            banner = subject.backdropImageUrl,
            providerName = name,
            seasons = seasons,
            genres = genres
        )
    }

    private fun normalizeTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "")

    private fun titlesMatch(
        homeTitle: String,
        homeReleaseDate: String?,
        subject: StreamsSubject
    ): Boolean {
        if (normalizeTitle(homeTitle) != normalizeTitle(subject.title.orEmpty())) return false
        val homeYear = homeReleaseDate?.take(4)?.toIntOrNull() ?: return true
        val streamsYear = subject.releaseDate?.take(4)?.toIntOrNull() ?: return true
        return homeYear == streamsYear
    }
}