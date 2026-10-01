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

        // Filter out any existing "Recommended For You" category to avoid duplicate rows
        val sanitizedCategories = categories.filterNot { cat ->
            cat.name == "✨ Recommended For You" ||
            cat.name == "Recommended For You" ||
            cat.name == "Recommended for you" ||
            cat.name == "tmdb_recommended_for_you"
        }

        if (profile.totalInteractions == 0 || profile.genreWeights.isEmpty()) {
            return sanitizedCategories.distinctBy { it.name }
        }

        // Separate hero banner (FEATURED) from other fixed categories
        val (heroBanner, otherCategories) = sanitizedCategories.partition { cat ->
            cat.name == Category.FEATURED || cat.name.isEmpty()
        }

        // Separate other fixed top categories (Banner, Livestreams, Recent, Continue Watching) from general categories
        val (fixedHead, rankable) = otherCategories.partition { cat ->
            val nameLower = cat.name.lowercase()
            cat.name == "Featured" ||
            (nameLower.contains("banner") && !nameLower.contains("section")) ||
            nameLower.contains("hero") ||
            nameLower.contains("livestream") ||
            nameLower.contains("recent") ||
            nameLower.contains("continue watching")
        }

        // Score rankable categories
        val scoredCategories = rankable.map { category ->
            var catScore = 0.0
            val items = category.list
            if (items.isNotEmpty()) {
                val avgItemScore = items.map { scoreItem(it, profile) }.average()
                catScore += avgItemScore
            }

            profile.genreWeights.forEach { (genre, weight) ->
                if (category.name.lowercase().contains(genre.lowercase())) {
                    catScore += weight * 2.0
                }
            }

            category to catScore
        }

        val sortedRankable = scoredCategories.sortedByDescending { it.second }.map { it.first }

        // Gather top personalized items across rankable categories
        val allItems = rankable.flatMap { it.list }
            .distinctBy { when (it) {
                is Movie -> "movie:${it.id}"
                is TvShow -> "tv:${it.id}"
                else -> it.hashCode().toString()
            } }
            .sortedByDescending { scoreItem(it, profile) }
            .take(15)

        val result = mutableListOf<Category>()

        // 1. Always add hero banner first
        result.addAll(heroBanner)

        // 2. Add other fixed categories (livestreams, banners, etc.)
        result.addAll(fixedHead)

        // 3. Add recommended section
        if (allItems.isNotEmpty()) {
            result.add(
                Category(
                    name = "✨ Recommended For You",
                    list = allItems
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
        }

        // 4. Add sorted rankable categories
        result.addAll(sortedRankable)

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
