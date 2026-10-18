package com.nexastream.app.utils

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Category
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UserInterestProfile(
    val genreWeights: Map<String, Double> = emptyMap(),
    val totalInteractions: Int = 0
)

object RecommendationEngine {

    private const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000

    suspend fun buildUserInterestProfile(database: AppDatabase?): UserInterestProfile = withContext(Dispatchers.IO) {
        if (database == null) return@withContext UserInterestProfile()

        runCatching {
            val rawWeights = mutableMapOf<String, Double>()
            var interactionCount = 0
            val now = System.currentTimeMillis()

            // 1. Process Movies
            val movies = database.movieDao().getAll()
            movies.forEach { movie ->
                var itemWeight = 0.0
                if (movie.isFavorite) {
                    itemWeight += 3.0
                    interactionCount++
                }
                if (movie.isWatched || movie.watchHistory != null) {
                    itemWeight += 2.0
                    interactionCount++
                }

                val lastTime = movie.watchHistory?.lastEngagementTimeUtcMillis ?: movie.favoritedAtMillis ?: 0L
                if (lastTime > 0 && (now - lastTime) < THIRTY_DAYS_MILLIS) {
                    itemWeight *= 1.5
                }

                if (itemWeight > 0) {
                    val genresToCredit = movie.genres.map { it.name }.ifEmpty { extractGenresFromTitle(movie.title, movie.overview ?: "") }
                    genresToCredit.forEach { genre ->
                        val normalized = normalizeGenreName(genre)
                        rawWeights[normalized] = (rawWeights[normalized] ?: 0.0) + itemWeight
                    }
                }
            }

            // 2. Process TV Shows
            val tvShows = database.tvShowDao().getAllForBackup()
            tvShows.forEach { tv ->
                var itemWeight = 0.0
                if (tv.isFavorite) {
                    itemWeight += 3.0
                    interactionCount++
                }
                if (tv.isWatching) {
                    itemWeight += 2.0
                    interactionCount++
                }

                val lastTime = tv.favoritedAtMillis ?: 0L
                if (lastTime > 0 && (now - lastTime) < THIRTY_DAYS_MILLIS) {
                    itemWeight *= 1.5
                }

                if (itemWeight > 0) {
                    val genresToCredit = tv.genres.map { it.name }.ifEmpty { extractGenresFromTitle(tv.title, tv.overview ?: "") }
                    genresToCredit.forEach { genre ->
                        val normalized = normalizeGenreName(genre)
                        rawWeights[normalized] = (rawWeights[normalized] ?: 0.0) + itemWeight
                    }
                }
            }

            val maxWeight = rawWeights.values.maxOrNull() ?: 1.0
            val normalizedWeights = if (maxWeight > 0) {
                rawWeights.mapValues { it.value / maxWeight }
            } else emptyMap()

            UserInterestProfile(
                genreWeights = normalizedWeights,
                totalInteractions = interactionCount
            )
        }.getOrDefault(UserInterestProfile())
    }

    fun scoreItem(item: AppAdapter.Item, profile: UserInterestProfile): Double {
        if (profile.genreWeights.isEmpty()) return 0.0

        var score = 0.0
        when (item) {
            is Movie -> {
                val genres = item.genres.map { it.name }.ifEmpty { extractGenresFromTitle(item.title, item.overview ?: "") }
                genres.forEach { genre ->
                    val norm = normalizeGenreName(genre)
                    score += profile.genreWeights[norm] ?: 0.0
                }
                score += (item.rating ?: 0.0) / 20.0
            }
            is TvShow -> {
                val genres = item.genres.map { it.name }.ifEmpty { extractGenresFromTitle(item.title, item.overview ?: "") }
                genres.forEach { genre ->
                    val norm = normalizeGenreName(genre)
                    score += profile.genreWeights[norm] ?: 0.0
                }
                score += (item.rating ?: 0.0) / 20.0
            }
        }
        return score
    }

    fun rankCategories(
        categories: List<Category>,
        profile: UserInterestProfile
    ): List<Category> {
        if (categories.isEmpty()) {
            return categories
        }

        // 1. Hero Banner
        val heroBanner = categories.find { cat ->
            cat.name == Category.FEATURED ||
            cat.name == "Movies Banner" ||
            cat.name == "Series Banner" ||
            cat.name.endsWith("Banner", ignoreCase = true)
        }

        // 2. Live Sports
        val liveSports = categories.find { cat ->
            cat.name.equals("Live Sports", ignoreCase = true) && cat != heroBanner
        }

        // 3. Continue Watching
        val continueWatching = categories.find { cat ->
            cat.name == Category.CONTINUE_WATCHING ||
            cat.name.contains("Continue Watching", ignoreCase = true)
        }

        // 4. Recommended For You
        var recommended = categories.find { cat ->
            (cat.name == "✨ Recommended For You" ||
             cat.name == "Recommended For You" ||
             cat.name.startsWith("Because You Watched", ignoreCase = true) ||
             cat.name == "⭐ Recommended Vibes") &&
            cat != heroBanner && cat != liveSports
        }

        if (recommended == null && profile.totalInteractions > 0 && profile.genreWeights.isNotEmpty()) {
            val allItems = categories.flatMap { it.list }
                .distinctBy { when (it) {
                    is Movie -> "movie:${it.id}"
                    is TvShow -> "tv:${it.id}"
                    else -> it.hashCode().toString()
                } }
                .sortedByDescending { scoreItem(it, profile) }
                .take(15)

            if (allItems.isNotEmpty()) {
                recommended = Category(
                    name = "✨ Recommended For You",
                    list = allItems
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            }
        }

        // 5. Livestream
        val livestream = categories.find { cat ->
            (cat.name.equals("Livestream", ignoreCase = true) ||
             cat.name.startsWith("Livestream", ignoreCase = true) ||
             cat.name.equals("CDN Live Channels", ignoreCase = true)) &&
            cat != heroBanner && cat != liveSports && cat != recommended
        }

        // 6. Upcoming Matches
        val upcomingMatches = categories.find { cat ->
            (cat.name.equals("Upcoming Matches", ignoreCase = true) ||
             cat.name.equals("Upcoming Sports", ignoreCase = true) ||
             cat.name.equals("Upcoming & Recent", ignoreCase = true) ||
             cat.name.equals("Daily Agenda", ignoreCase = true)) &&
            cat != heroBanner && cat != liveSports && cat != recommended && cat != livestream
        }

        val prioritySet = setOfNotNull(
            heroBanner,
            liveSports,
            continueWatching,
            recommended,
            livestream,
            upcomingMatches
        )

        val remaining = categories.filter { it !in prioritySet }

        val sortedRemaining = if (profile.totalInteractions > 0 && profile.genreWeights.isNotEmpty()) {
            remaining.sortedByDescending { category ->
                var score = 0.0
                if (category.list.isNotEmpty()) {
                    score += category.list.map { scoreItem(it, profile) }.average()
                }
                profile.genreWeights.forEach { (genre, weight) ->
                    if (category.name.lowercase().contains(genre.lowercase())) {
                        score += weight * 2.0
                    }
                }
                score
            }
        } else {
            remaining
        }

        val result = mutableListOf<Category>()
        heroBanner?.let { result.add(it) }
        liveSports?.let { result.add(it) }
        continueWatching?.let { result.add(it) }
        recommended?.let { result.add(it) }
        livestream?.let { result.add(it) }
        upcomingMatches?.let { result.add(it) }
        result.addAll(sortedRemaining)

        return result.distinctBy { it.name }
    }

    private fun normalizeGenreName(name: String): String {
        val lower = name.lowercase().trim()
        return when {
            lower.contains("sci-fi") || lower.contains("scifi") || lower.contains("science fiction") -> "Sci-Fi"
            lower.contains("action") -> "Action"
            lower.contains("comedy") || lower.contains("funny") -> "Comedy"
            lower.contains("horror") || lower.contains("scary") -> "Horror"
            lower.contains("anime") || lower.contains("animation") -> "Animation"
            lower.contains("drama") -> "Drama"
            lower.contains("thriller") || lower.contains("suspense") -> "Thriller"
            lower.contains("romance") || lower.contains("romantic") -> "Romance"
            lower.contains("family") || lower.contains("kids") -> "Family"
            lower.contains("fantasy") -> "Fantasy"
            lower.contains("crime") || lower.contains("mystery") -> "Crime"
            else -> name.replaceFirstChar { it.uppercase() }
        }
    }

    private fun extractGenresFromTitle(title: String, overview: String): List<String> {
        val combined = "$title $overview".lowercase()
        val genres = mutableListOf<String>()

        if (combined.contains("sci-fi") || combined.contains("scifi") || combined.contains("space") || combined.contains("future")) genres.add("Sci-Fi")
        if (combined.contains("action") || combined.contains("fight") || combined.contains("warrior")) genres.add("Action")
        if (combined.contains("comedy") || combined.contains("funny") || combined.contains("humor")) genres.add("Comedy")
        if (combined.contains("horror") || combined.contains("scary") || combined.contains("ghost")) genres.add("Horror")
        if (combined.contains("anime") || combined.contains("animated")) genres.add("Animation")
        if (combined.contains("drama") || combined.contains("emotional")) genres.add("Drama")
        if (combined.contains("thriller") || combined.contains("mystery")) genres.add("Thriller")
        if (combined.contains("romance") || combined.contains("love")) genres.add("Romance")

        return genres
    }
}
