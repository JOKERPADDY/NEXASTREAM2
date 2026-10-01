package com.nexastream.app.utils

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Movie
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.TMDb3.original
import com.nexastream.app.utils.TMDb3.w500
import java.util.Calendar

data class AiSearchIntent(
    val rawQuery: String,
    val mediaType: SearchFilters.MediaType = SearchFilters.MediaType.ALL,
    val genres: List<String> = emptyList(),
    val genreIds: List<Int> = emptyList(),
    val startYear: Int? = null,
    val endYear: Int? = null,
    val minRating: Float? = null,
    val keywords: List<String> = emptyList(),
    val cleanedQuery: String = "",
    val explanation: String = ""
)

data class AiSearchPrompt(
    val title: String,
    val query: String,
    val emoji: String
)

object AiSearchEngine {

    val defaultPrompts = listOf(
        AiSearchPrompt("Mind-bending Sci-Fi", "mind-bending sci-fi movies from 2010 to 2020", "🤯"),
        AiSearchPrompt("Top 90s Anime", "top rated 90s anime tv series", "🎎"),
        AiSearchPrompt("Scary Space Horror", "scary space horror movies rated above 7", "🚀"),
        AiSearchPrompt("Hilarious Comedies", "hilarious comedy movies", "🍿"),
        AiSearchPrompt("High-Octane Action", "high action movies from 2020 to 2024", "🔥"),
        AiSearchPrompt("Emotional Dramas", "emotional top rated drama shows", "🎭")
    )

    private val genreMap = mapOf(
        28 to listOf("action", "fight", "martial arts", "warrior"),
        12 to listOf("adventure", "expedition", "quest"),
        16 to listOf("animation", "animated", "anime", "cartoon"),
        35 to listOf("comedy", "funny", "humor", "hilarious", "laugh"),
        80 to listOf("crime", "mafia", "gangster", "cop", "detective"),
        99 to listOf("documentary", "docu", "real life"),
        18 to listOf("drama", "emotional", "tragic"),
        10751 to listOf("family", "kids", "children"),
        14 to listOf("fantasy", "magic", "wizard", "supernatural"),
        36 to listOf("history", "historical", "period piece"),
        27 to listOf("horror", "scary", "spooky", "frightening", "slasher"),
        10402 to listOf("music", "musical"),
        9648 to listOf("mystery", "whodunit"),
        10749 to listOf("romance", "romantic", "love"),
        878 to listOf("sci-fi", "scifi", "science fiction", "space", "futuristic", "cyberpunk", "time travel", "mind-bending", "alien"),
        10770 to listOf("tv movie"),
        53 to listOf("thriller", "suspense", "tension"),
        10752 to listOf("war", "military"),
        37 to listOf("western", "cowboy")
    )

    private val genreNameMap = mapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Sci-Fi",
        53 to "Thriller",
        10752 to "War",
        37 to "Western"
    )

    fun parseQuery(rawQuery: String): AiSearchIntent {
        val lower = rawQuery.lowercase().trim()
        if (lower.isBlank()) {
            return AiSearchIntent(rawQuery)
        }

        // 1. Detect Media Type
        val mediaType = when {
            lower.contains("movie") || lower.contains("movies") || lower.contains("film") || lower.contains("films") || lower.contains("cinema") ->
                SearchFilters.MediaType.MOVIES
            lower.contains("tv show") || lower.contains("tv series") || lower.contains("show") || lower.contains("shows") || lower.contains("series") || lower.contains("anime series") || lower.contains("drama series") ->
                SearchFilters.MediaType.TV_SHOWS
            else -> SearchFilters.MediaType.ALL
        }

        // 2. Detect Years & Decades
        var startYear: Int? = null
        var endYear: Int? = null

        val decadeRegex = Regex("""\b(19\d0|20\d0|90|80|70|00|10)s\b""")
        val decadeMatch = decadeRegex.find(lower)
        if (decadeMatch != null) {
            val decadeStr = decadeMatch.groupValues[1]
            val baseYear = when (decadeStr) {
                "70" -> 1970
                "80" -> 1980
                "90" -> 1990
                "00" -> 2000
                "10" -> 2010
                else -> decadeStr.toIntOrNull() ?: 2000
            }
            startYear = baseYear
            endYear = baseYear + 9
        }

        if (startYear == null) {
            val rangeRegex = Regex("""(from|between)?\s*(\d{4})\s*(to|and|-)\s*(\d{4})""")
            val rangeMatch = rangeRegex.find(lower)
            if (rangeMatch != null) {
                startYear = rangeMatch.groupValues[2].toIntOrNull()
                endYear = rangeMatch.groupValues[4].toIntOrNull()
            }
        }

        if (startYear == null) {
            val singleYearRegex = Regex("""\b(19\d\d|20\d\d)\b""")
            val singleYearMatch = singleYearRegex.find(lower)
            if (singleYearMatch != null) {
                val yearVal = singleYearMatch.groupValues[1].toIntOrNull()
                if (yearVal != null) {
                    startYear = yearVal
                    endYear = yearVal
                }
            }
        }

        if (startYear == null && (lower.contains("latest") || lower.contains("new") || lower.contains("recent"))) {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            startYear = currentYear - 2
            endYear = currentYear
        }

        // 3. Detect Ratings
        var minRating: Float? = null
        val ratingRegex = Regex("""(rated|rating|vote|score)?\s*(above|over|>|gte|at least)\s*(\d+(\.\d+)?)""")
        val ratingMatch = ratingRegex.find(lower)
        if (ratingMatch != null) {
            minRating = ratingMatch.groupValues[3].toFloatOrNull()
        } else if (lower.contains("top rated") || lower.contains("best") || lower.contains("highly rated")) {
            minRating = 7.5f
        }

        // 4. Detect Genres
        val matchedGenreIds = mutableListOf<Int>()
        val matchedGenreNames = mutableListOf<String>()

        genreMap.forEach { (id, keywords) ->
            if (keywords.any { lower.contains(it) }) {
                matchedGenreIds.add(id)
                genreNameMap[id]?.let { matchedGenreNames.add(it) }
            }
        }

        // Clean query terms by stripping recognized control words
        val cleaned = lower
            .replace(Regex("""\b(movies?|films?|cinema|tv shows?|tv series|shows?|series|rated|above|over|top rated|best|highly rated|latest|new|recent|from|to|between|and)\b"""), "")
            .replace(Regex("""\b(19\d0|20\d0|90|80|70|00|10)s\b"""), "")
            .replace(Regex("""\b(19\d\d|20\d\d)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // 5. Build Human-Readable AI Explanation
        val explParts = mutableListOf<String>()
        explParts.add("✨ AI Search")

        if (matchedGenreNames.isNotEmpty()) {
            explParts.add(matchedGenreNames.joinToString("/"))
        }

        if (startYear != null && endYear != null) {
            if (startYear == endYear) {
                explParts.add("$startYear")
            } else {
                explParts.add("$startYear–$endYear")
            }
        }

        if (minRating != null) {
            explParts.add("Rating ≥ $minRating")
        }

        val typeLabel = when (mediaType) {
            SearchFilters.MediaType.MOVIES -> "Movies"
            SearchFilters.MediaType.TV_SHOWS -> "TV Shows"
            SearchFilters.MediaType.ALL -> null
        }
        typeLabel?.let { explParts.add(it) }

        val explanation = explParts.joinToString(" • ")

        return AiSearchIntent(
            rawQuery = rawQuery,
            mediaType = mediaType,
            genres = matchedGenreNames,
            genreIds = matchedGenreIds,
            startYear = startYear,
            endYear = endYear,
            minRating = minRating,
            cleanedQuery = cleaned,
            explanation = explanation
        )
    }

    suspend fun discoverByAiIntent(
        intent: AiSearchIntent,
        language: String = "en",
        page: Int = 1
    ): List<AppAdapter.Item> {
        val results = mutableListOf<AppAdapter.Item>()

        val genrePipe = if (intent.genreIds.isNotEmpty()) intent.genreIds.joinToString("|") else null
        val voteRange = intent.minRating?.let { TMDb3.Params.Range(gte = it) }

        val startCal = intent.startYear?.let {
            Calendar.getInstance().apply { set(it, Calendar.JANUARY, 1) }
        }
        val endCal = intent.endYear?.let {
            Calendar.getInstance().apply { set(it, Calendar.DECEMBER, 31) }
        }
        val dateRange = if (startCal != null || endCal != null) {
            TMDb3.Params.Range(gte = startCal, lte = endCal)
        } else null

        // Discover Movies
        if (intent.mediaType == SearchFilters.MediaType.ALL || intent.mediaType == SearchFilters.MediaType.MOVIES) {
            try {
                val moviePage = TMDb3.Discover.movie(
                    language = language,
                    page = page,
                    primaryReleaseDate = dateRange,
                    voteAverage = voteRange,
                    withGenres = genrePipe?.let { TMDb3.Params.WithBuilder(it) },
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC
                )
                moviePage.results.mapTo(results) { movie ->
                    Movie(
                        id = movie.id.toString(),
                        title = movie.title,
                        overview = movie.overview,
                        released = movie.releaseDate,
                        rating = movie.voteAverage.toDouble(),
                        poster = movie.posterPath?.w500,
                        banner = movie.backdropPath?.original
                    )
                }
            } catch (_: Exception) {}
        }

        // Discover TV Shows
        if (intent.mediaType == SearchFilters.MediaType.ALL || intent.mediaType == SearchFilters.MediaType.TV_SHOWS) {
            try {
                val tvPage = TMDb3.Discover.tv(
                    language = language,
                    page = page,
                    firstAirDate = dateRange,
                    voteAverage = voteRange,
                    withGenres = genrePipe?.let { TMDb3.Params.WithBuilder(it) },
                    sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC
                )
                tvPage.results.mapTo(results) { tv ->
                    TvShow(
                        id = tv.id.toString(),
                        title = tv.name,
                        overview = tv.overview,
                        released = tv.firstAirDate,
                        rating = tv.voteAverage.toDouble(),
                        poster = tv.posterPath?.w500,
                        banner = tv.backdropPath?.original
                    )
                }
            } catch (_: Exception) {}
        }

        return results.distinctBy { when (it) {
            is Movie -> "movie:${it.id}"
            is TvShow -> "tv:${it.id}"
            else -> it.hashCode().toString()
        } }
    }
}
