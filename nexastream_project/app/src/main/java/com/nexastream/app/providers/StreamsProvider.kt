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
        suspend fun addCatalogCategory(name: String, catalog: String) {
            if (categories.any { it.name == name }) return
            try {
                val items = api.getCatalog(catalog, 1).items.orEmpty()
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

        addCatalogCategory("STREAMS Kids", "kids")

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

        val searchCategoriesOnHome = coroutineScope {
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
            (category.subjectType == null || subject.subjectType == category.subjectType) &&
                (category.requiredTitle == null ||
                    subject.title?.contains(category.requiredTitle, ignoreCase = true) == true)
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
        api.getCatalog("tv", page).items.orEmpty().mapNotNull { subject ->
            cacheSubject(subject)
            subjectToTvShow(subject)
        }
    } catch (e: Exception) {
        Log.e("StreamsProvider", "Error in getTvShows", e)
        emptyList()
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
        if (id.lowercase() in setOf("cdn_all_channels", "cdn_sports", "upcoming_matches")) {
            return NexaHomeProvider.getGenre(id, page)
        }

        return try {
        val categoryKey = when (id.lowercase()) {
            "kids", "family", "cartoon", "streams_kids" -> "kids"
            "anime", "streams_anime" -> "anime"
            else -> id.lowercase()
        }
        
        val selectedSearchCategory = searchCategories[categoryKey]
        val rawSubjects = if (selectedSearchCategory != null) {
            searchCategorySubjects(selectedSearchCategory, page)
        } else {
            val catalogItems = try {
                api.getCatalog(categoryKey, page).items.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            if (catalogItems.isNotEmpty()) catalogItems else try {
                api.search(categoryKey, page).items.orEmpty()
            } catch (_: Exception) {
                emptyList()
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
                else -> if (selectedSearchCategory == null) allItems else emptyList()
            }
        }

        Genre(
            id = id,
            name = selectedSearchCategory?.name ?: when (categoryKey) {
                    "kids" -> "Kids"
                    "anime" -> "Anime"
                    else -> id.replaceFirstChar { it.uppercase() }
                },
            shows = if (selectedSearchCategory != null) {
                shows
            } else {
                shows.ifEmpty { NexaHomeProvider.getGenre(id, page).shows }
            }
        )
        } catch (e: Exception) {
            Log.e("StreamsProvider", "Error in getGenre for $id", e)
            runCatching { NexaHomeProvider.getGenre(id, page) }
                .getOrElse { Genre(id = id, name = id, shows = emptyList()) }
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
            val playback = api.getPlayback(path, season, episode)
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
                directSubject.subjectType == expectedType &&
                titlesMatch(title, releaseDate, directSubject)
            ) {
                cacheSubject(directSubject)
                val directVideoType = when (videoType) {
                    is Video.Type.Episode -> videoType.copy(tvShow = videoType.tvShow.copy(id = directPath))
                    else -> videoType
                }
                val directServers = getServers(directPath, directVideoType)
                if (directServers.isNotEmpty()) return directServers
            }
        }
        val releaseYear = releaseDate?.take(4)?.toIntOrNull()
        val normalizedTitle = normalizeTitle(title)

        val cached = subjectCache.values.filter { subject ->
            subject.subjectType == expectedType && titlesMatch(title, releaseDate, subject)
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
        val servers = getServers(detailPath, streamsType)
        if (servers.isEmpty()) throw IllegalStateException("STREAMS returned no playable sources for \"$title\"")
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
            it.subjectType == type && titlesMatch(title, releaseYear?.toString(), it)
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
            it.subjectType == type && titlesMatch(title, releaseYear?.toString(), it)
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
                    it.subjectType == type && titlesMatch(title, releaseYear?.toString(), it)
                }
                if (matches.isNotEmpty()) break
            }
        }

        if (candidates.none {
                it.subjectType == type && titlesMatch(title, releaseYear?.toString(), it)
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
                it.subjectType == type && titlesMatch(title, releaseYear?.toString(), it)
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
            subject to detail.resource?.seasons.orEmpty()
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
        if (subject.subjectType == 2) subjectToTvShow(subject) else subjectToMovie(subject)

    private fun subjectToMovie(subject: StreamsSubject): Movie? {
        val id = subject.detailPath?.takeIf(String::isNotBlank) ?: subject.subjectId ?: return null
        return Movie(
            id = id,
            title = subject.title ?: id,
            overview = subject.description,
            released = subject.releaseDate,
            rating = subject.imdbRatingValue?.toDoubleOrNull(),
            poster = subject.cover?.url,
            banner = subject.stills?.url ?: subject.cover?.url,
            providerName = name
        )
    }

    private fun subjectToTvShow(subject: StreamsSubject, seasons: List<Season> = emptyList()): TvShow? {
        val id = subject.detailPath?.takeIf(String::isNotBlank) ?: subject.subjectId ?: return null
        val genres = subject.genre.orEmpty().split(",").map(String::trim).filter(String::isNotEmpty)
            .map { Genre(id = it, name = it) }
        return TvShow(
            id = id,
            title = subject.title ?: id,
            overview = subject.description,
            released = subject.releaseDate,
            rating = subject.imdbRatingValue?.toDoubleOrNull(),
            poster = subject.cover?.url,
            banner = subject.stills?.url ?: subject.cover?.url,
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