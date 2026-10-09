package com.nexastream.app.providers

import com.nexastream.app.NexastreamApp
import android.content.pm.PackageManager
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.extractors.AfterDarkExtractor
import com.nexastream.app.extractors.Extractor
import com.nexastream.app.extractors.MoflixExtractor
import com.nexastream.app.extractors.MoviesapiExtractor
import com.nexastream.app.extractors.TwoEmbedExtractor
import com.nexastream.app.extractors.VidsrcNetExtractor
import com.nexastream.app.extractors.VidsrcToExtractor
import com.nexastream.app.extractors.VidzeeExtractor
import com.nexastream.app.extractors.VixSrcExtractor
import com.nexastream.app.extractors.MovieBoxExtractor
import com.nexastream.app.extractors.VidLinkExtractor
import com.nexastream.app.extractors.VidsrcRuExtractor
import com.nexastream.app.extractors.EinschaltenExtractor
import com.nexastream.app.extractors.FrembedExtractor
import com.nexastream.app.extractors.VidflixExtractor
import com.nexastream.app.extractors.VidrockExtractor
import com.nexastream.app.extractors.VideasyExtractor
import com.nexastream.app.extractors.PrimeSrcExtractor
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.TMDb3
import com.nexastream.app.utils.TMDb3.original
import com.nexastream.app.utils.TMDb3.w500
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.safeSubList
import android.util.Base64
import android.util.Log
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class TmdbProvider(override val language: String) : Provider {
    override val baseUrl: String
        get() = ""

    override val name = "TMDb ($language)"
    override val logo =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/Tmdb.new.logo.svg/1280px-Tmdb.new.logo.svg.png"

    fun mapMulti(multi: TMDb3.MultiItem): AppAdapter.Item? {
        return when (multi) {
            is TMDb3.Movie -> Movie(
                id = multi.id.toString(),
                title = multi.title,
                overview = multi.overview,
                released = multi.releaseDate,
                rating = multi.voteAverage.toDouble(),
                poster = multi.posterPath?.w500,
                banner = multi.backdropPath?.original,
            )

            is TMDb3.Tv -> TvShow(
                id = multi.id.toString(),
                title = multi.name,
                overview = multi.overview,
                released = multi.firstAirDate,
                rating = multi.voteAverage.toDouble(),
                poster = multi.posterPath?.w500,
                banner = multi.backdropPath?.original,
            )

            is TMDb3.Person -> People(
                id = multi.id.toString(),
                name = multi.name,
                image = multi.profilePath?.w500,
            )

            else -> null
        }
    }

    suspend fun getFeaturedMovies(): Category = coroutineScope {
        val results = TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, language = language).results
            .filter { it is TMDb3.Movie }
            .mapNotNull { mapMulti(it) }
        Category(name = "Featured Movies", list = results)
    }

    suspend fun getFeaturedTvShows(): Category = coroutineScope {
        val results = TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, language = language).results
            .filter { it is TMDb3.Tv }
            .mapNotNull { mapMulti(it) }
        Category(name = "Featured Series", list = results)
    }

    suspend fun getKidsContent(): Category = coroutineScope {
        val movieKids = async { 
            TMDb3.Discover.movie(
                language = language,
                withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(TMDb3.Genre.Movie.FAMILY.id).or(TMDb3.Genre.Movie.ANIMATION.id)
            ).results.mapNotNull { mapMulti(it) }
        }
        val tvKids = async {
            TMDb3.Discover.tv(
                language = language,
                withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Tv>(TMDb3.Genre.Tv.KIDS.id).or(TMDb3.Genre.Tv.FAMILY.id)
            ).results.mapNotNull { mapMulti(it) }
        }
        
        val combined = (movieKids.await() + tvKids.await()).shuffled()
        Category(name = "Kids & Family", list = combined)
    }

    suspend fun getCartoonMovies(): Category = coroutineScope {
        val results = TMDb3.Discover.movie(
            language = language,
            withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(TMDb3.Genre.Movie.ANIMATION.id),
            sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = "Cartoon Movies", list = results)
    }

    suspend fun getCartoonSeries(): Category = coroutineScope {
        val results = TMDb3.Discover.tv(
            language = language,
            withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Tv>(TMDb3.Genre.Tv.ANIMATION.id),
            sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = "Cartoon Series", list = results)
    }

    suspend fun getStudioContent(name: String, companyId: Int): Category = coroutineScope {
        val movies = async {
            TMDb3.Discover.movie(
                language = language,
                withCompanies = TMDb3.Params.WithBuilder<TMDb3.Company.CompanyId>(companyId),
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        val tv = async {
            TMDb3.Discover.tv(
                language = language,
                withCompanies = TMDb3.Params.WithBuilder<TMDb3.Company.CompanyId>(companyId),
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        val combined = (movies.await() + tv.await())
        val distinct = combined.distinctBy { item ->
            when (item) {
                is Movie -> "movie_${item.id}"
                is TvShow -> "tv_${item.id}"
                else -> item.hashCode().toString()
            }
        }
        Category(name = name, list = distinct)
    }

    suspend fun getKeywordContent(name: String, keywordId: Int): Category = coroutineScope {
        val movies = async {
            TMDb3.Discover.movie(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(keywordId),
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        val tv = async {
            TMDb3.Discover.tv(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(keywordId),
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        val combined = (movies.await() + tv.await())
        val distinct = combined.distinctBy { item ->
            when (item) {
                is Movie -> "movie_${item.id}"
                is TvShow -> "tv_${item.id}"
                else -> item.hashCode().toString()
            }
        }
        Category(name = name, list = distinct)
    }

    suspend fun getSearchContent(name: String, query: String): Category = coroutineScope {
        val results = TMDb3.Search.multi(
            query = query,
            language = language
        ).results.mapNotNull { mapMulti(it) }
        Category(name = name, list = results)
    }

    suspend fun getAnimeContent(): Category = coroutineScope {
        val results = TMDb3.Discover.tv(
            language = language,
            withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME)
        ).results.mapNotNull { mapMulti(it) }
        Category(name = "Anime Universe", list = results)
    }

    suspend fun getAnimeMovies(): Category = coroutineScope {
        val results = TMDb3.Discover.movie(
            language = language,
            withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME),
            sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = "Anime Movies", list = results)
    }

    suspend fun getJapaneseAnime(): Category = coroutineScope {
        val movies = async {
            TMDb3.Discover.movie(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME),
                withOriginCountry = TMDb3.Params.WithBuilder<String>("JP"),
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        val tv = async {
            TMDb3.Discover.tv(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME),
                withOriginCountry = TMDb3.Params.WithBuilder<String>("JP"),
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        val combined = (movies.await() + tv.await())
        val distinct = combined.distinctBy { item ->
            when (item) {
                is Movie -> "movie_${item.id}"
                is TvShow -> "tv_${item.id}"
                else -> item.hashCode().toString()
            }
        }
        Category(name = "Japanese Anime", list = distinct)
    }

    suspend fun getWesternAnime(): Category = coroutineScope {
        val movies = async {
            TMDb3.Discover.movie(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME),
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
            ).results.filter { it.originalLanguage != "ja" }.mapNotNull { mapMulti(it) }
        }
        val tv = async {
            TMDb3.Discover.tv(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME),
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
            ).results.filter { it.originalLanguage != "ja" }.mapNotNull { mapMulti(it) }
        }
        val combined = (movies.await() + tv.await())
        val distinct = combined.distinctBy { item ->
            when (item) {
                is Movie -> "movie_${item.id}"
                is TvShow -> "tv_${item.id}"
                else -> item.hashCode().toString()
            }
        }
        Category(name = "European & American Anime", list = distinct)
    }

    suspend fun getAnimeAge7to12(): Category = coroutineScope {
        val movies = async {
            TMDb3.Discover.movie(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME),
                withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(TMDb3.Genre.Movie.FAMILY.id),
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        val tv = async {
            TMDb3.Discover.tv(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME),
                withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Tv>(TMDb3.Genre.Tv.KIDS.id),
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        val combined = (movies.await() + tv.await())
        val distinct = combined.distinctBy { item ->
            when (item) {
                is Movie -> "movie_${item.id}"
                is TvShow -> "tv_${item.id}"
                else -> item.hashCode().toString()
            }
        }
        Category(name = "Age 7-12", list = distinct)
    }

    // --- NEW GENERIC METHODS ---
    suspend fun getGenreMovies(genreId: Int, name: String): Category = coroutineScope {
        val results = TMDb3.Discover.movie(
            language = language,
            withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(genreId),
            sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = name, list = results)
    }

    suspend fun getGenreTv(genreId: Int, name: String): Category = coroutineScope {
        val results = TMDb3.Discover.tv(
            language = language,
            withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Tv>(genreId),
            sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = name, list = results)
    }

    suspend fun getNetworkTv(networkId: Int, name: String): Category = coroutineScope {
        val results = TMDb3.Discover.tv(
            language = language,
            withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(networkId),
            sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = name, list = results)
    }

    suspend fun getWatchProviderMovies(providerId: Int, name: String): Category = coroutineScope {
        val results = TMDb3.Discover.movie(
            language = language,
            withWatchProviders = TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>(providerId),
            sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = name, list = results)
    }

    // --- NEW SPECIFIC METHODS ---
    suspend fun getLatestMovies(): Category = coroutineScope {
        val today = Calendar.getInstance()
        val results = TMDb3.Discover.movie(
            language = language,
            primaryReleaseDate = TMDb3.Params.Range(lte = today),
            sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = "Latest Movies", list = results)
    }

    suspend fun getAllCinema(): Category = coroutineScope {
        val today = Calendar.getInstance()
        val results = TMDb3.Discover.movie(
            language = language,
            withReleaseType = TMDb3.Params.WithBuilder<TMDb3.Movie.ReleaseType>(TMDb3.Movie.ReleaseType.THEATRICAL),
            primaryReleaseDate = TMDb3.Params.Range(lte = today),
            sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
        ).results.mapNotNull { mapMulti(it) }
        Category(name = "All Cinema", list = results)
    }

    suspend fun getNewSeasonsAndEpisodes(): Category = coroutineScope {
        val results = TMDb3.TvSeriesLists.airingToday(
            language = language
        ).results.mapNotNull { mapMulti(it) }
        Category(name = "New Season and Episode", list = results)
    }

    data class TeenRomanceCandidate(
        val item: TMDb3.MultiItem,
        val compositeScore: Double,
        val matrixPriority: Int,
        val tropeScore: Int,
        val glossyScore: Int,
        val popularity: Float,
        val voteAverage: Float,
        val franchiseKey: String
    )

    fun evaluateTeenRomanceCandidate(rawItem: TMDb3.MultiItem): TeenRomanceCandidate? {
        val genresIds = when (rawItem) {
            is TMDb3.Movie -> rawItem.genresIds
            is TMDb3.Tv -> rawItem.genresIds
            else -> return null
        }

        // Rule 1: Exclude Animation (Genre ID 16)
        if (genresIds.contains(16)) return null

        val dateStr = when (rawItem) {
            is TMDb3.Movie -> rawItem.releaseDate
            is TMDb3.Tv -> rawItem.firstAirDate
            else -> null
        }
        val year = dateStr?.take(4)?.toIntOrNull()

        val title = when (rawItem) {
            is TMDb3.Movie -> rawItem.title
            is TMDb3.Tv -> rawItem.name
            else -> ""
        }
        val overview = when (rawItem) {
            is TMDb3.Movie -> rawItem.overview
            is TMDb3.Tv -> rawItem.overview
            else -> ""
        }
        val text = "$title $overview".lowercase()

        // Category-scoped trope matching
        val romanceTropes = listOf(
            Pair(12, listOf("fake date", "fake dating", "pretend relationship", "pretend dating")),
            Pair(11, listOf("childhood friend", "best friend", "friends to lovers", "friendship turns")),
            Pair(11, listOf("rivalry", "heated rivalry", "rival", "figure skating", "hockey", "crew", "sports romance", "finding her edge")),
            Pair(10, listOf("enemies to lovers", "rivals", "opposites attract", "hate each other")),
            Pair(10, listOf("heartbreak high", "euphoria", "walter boys", "off campus", "xo, kitty", "xo kitty", "teen matchmaker")),
            Pair(9, listOf("love triangle", "between two lovers", "choose between", "torn between")),
            Pair(8, listOf("first love", "summer romance", "secret relationship", "forbidden love"))
        )

        val teenSettingTropes = listOf(
            Pair(8, listOf("prom", "homecoming", "makeover", "school dance", "high school", "boarding school", "college freshman", "coming of age", "hockey", "sports romance", "figure skating", "skating", "crew", "heated rivalry"))
        )

        var tropeScore = 0
        var romanceTropeMatches = 0
        for ((weight, patterns) in romanceTropes) {
            if (patterns.any { text.contains(it) }) {
                tropeScore += weight
                romanceTropeMatches++
            }
        }

        var teenTropeMatches = 0
        for ((weight, patterns) in teenSettingTropes) {
            if (patterns.any { text.contains(it) }) {
                tropeScore += weight
                teenTropeMatches++
            }
        }

        val glossySignals = listOf("party", "popular", "dare", "school", "summer", "music", "dance", "secret", "fashion", "wedding", "comedy", "teen", "rivalry", "hockey", "skating")
        val glossyScore = glossySignals.distinct().count { text.contains(it) }

        // Dual Evidence Checking: Requires BOTH Romantic Content AND Teen/YA Setting
        val hasRomanceGenre = genresIds.contains(10749)
        val romanceTextKeywords = listOf("love", "romance", "dating", "crush", "crushes", "kiss", "kissing", "relationship", "boyfriend", "girlfriend", "lovers", "matchmaker", "heartbreaker", "fall in love", "falls in love", "falling in love", "catches the eye", "romantic", "affection", "couple", "rivalry", "heated rivalry", "walter boys", "xo, kitty", "xo kitty", "heartbreak high", "finding her edge", "crew girl")
        val hasRomanceText = romanceTextKeywords.any { text.contains(it) } || romanceTropeMatches > 0
        val hasRomanceEvidence = hasRomanceGenre || hasRomanceText

        val teenTextKeywords = listOf("teen", "teens", "teenager", "teenagers", "high school", "boarding school", "prep school", "school", "prom", "homecoming", "coming of age", "youth", "student", "students", "college", "freshman", "young", "young adult", "crush", "first love", "summer vacation", "summer romance", "girl", "boy", "girls", "boys", "euphoria", "heartbreak high", "off campus", "sterling point", "the shards", "the map of longing", "every year after")
        val hasTeenText = teenTextKeywords.any { text.contains(it) } || teenTropeMatches > 0
        val hasTeenEvidence = hasTeenText

        val hasMetadata = title.isNotEmpty() || overview.isNotEmpty() || genresIds.isNotEmpty()
        if (hasMetadata && (!hasRomanceEvidence || !hasTeenEvidence)) {
            return null
        }

        // Classic Titles Exception Check (pre-2012)
        val voteCount = when (rawItem) {
            is TMDb3.Movie -> rawItem.voteCount
            is TMDb3.Tv -> rawItem.voteCount
            else -> 0
        }
        val voteAverage = when (rawItem) {
            is TMDb3.Movie -> rawItem.voteAverage
            is TMDb3.Tv -> rawItem.voteAverage
            else -> 0f
        }

        val isClassicCandidate = year != null && year < 2012
        if (isClassicCandidate) {
            val hasStrongAcclaim = voteCount >= 500 || (voteAverage >= 7.0f && voteCount >= 200)
            val hasStrongTeenRomanceEvidence = (romanceTropeMatches >= 1 || (hasRomanceGenre && hasRomanceText)) && (teenTropeMatches >= 1 || hasTeenText)
            if (!hasStrongAcclaim || !hasStrongTeenRomanceEvidence) {
                return null
            }
        }

        val popularity = when (rawItem) {
            is TMDb3.Movie -> rawItem.popularity
            is TMDb3.Tv -> rawItem.popularity
            else -> 0f
        }

        val safeYear = year ?: 2020
        val recentReleaseBoost = when {
            safeYear >= 2024 -> 6.0
            safeYear >= 2021 -> 3.0
            else -> 0.0
        }

        val romanceBonus = if (hasRomanceGenre) 15.0 else (if (hasRomanceText) 8.0 else 0.0)
        val teenRelevance = if (hasTeenText) 15.0 else 5.0

        val normalizedPopularity = kotlin.math.min(kotlin.math.log10(popularity.toDouble() + 1.0) * 5.0, 15.0)
        val ratingBonus = if (voteCount >= 20) (voteAverage.toDouble() / 10.0) * 10.0 else 0.0

        val compositeScore = (tropeScore.toDouble() * 1.5) +
                (glossyScore.toDouble() * 0.8) +
                romanceBonus +
                teenRelevance +
                normalizedPopularity +
                ratingBonus +
                recentReleaseBoost

        // Vibe Priority Matrix:
        // Priority 1: Core Standard YA / Glossy Romance (Primary)
        // Priority 2: Atmospheric / Scenic
        // Priority 3: Elite / Ambition
        // Priority 4: Dark / Gritty / Mystery
        val isDarkGritty = text.contains("neon") || text.contains("gritty") || text.contains("mystery") || text.contains("dark")
        val isEliteAmbition = text.contains("sports") || text.contains("boarding-school") || text.contains("academy") || text.contains("ambition")
        val isAtmosphericScenic = text.contains("coastal") || text.contains("beach") || text.contains("island")

        val matrixPriority = when {
            isDarkGritty -> 4
            isEliteAmbition -> 3
            isAtmosphericScenic -> 2
            else -> 1
        }

        val franchiseKey = extractFranchiseKey(rawItem, title)

        return TeenRomanceCandidate(
            item = rawItem,
            compositeScore = compositeScore,
            matrixPriority = matrixPriority,
            tropeScore = tropeScore,
            glossyScore = glossyScore,
            popularity = popularity,
            voteAverage = voteAverage,
            franchiseKey = franchiseKey
        )
    }

    private fun extractFranchiseKey(item: TMDb3.MultiItem, title: String): String {
        val cleanTitle = title.lowercase()
            .replace(Regex("[:\\-–—].*"), "")
            .replace(Regex("\\b(1|2|3|4|5|i|ii|iii|iv|v|chapter|part)\\b"), "")
            .trim()
        return if (cleanTitle.length >= 4) cleanTitle else title.lowercase().trim()
    }

    suspend fun getTeenRomance(isMovie: Boolean, page: Int = 1, name: String = "Teen Romance"): Category = coroutineScope {
        val targetPage = if (page < 1) 1 else page
        val rawResults = if (isMovie) {
            val p1 = async { runCatching { TMDb3.Discover.movie(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(14534), page = targetPage, sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p2 = async { runCatching { TMDb3.Discover.movie(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(14534), page = targetPage + 1, sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p3 = async { runCatching { TMDb3.Discover.movie(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(9840), withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(10749), page = targetPage, sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p4 = async { runCatching { TMDb3.Discover.movie(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(11108), withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(10749), page = targetPage, sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p5 = async { runCatching { TMDb3.Discover.movie(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(158718), page = targetPage, sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p6 = async { runCatching { TMDb3.Discover.movie(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(209090), page = targetPage, sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val seeds = if (targetPage == 1) async { runCatching { listOf("The Kissing Booth", "To All the Boys", "Through My Window", "Culpa Mia", "Purple Hearts", "The Tearsmith", "Finding Her Edge").flatMap { TMDb3.Search.multi(query = it, language = language).results }.filterIsInstance<TMDb3.Movie>() }.getOrElse { emptyList() } } else null
            p1.await() + p2.await() + p3.await() + p4.await() + p5.await() + p6.await() + (seeds?.await() ?: emptyList())
        } else {
            val p1 = async { runCatching { TMDb3.Discover.tv(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(14534), page = targetPage, sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p2 = async { runCatching { TMDb3.Discover.tv(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(14534), page = targetPage + 1, sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p3 = async { runCatching { TMDb3.Discover.tv(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(9840), page = targetPage, sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p4 = async { runCatching { TMDb3.Discover.tv(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(11108), page = targetPage, sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p5 = async { runCatching { TMDb3.Discover.tv(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(209090), page = targetPage, sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val p6 = async { runCatching { TMDb3.Discover.tv(language = language, withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(10229), page = targetPage, sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC).results }.getOrElse { emptyList() } }
            val seeds = if (targetPage == 1) async { runCatching { listOf("Walter Boys", "XO Kitty", "Euphoria", "Heartbreak High", "Off Campus", "Heated Rivalry", "Finding Her Edge", "Crew Girl", "The Shards", "Sterling Point", "The Map of Longing", "Elle", "Every Year After").flatMap { TMDb3.Search.multi(query = it, language = language).results }.filterIsInstance<TMDb3.Tv>() }.getOrElse { emptyList() } } else null
            p1.await() + p2.await() + p3.await() + p4.await() + p5.await() + p6.await() + (seeds?.await() ?: emptyList())
        }

        android.util.Log.d("TmdbTeenRomance", "[$name] Stage 1: Raw TMDb items fetched = ${rawResults.size}")

        val rawItems = rawResults.distinctBy { 
            when (it) {
                is TMDb3.Movie -> it.id
                is TMDb3.Tv -> it.id
                else -> 0
            }
        }
        android.util.Log.d("TmdbTeenRomance", "[$name] Stage 2: Deduplicated by TMDb ID = ${rawItems.size}")

        val candidates = rawItems.mapNotNull { evaluateTeenRomanceCandidate(it) }
        android.util.Log.d("TmdbTeenRomance", "[$name] Stages 3-5: Evidence & Eligibility Filtered Candidates = ${candidates.size}")

        val sortedCandidates = candidates.sortedWith(
            compareByDescending<TeenRomanceCandidate> { it.compositeScore }
                .thenBy { it.matrixPriority }
                .thenByDescending { it.tropeScore }
                .thenByDescending { it.voteAverage }
                .thenByDescending { it.popularity }
                .thenBy { 
                    when (val raw = it.item) {
                        is TMDb3.Movie -> raw.id.toString()
                        is TMDb3.Tv -> raw.id.toString()
                        else -> ""
                    }
                }
        )
        android.util.Log.d("TmdbTeenRomance", "[$name] Stage 6: Ranked Candidates = ${sortedCandidates.size}")

        val franchiseCounts = mutableMapOf<String, Int>()
        val filteredList = mutableListOf<TMDb3.MultiItem>()

        for (candidate in sortedCandidates) {
            val key = candidate.franchiseKey
            val count = franchiseCounts.getOrDefault(key, 0)
            if (count < 2) {
                franchiseCounts[key] = count + 1
                filteredList.add(candidate.item)
            }
        }
        android.util.Log.d("TmdbTeenRomance", "[$name] Stage 7: Final Franchise Capped Count = ${filteredList.size}")

        val results = filteredList.mapNotNull { mapMulti(it) }
        Category(name = name, list = results)
    }

    suspend fun getBiography(isMovie: Boolean, name: String = "Biography"): Category = coroutineScope {
        val results = if (isMovie) {
            TMDb3.Discover.movie(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(5565), // Biography keyword
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        } else {
            TMDb3.Discover.tv(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(5565),
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        Category(name = name, list = results)
    }

    suspend fun getSport(isMovie: Boolean, name: String = "Sport"): Category = coroutineScope {
        val results = if (isMovie) {
            TMDb3.Discover.movie(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(6075), // Sport keyword
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        } else {
            TMDb3.Discover.tv(
                language = language,
                withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(6075),
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
            ).results.mapNotNull { mapMulti(it) }
        }
        Category(name = name, list = results)
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val watchRegion = UserPreferences.selectedRegion
        val isTv = try { 
            NexastreamApp.instance.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) 
        } catch (_: Exception) { false }

        val trendingDeferred = async {
            if (isTv) {
                TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language).results
            } else {
                awaitAll(
                    async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language) },
                    async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 2, language = language) },
                    async { TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 3, language = language) },
                ).flatMap { it.results }
            }
        }

        val popularMoviesDeferred = async {
            if (isTv) {
                TMDb3.MovieLists.popular(page = 1, language = language).results
            } else {
                awaitAll(
                    async { TMDb3.MovieLists.popular(page = 1, language = language) },
                    async { TMDb3.MovieLists.popular(page = 2, language = language) },
                    async { TMDb3.MovieLists.popular(page = 3, language = language) },
                ).flatMap { it.results }
            }
        }

        val popularTvShowsDeferred = async {
            if (isTv) {
                TMDb3.TvSeriesLists.popular(page = 1, language = language).results
            } else {
                awaitAll(
                    async { TMDb3.TvSeriesLists.popular(page = 1, language = language) },
                    async { TMDb3.TvSeriesLists.popular(page = 2, language = language) },
                    async { TMDb3.TvSeriesLists.popular(page = 3, language = language) },
                ).flatMap { it.results }
            }
        }

        val popularAnimeDeferred = async {
            if (isTv) {
                TMDb3.Discover.movie(
                    language = language,
                    withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME)
                        .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                ).results
            } else {
                awaitAll(
                    async {
                        TMDb3.Discover.movie(
                            language = language,
                            withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME)
                                .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                        )
                    },
                    async {
                        TMDb3.Discover.tv(
                            language = language,
                            withKeywords = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(TMDb3.Keyword.KeywordId.ANIME)
                                .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                        )
                    },
                ).flatMap { it.results }
            }
        }

        val netflixDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>(TMDb3.Provider.WatchProviderId.NETFLIX),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(TMDb3.Network.NetworkId.NETFLIX),
                    )
                },
            ).flatMap { it.results }
        }

        val amazonDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>(TMDb3.Provider.WatchProviderId.AMAZON_VIDEO),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(TMDb3.Network.NetworkId.AMAZON),
                    )
                },
            ).flatMap { it.results }
        }

        val disneyDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>(TMDb3.Provider.WatchProviderId.DISNEY_PLUS),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(TMDb3.Network.NetworkId.DISNEY_PLUS),
                    )
                },
            ).flatMap { it.results }
        }

        val huluDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>(TMDb3.Provider.WatchProviderId.HULU),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(TMDb3.Network.NetworkId.HULU),
                    )
                },
            ).flatMap { it.results }
        }

        val appleDeferred = async {
            awaitAll(
                async {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder<TMDb3.Provider.WatchProviderId>(TMDb3.Provider.WatchProviderId.APPLE_TV_PLUS),
                    )
                },
                async {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(TMDb3.Network.NetworkId.APPLE_TV),
                    )
                },
            ).flatMap { it.results }
        }

        val hboDeferred = async {
            if (isTv) {
                TMDb3.Discover.tv(
                    language = language,
                    withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(TMDb3.Network.NetworkId.HBO),
                    page = 1,
                ).results
            } else {
                awaitAll(
                    async {
                        TMDb3.Discover.tv(
                            language = language,
                            withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(TMDb3.Network.NetworkId.HBO),
                            page = 1,
                        )
                    },
                    async {
                        TMDb3.Discover.tv(
                            language = language,
                            withNetworks = TMDb3.Params.WithBuilder<TMDb3.Network.NetworkId>(TMDb3.Network.NetworkId.HBO),
                            page = 2,
                        )
                    },
                ).flatMap { it.results }
            }
        }

        val trending = trendingDeferred.await()
        categories.add(
            Category(
                name = Category.FEATURED,
                list = trending.safeSubList(0, 5).mapNotNull { mapMulti(it) }
            )
        )

        categories.add(
            Category(
                name = getTranslation("Trending"),
                list = trending.safeSubList(10, trending.size).mapNotNull { mapMulti(it) }
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular Movies"),
                list = popularMoviesDeferred.await().mapNotNull { mapMulti(it) }
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular TV Shows"),
                list = popularTvShowsDeferred.await().mapNotNull { mapMulti(it) }
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular Anime"),
                list = popularAnimeDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull { mapMulti(it) },
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Netflix"),
                list = netflixDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull { mapMulti(it) },
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Amazon"),
                list = amazonDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull { mapMulti(it) },
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Disney+"),
                list = disneyDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull { mapMulti(it) },
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Hulu"),
                list = huluDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull { mapMulti(it) },
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on Apple TV+"),
                list = appleDeferred.await()
                    .sortedByDescending {
                        when (it) {
                            is TMDb3.Movie -> it.popularity
                            is TMDb3.Person -> it.popularity
                            is TMDb3.Tv -> it.popularity
                        }
                    }
                    .mapNotNull { mapMulti(it) },
            )
        )

        categories.add(
            Category(
                name = getTranslation("Popular on HBO"),
                list = hboDeferred.await().mapNotNull { mapMulti(it) },
            )
        )

        categories
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isEmpty() && (filters == null || filters.isDefault())) {
            val genres = listOf(
                TMDb3.Genres.movieList(language = language),
                TMDb3.Genres.tvList(language = language),
            ).flatMap { it.genres }
                .distinctBy { it.id }
                .sortedBy { it.name }
                .map {
                    Genre(
                        id = it.id.toString(),
                        name = it.name,
                    )
                }

            return genres
        }

        if (filters != null && !filters.isDefault()) {
            val results = mutableListOf<AppAdapter.Item>()
            coroutineScope {
                val movieResults = if (filters.mediaType == SearchFilters.MediaType.ALL || filters.mediaType == SearchFilters.MediaType.MOVIES) {
                    async {
                        TMDb3.Discover.movie(
                            language = language,
                            page = page,
                            year = filters.year,
                            sortBy = when (filters.sortBy) {
                                SearchFilters.SortBy.POPULARITY_DESC -> TMDb3.Params.SortBy.Movie.POPULARITY_DESC
                                SearchFilters.SortBy.POPULARITY_ASC -> TMDb3.Params.SortBy.Movie.POPULARITY_ASC
                                SearchFilters.SortBy.VOTE_AVERAGE_DESC -> TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC
                                SearchFilters.SortBy.VOTE_AVERAGE_ASC -> TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_ASC
                                SearchFilters.SortBy.RELEASE_DATE_DESC -> TMDb3.Params.SortBy.Movie.RELEASE_DATE_DESC
                                SearchFilters.SortBy.RELEASE_DATE_ASC -> TMDb3.Params.SortBy.Movie.RELEASE_DATE_ASC
                            },
                            voteAverage = filters.voteAverageGte?.let { TMDb3.Params.Range(gte = it) },
                            withGenres = if (filters.genres.isNotEmpty()) TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(filters.genres.joinToString(",")) else null
                        ).results.map { movie ->
                            Movie(
                                id = movie.id.toString(),
                                title = movie.title,
                                overview = movie.overview,
                                released = movie.releaseDate,
                                rating = movie.voteAverage.toDouble(),
                                poster = movie.posterPath?.w500,
                                banner = movie.backdropPath?.original,
                            )
                        }
                    }
                } else null

                val tvResults = if (filters.mediaType == SearchFilters.MediaType.ALL || filters.mediaType == SearchFilters.MediaType.TV_SHOWS) {
                    async {
                        TMDb3.Discover.tv(
                            language = language,
                            page = page,
                            firstAirDateYear = filters.year,
                            sortBy = when (filters.sortBy) {
                                SearchFilters.SortBy.POPULARITY_DESC -> TMDb3.Params.SortBy.Tv.POPULARITY_DESC
                                SearchFilters.SortBy.POPULARITY_ASC -> TMDb3.Params.SortBy.Tv.POPULARITY_ASC
                                SearchFilters.SortBy.VOTE_AVERAGE_DESC -> TMDb3.Params.SortBy.Tv.VOTE_AVERAGE_DESC
                                SearchFilters.SortBy.VOTE_AVERAGE_ASC -> TMDb3.Params.SortBy.Tv.VOTE_AVERAGE_ASC
                                SearchFilters.SortBy.RELEASE_DATE_DESC -> TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC
                                SearchFilters.SortBy.RELEASE_DATE_ASC -> TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_ASC
                            },
                            voteAverage = filters.voteAverageGte?.let { TMDb3.Params.Range(gte = it) },
                            withGenres = if (filters.genres.isNotEmpty()) TMDb3.Params.WithBuilder<TMDb3.Genre.Tv>(filters.genres.joinToString(",")) else null
                        ).results.map { tv ->
                            TvShow(
                                id = tv.id.toString(),
                                title = tv.name,
                                overview = tv.overview,
                                released = tv.firstAirDate,
                                rating = tv.voteAverage.toDouble(),
                                poster = tv.posterPath?.w500,
                                banner = tv.backdropPath?.original,
                            )
                        }
                    }
                } else null

                movieResults?.await()?.let { results.addAll(it) }
                tvResults?.await()?.let { results.addAll(it) }
            }
            return results.sortedByDescending { (it as? Movie)?.rating ?: (it as? TvShow)?.rating ?: 0.0 }
        }

        val results = TMDb3.Search.multi(query, page = page, language = language).results.mapNotNull { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                )

                is TMDb3.Person -> People(
                    id = multi.id.toString(),
                    name = multi.name,
                    image = multi.profilePath?.w500,
                )

                else -> null
            }
        }

        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val movies = TMDb3.MovieLists.popular(page = page, language = language).results.map { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.w500,
                banner = movie.backdropPath?.original,
            )
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val tvShows = TMDb3.TvSeriesLists.popular(page = page, language = language).results.map { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w500,
                banner = tv.backdropPath?.original,
            )
        }

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie {
        val movieId = id.toIntOrNull() ?: throw Exception("Invalid Movie ID: $id")
        val movie = TMDb3.Movies.details(
            movieId = movieId,
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Movie.CREDITS,
                TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
            ),
            language = language
        ).let { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                runtime = movie.runtime,
                trailer = movie.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.original,
                banner = movie.backdropPath?.original,
                imdbId = movie.externalIds?.imdbId,

                genres = movie.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = movie.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = movie.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        else -> null
                    }
                } ?: listOf(),
            )
        }

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        val seriesId = id.toIntOrNull() ?: throw Exception("Invalid TV Show ID: $id")
        val tvShow = TMDb3.TvSeries.details(
            seriesId = seriesId,
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Tv.CREDITS,
                TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
            ),
            language = language
        ).let { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.original,
                banner = tv.backdropPath?.original,
                imdbId = tv.externalIds?.imdbId,

                seasons = tv.seasons.map { season ->
                    Season(
                        id = "${tv.id}-${season.seasonNumber}",
                        number = season.seasonNumber,
                        title = season.name,
                        poster = season.posterPath?.w500,
                    )
                },
                genres = tv.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = tv.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                } ?: listOf(),
                recommendations = tv.recommendations?.results?.mapNotNull { multi ->
                    when (multi) {
                        is TMDb3.Movie -> Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        is TMDb3.Tv -> TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                        )

                        else -> null
                    }
                } ?: listOf(),
            ).apply {
                seasons.forEach { it.tvShow = this }
            }
        }

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (tvShowId, seasonNumber) = seasonId.split("-")

        val episodes = TMDb3.TvSeasons.details(
            seriesId = tvShowId.toInt(),
            seasonNumber = seasonNumber.toInt(),
            language = language
        ).episodes?.map {
            Episode(
                id = it.id.toString(),
                number = it.episodeNumber,
                title = it.name ?: "",
                released = it.airDate,
                poster = it.stillPath?.w500,
            )
        } ?: listOf()

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        fun <T> List<T>.mix(other: List<T>): List<T> {
            return sequence {
                val first = iterator()
                val second = other.iterator()
                while (first.hasNext() && second.hasNext()) {
                    yield(first.next())
                    yield(second.next())
                }

                yieldAll(first)
                yieldAll(second)
            }.toList()
        }

        val genre = Genre(
            id = id,
            name = "",

            shows = TMDb3.Discover.movie(
                page = page,
                withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Movie>(id),
                language = language
            ).results.map { movie ->
                Movie(
                    id = movie.id.toString(),
                    title = movie.title,
                    overview = movie.overview,
                    released = movie.releaseDate,
                    rating = movie.voteAverage.toDouble(),
                    poster = movie.posterPath?.w500,
                    banner = movie.backdropPath?.original,
                )
            }.mix(TMDb3.Discover.tv(
                page = page,
                withGenres = TMDb3.Params.WithBuilder<TMDb3.Genre.Tv>(id),
                language = language
            ).results.map { tv ->
                TvShow(
                    id = tv.id.toString(),
                    title = tv.name,
                    overview = tv.overview,
                    released = tv.firstAirDate,
                    rating = tv.voteAverage.toDouble(),
                    poster = tv.posterPath?.w500,
                    banner = tv.backdropPath?.original,
                )
            })
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val people = TMDb3.People.details(
            personId = id.toInt(),
            appendToResponse = listOfNotNull(
                if (page > 1) null else TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS,
            ),
            language = language
        ).let { person ->
            People(
                id = person.id.toString(),
                name = person.name,
                image = person.profilePath?.w500,
                biography = person.biography,
                placeOfBirth = person.placeOfBirth,
                birthday = person.birthday,
                deathday = person.deathday,

                filmography = person.combinedCredits?.cast
                    ?.mapNotNull { multi ->
                        when (multi) {
                            is TMDb3.Movie -> Movie(
                                id = multi.id.toString(),
                                title = multi.title,
                                overview = multi.overview,
                                released = multi.releaseDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w500,
                                banner = multi.backdropPath?.original,
                            )

                            is TMDb3.Tv -> TvShow(
                                id = multi.id.toString(),
                                title = multi.name,
                                overview = multi.overview,
                                released = multi.firstAirDate,
                                rating = multi.voteAverage.toDouble(),
                                poster = multi.posterPath?.w500,
                                banner = multi.backdropPath?.original,
                            )

                        else -> null
                    }
                }
                    ?.sortedBy {
                        when (it) {
                            is Movie -> it.released
                            is TvShow -> it.released
                        }
                    }
                    ?.reversed()
                    ?: listOf()
            )
        }

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val lang = language.lowercase().substringBefore("-")

        Log.d("TmdbProvider", "getServers: lang=$language, simplifiedLang=$lang")

        // 0. Always add MovieBox first as primary default provider
        try {
            servers.add(MovieBoxExtractor().server(videoType))
        } catch (e: Exception) {
            Log.e("TmdbProvider", "Failed to add MovieBox: ${e.message}")
        }

        // Always add VixSrc as fallback
        try {
            servers.add(VixSrcExtractor().server(videoType))
        } catch (e: Exception) {
            Log.e("TmdbProvider", "Failed to add VixSrc: ${e.message}")
        }

        when (lang) {
            "it" -> {
                // Already added VixSrc
            }
            "de" -> {
                // Solo server tedeschi
                servers.addAll(MoflixExtractor().servers(videoType))
                if (videoType is Video.Type.Movie) {
                    servers.add(EinschaltenExtractor().server(videoType))
                }
                VideasyExtractor().server(videoType, language)?.let { servers.add(it) }
            }
            "fr" -> {
                // Solo server francesi
                servers.addAll(FrembedExtractor(UserPreferences.getProviderCache(FrembedProvider, UserPreferences.PROVIDER_URL)).servers(videoType))
                // servers.addAll(AfterDarkExtractor(UserPreferences.getProviderCache(AfterDarkProvider, UserPreferences.PROVIDER_URL)).servers(videoType))
            }
            "es" -> {
                // TMDB Spagnolo: Utilizza ESCLUSIVAMENTE server certificati con audio spagnolo ([LAT] o [CAST])
                
                val targetTitle = when (videoType) {
                    is Video.Type.Movie -> videoType.title
                    is Video.Type.Episode -> videoType.tvShow.title
                }
                
                Log.i("NexaStream", "[SEARCH START] -> Target: $targetTitle (${if (videoType is Video.Type.Movie) "Movie" else "TV Show"})")

                // Funzione di matching rigorosa per i titoli e tipo
                fun isMatch(item: AppAdapter.Item, target: String): Boolean {
                    val isCorrectType = if (videoType is Video.Type.Movie) item is Movie else item is TvShow
                    if (!isCorrectType) return false

                    val itemTitle = if (item is Movie) item.title else (item as TvShow).title
                    val nItem = itemTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val nTarget = target.lowercase().replace(Regex("[^a-z0-9]"), "")
                    
                    // Match esatto (normalizzato) ha la priorità
                    if (nItem == nTarget) return true
                    
                    // Match parziale se contenuto e differenza lunghezza minima
                    if (nItem.contains(nTarget) || nTarget.contains(nItem)) {
                        val diff = Math.abs(nItem.length - nTarget.length)
                        if (diff <= 5) return true
                    }
                    
                    // Match per parole (almeno una deve corrispondere esattamente se il target è corto, o tutte se lungo)
                    val cleanWords: (String) -> Set<String> = { s ->
                        s.lowercase()
                            .replace(Regex("[^a-z0-9 ]"), " ")
                            .split(Regex("\\s+"))
                            .filter { it.length > 2 }
                            .toSet()
                    }
                    val nItemWords = cleanWords(itemTitle)
                    val nTargetWords = cleanWords(target)
                    
                    if (nItemWords.isEmpty() || nTargetWords.isEmpty()) return false
                    
                    // Se il target ha solo una parola importante, deve esserci
                    if (nTargetWords.size == 1) return nItemWords.contains(nTargetWords.first())
                    
                    // Altrimenti tutte le parole del target devono essere presentes nell'item
                    return nItemWords.containsAll(nTargetWords) || nTargetWords.containsAll(nItemWords)
                }

                coroutineScope {
                    val providers = listOf(PoseidonHD2Provider)
                    val deferred = providers.map { provider ->
                        async {
                            try {
                                val searchResults = provider.search(targetTitle, 1)
                                val bestMatch = searchResults.firstOrNull { isMatch(it, targetTitle) }
                                val id = if (bestMatch is Movie) bestMatch.id else (bestMatch as? TvShow)?.id
                                
                                if (id != null) {
                                    val matchTitle = if (bestMatch is Movie) bestMatch.title else (bestMatch as? TvShow)?.title
                                    Log.i("NexaStream", "[MATCH FOUND] -> Provider: ${provider.name}, Matched: '$matchTitle', ID: $id")
                                    
                                    val allServers = provider.getServers(id, videoType)
                                    val filtered = allServers.filter { s ->
                                        val n = s.name.uppercase()
                                        n.contains("[LAT]") || n.contains("[CAST]") || n.contains("[CAS]") || n.contains("[ES]") ||
                                        n.contains("(LAT)") || n.contains("(ESP)") || n.contains("LATINO") || n.contains("CASTELLANO")
                                    }
                                    Log.i("NexaStream", "[SERVERS OK] -> ${provider.name}: ${filtered.size}/${allServers.size} servers kept")
                                    filtered
                                } else {
                                    Log.d("NexaStream", "[NO MATCH] -> ${provider.name} did not find a valid match for '$targetTitle'")
                                    emptyList()
                                }
                            } catch (e: Exception) { 
                                Log.e("NexaStream", "[PROVIDER ERROR] -> ${provider.name}: ${e.message}")
                                emptyList() 
                            }
                        }
                    }
                    servers.addAll(deferred.awaitAll().flatten())
                }
            }
            else -> {
                // Per inglese (en) o altre lingue non specifiche, usiamo i server globali
                servers.addAll(listOf(
                    TwoEmbedExtractor().server(videoType),
                    VidsrcNetExtractor().server(videoType),
                    VidLinkExtractor().server(videoType),
                    VidsrcRuExtractor().server(videoType),
                    VidflixExtractor().server(videoType),
                ))

                if (videoType is Video.Type.Movie) {
                    servers.add(2, MoviesapiExtractor().server(videoType))
                }

                servers.addAll(VidrockExtractor().servers(videoType))
                servers.addAll(VidzeeExtractor().servers(videoType))
                servers.addAll(PrimeSrcExtractor().servers(videoType))

                if (language == "en") {
                    servers.addAll(1, VideasyExtractor().servers(videoType, language))
                }
            }
        }

        // 1. REORDERING LOGIC based on user preference and audio tags
        val preferredServer = UserPreferences.preferredServerName
        
        val sortedServers = servers.distinctBy { it.id }.sortedWith { s1, s2 ->
            val n1 = s1.name.uppercase()
            val n2 = s2.name.uppercase()
            
            // ABSOLUTE PRIORITY: VixSrc (if no user choice yet or if user explicitly wants it)
            // But usually User choice should be #1 if they manually set it.
            // Let's stick to: Preferred > VixSrc > Others
            
            // PRIORITY 1: User's manually selected server name match
            if (!preferredServer.isNullOrBlank() && preferredServer.uppercase() != "VIXSRC") {
                val pref = preferredServer.uppercase()
                if (n1 == pref && n2 != pref) return@sortedWith -1
                if (n2 == pref && n1 != pref) return@sortedWith 1
                
                // Partial match for preferred server
                if (n1.contains(pref) && !n2.contains(pref)) return@sortedWith -1
                if (n2.contains(pref) && !n1.contains(pref)) return@sortedWith 1
            }

            // PRIORITY 2: MovieBox preference (Primary default server)
            if (n1.contains("MOVIEBOX") && !n2.contains("MOVIEBOX")) return@sortedWith -1
            if (n2.contains("MOVIEBOX") && !n1.contains("MOVIEBOX")) return@sortedWith 1

            // PRIORITY 3: VixSrc preference (Fallback)
            if (n1.contains("VIXSRC") && !n2.contains("VIXSRC")) return@sortedWith -1
            if (n2.contains("VIXSRC") && !n1.contains("VIXSRC")) return@sortedWith 1

            // PRIORITY 3: Specific language rules (existing logic integrated)
            if (language.startsWith("es")) {
                val p1 = when {
                    n1.contains("FILEMOON") -> 110
                    n1.contains("[CAS]") || n1.contains("[LAT]") || n1.contains("[ES]") || n1.contains("SPAIN") || n1.contains("[CAST]") ||
                    n1.contains("LATINO") || n1.contains("SPANISH") || n1.contains("CASTELLANO") || n1.contains("(LAT)") || n1.contains("(ESP)") -> 100
                    n1.contains("VIDSRC") || n1.contains("VIDLINK") -> 80
                    n1.contains("[EN]") || n1.contains("[SUB]") || n1.contains("(EN)") || n1.contains("(SUB)") -> 50
                    else -> 0
                }
                val p2 = when {
                    n2.contains("FILEMOON") -> 110
                    n2.contains("[CAS]") || n2.contains("[LAT]") || n2.contains("[ES]") || n2.contains("SPAIN") || n2.contains("[CAST]") ||
                    n2.contains("LATINO") || n2.contains("SPANISH") || n2.contains("CASTELLANO") || n2.contains("(LAT)") || n2.contains("(ESP)") -> 100
                    n2.contains("VIDSRC") || n2.contains("VIDLINK") -> 80
                    n2.contains("[EN]") || n2.contains("[SUB]") || n2.contains("(EN)") || n2.contains("(SUB)") -> 50
                    else -> 0
                }
                if (p1 != p2) return@sortedWith p2 - p1
            }
            
            0
        }

        Log.i("NexaStream", "[SERVERS LIST] -> Found ${sortedServers.size} servers: ${sortedServers.joinToString { it.name }}")
        return sortedServers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifEmpty { server.id }
        Log.i("NexaStream", "[SERVER] -> Using: ${server.name} (URL: $url)")
        
        val video = when {
            server.video != null -> server.video!!
            else -> Extractor.extract(url, server)
        }

        // LOGICA SOTTOTITOLI FORZATI: Se siamo in spagnolo, attiviamo solo i forced di default
        if (language.startsWith("es")) {
            var forcedFound = false
            video.subtitles.forEach { sub ->
                val label = sub.label.lowercase()
                val isSpanish = label.contains("spanish") || label.contains("español") || 
                                label.contains("espanol") || label.contains("castellano") || 
                                label.contains(" lat ")
                val isForced = label.contains("forced") || label.contains("forzati") || label.contains("forzato")

                if (isSpanish && isForced) {
                    sub.default = true
                    forcedFound = true
                    Log.i("NexaStream", "[SUBTITLE] -> TMDb (es): Selected FORCED subtitle: ${sub.label}")
                } else {
                    sub.default = false
                }
            }
            
            if (!forcedFound) {
                video.subtitles.forEach { it.default = false }
                Log.i("NexaStream", "[SUBTITLE] -> TMDb (es): No forced subs found, keeping them OFF")
            }
        }
        
        Log.i("NexaStream", "[VIDEO] -> Final source: ${video.source}")
        return video
    }

    private fun getTranslation(key: String): String {
        return when (language) {
            "it" -> when (key) {
                "Trending" -> "Di tendenza"
                "Popular Movies" -> "Film popolari"
                "Popular TV Shows" -> "Serie TV popolari"
                "Popular Anime" -> "Anime popolari"
                "Popular on Netflix" -> "Popolari su Netflix"
                "Popular on Amazon" -> "Popolari su Amazon"
                "Popular on Disney+" -> "Popolari su Disney+"
                "Popular on Hulu" -> "Popolari su Hulu"
                "Popular on Apple TV+" -> "Popolari su Apple TV+"
                "Popular on HBO" -> "Popolari su HBO"
                else -> key
            }
            "es" -> when (key) {
                "Trending" -> "Tendencias"
                "Popular Movies" -> "Películas populares"
                "Popular TV Shows" -> "Series de TV populares"
                "Popular Anime" -> "Anime populares"
                "Popular on Netflix" -> "Popular en Netflix"
                "Popular on Amazon" -> "Popular en Amazon"
                "Popular on Disney+" -> "Popular en Disney+"
                "Popular on Hulu" -> "Popular en Hulu"
                "Popular on Apple TV+" -> "Popular en Apple TV+"
                "Popular on HBO" -> "Popular en HBO"
                else -> key
            }
            "de" -> when (key) {
                "Trending" -> "Trends"
                "Popular Movies" -> "Beliebte Filme"
                "Popular TV Shows" -> "Beliebte Serien"
                "Popular Anime" -> "Beliebte Anime"
                "Popular on Netflix" -> "Beliebt bei Netflix"
                "Popular on Amazon" -> "Beliebt bei Amazon"
                "Popular on Disney+" -> "Beliebt bei Disney+"
                "Popular on Hulu" -> "Beliebt bei Hulu"
                "Popular on Apple TV+" -> "Beliebt bei Apple TV+"
                "Popular on HBO" -> "Beliebt bei HBO"
                else -> key
            }
            "fr" -> when (key) {
                "Trending" -> "Tendances"
                "Popular Movies" -> "Films populaires"
                "Popular TV Shows" -> "Séries populaires"
                "Popular Anime" -> "Animes populaires"
                "Popular on Netflix" -> "Populaire sur Netflix"
                "Popular on Amazon" -> "Populaire sur Amazon"
                "Popular on Disney+" -> "Populaire sur Disney+"
                "Popular on Hulu" -> "Populaire sur Hulu"
                "Popular on Apple TV+" -> "Populaire sur Apple TV+"
                "Popular on HBO" -> "Populaire sur HBO"
                else -> key
            }
            else -> key
        }
    }
}
