package com.nexastream.app.providers

import android.util.Log
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.extractors.GengaExtractor
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.providers.genga.GengaApi
import com.nexastream.app.providers.genga.GengaSubject
import com.nexastream.app.providers.genga.LocalGengaServer

object GengaProvider : Provider {

    override val baseUrl = "http://127.0.0.1:3001"
    override val name = "Genga"
    override val logo = "https://www.moviebox.ph/favicon.ico"
    override val language = "en"

    private val api by lazy { GengaApi.create("http://127.0.0.1:3001/") }
    private val extractor by lazy { GengaExtractor() }

    fun ensureServerRunning() {
        try {
            val context = com.nexastream.app.NexastreamApp.instance.applicationContext
            LocalGengaServer.getInstance(context, 3001)
        } catch (e: Exception) {
            Log.e("GengaProvider", "Failed to start LocalGengaServer: ${e.message}")
        }
    }

    private fun parseSubjects(subjects: List<GengaSubject>?): List<AppAdapter.Item> {
        if (subjects == null) return emptyList()
        return subjects.map { subject ->
            if (subject.isTvShow) {
                TvShow(
                    id = subject.id,
                    title = subject.name,
                    poster = subject.poster,
                    banner = subject.poster,
                    overview = subject.description,
                    rating = subject.rating?.toDoubleOrNull(),
                    released = subject.year,
                    providerName = "MovieBox"
                )
            } else {
                Movie(
                    id = subject.id,
                    title = subject.name,
                    poster = subject.poster,
                    banner = subject.poster,
                    overview = subject.description,
                    rating = subject.rating?.toDoubleOrNull(),
                    released = subject.year,
                    providerName = "MovieBox"
                )
            }
        }
    }

    override suspend fun getHome(): List<Category> {
        ensureServerRunning()
        val categories = mutableListOf<Category>()

        try {
            val trendingResponse = api.getTrending()
            val trendingItems = parseSubjects(trendingResponse.data?.items ?: trendingResponse.data?.list)
            if (trendingItems.isNotEmpty()) {
                categories.add(Category(name = Category.FEATURED, list = trendingItems))
                categories.add(Category(name = "Trending Today", list = trendingItems))
            }
        } catch (e: Exception) {
            Log.e("GengaProvider", "Error fetching trending: ${e.message}")
        }

        try {
            val moviesResponse = api.getCategory("movies", 1)
            val movieItems = parseSubjects(moviesResponse.data?.items ?: moviesResponse.data?.list)
            if (movieItems.isNotEmpty()) {
                categories.add(Category(name = "Popular Movies", list = movieItems))
            }
        } catch (e: Exception) {
            Log.e("GengaProvider", "Error fetching movies: ${e.message}")
        }

        try {
            val animeResponse = api.getCategory("anime", 1)
            val animeItems = parseSubjects(animeResponse.data?.items ?: animeResponse.data?.list)
            if (animeItems.isNotEmpty()) {
                categories.add(Category(name = "Anime", list = animeItems))
            }
        } catch (e: Exception) {
            Log.e("GengaProvider", "Error fetching anime: ${e.message}")
        }

        try {
            val kidsResponse = api.getCategory("kids", 1)
            val kidsItems = parseSubjects(kidsResponse.data?.items ?: kidsResponse.data?.list)
            if (kidsItems.isNotEmpty()) {
                categories.add(Category(name = "Kids & Family", list = kidsItems))
            }
        } catch (e: Exception) {
            Log.e("GengaProvider", "Error fetching kids: ${e.message}")
        }

        return categories
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: SearchFilters?
    ): List<AppAdapter.Item> {
        ensureServerRunning()
        val searchResponse = try {
            api.search(query, page)
        } catch (e: Exception) {
            Log.e("GengaProvider", "Search error: ${e.message}")
            return emptyList()
        }

        val items = searchResponse.data?.items ?: searchResponse.data?.list ?: emptyList()
        return parseSubjects(items)
    }

    suspend fun getCategory(category: String, page: Int = 1): List<AppAdapter.Item> {
        ensureServerRunning()
        val response = try { api.getCategory(category, page) } catch (_: Exception) { null }
        val items = response?.data?.items ?: response?.data?.list ?: emptyList()
        return parseSubjects(items)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        ensureServerRunning()
        val response = try { api.getCategory("movies", page) } catch (_: Exception) { null }
        val items = response?.data?.items ?: response?.data?.list ?: emptyList()
        return parseSubjects(items).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        ensureServerRunning()
        val westernResponse = try { api.getCategory("western", page) } catch (_: Exception) { null }
        val westernItems = westernResponse?.data?.items ?: westernResponse?.data?.list ?: emptyList()
        val tvShows = parseSubjects(westernItems).filterIsInstance<TvShow>()
        if (tvShows.isNotEmpty()) return tvShows

        val response = try { api.getCategory("anime", page) } catch (_: Exception) { null }
        val items = response?.data?.items ?: response?.data?.list ?: emptyList()
        return parseSubjects(items).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        ensureServerRunning()
        val detailResponse = try {
            api.getDetail(id)
        } catch (e: Exception) {
            throw IllegalStateException("Movie not found on MovieBox: $id", e)
        }
        val subject = detailResponse.data ?: throw IllegalStateException("Movie not found: $id")
        return Movie(
            id = subject.id,
            title = subject.name,
            poster = subject.poster,
            banner = subject.poster,
            overview = subject.description,
            rating = subject.rating?.toDoubleOrNull(),
            released = subject.year,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        ensureServerRunning()
        val detailResponse = try {
            api.getDetail(id)
        } catch (e: Exception) {
            throw IllegalStateException("TvShow not found on MovieBox: $id", e)
        }
        val subject = detailResponse.data ?: throw IllegalStateException("TvShow not found: $id")

        val episodesResp = try {
            api.getEpisodes(subject.id)
        } catch (_: Exception) {
            null
        }

        val fetchedSeasons = episodesResp?.data?.seasons ?: emptyList()

        var seasons = if (fetchedSeasons.isNotEmpty()) {
            fetchedSeasons.map { sInfo ->
                val epList = sInfo.episodeList ?: emptyList()
                val seasonPoster = sInfo.poster.takeIf { !it.isNullOrEmpty() } ?: subject.poster
                val seasonObj = com.nexastream.app.models.Season(
                    id = "${subject.id}|${sInfo.seasonNumber}",
                    number = sInfo.seasonNumber,
                    title = "Season ${sInfo.seasonNumber}",
                    poster = seasonPoster,
                )
                seasonObj.episodes = epList.map { ep ->
                    val epNum = ep.episodeNumberInt
                    val epPoster = ep.poster.takeIf { !it.isNullOrEmpty() } ?: seasonPoster
                    Episode(
                        id = "${subject.id}|${sInfo.seasonNumber}|$epNum",
                        number = epNum,
                        title = ep.title?.ifEmpty { "Episode $epNum" } ?: "Episode $epNum",
                        poster = epPoster,
                        season = seasonObj,
                    )
                }
                seasonObj
            }
        } else {
            val fallbackSeasonList = detailResponse.seasonList ?: emptyList()
            fallbackSeasonList.map { sInfo ->
                val epList = sInfo.episodeList ?: emptyList()
                val seasonPoster = sInfo.poster.takeIf { !it.isNullOrEmpty() } ?: subject.poster
                val seasonObj = com.nexastream.app.models.Season(
                    id = "${subject.id}|${sInfo.seasonNumber}",
                    number = sInfo.seasonNumber,
                    title = "Season ${sInfo.seasonNumber}",
                    poster = seasonPoster,
                )
                seasonObj.episodes = epList.map { ep ->
                    val epNum = ep.episodeNumberInt
                    val epPoster = ep.poster.takeIf { !it.isNullOrEmpty() } ?: seasonPoster
                    Episode(
                        id = "${subject.id}|${sInfo.seasonNumber}|$epNum",
                        number = epNum,
                        title = ep.title?.ifEmpty { "Episode $epNum" } ?: "Episode $epNum",
                        poster = epPoster,
                        season = seasonObj,
                    )
                }
                seasonObj
            }
        }

        val totalEpisodes = seasons.sumOf { it.episodes.size }
        if (totalEpisodes == 0) {
            val fallbackSeason = com.nexastream.app.models.Season(
                id = "${subject.id}|1",
                number = 1,
                title = "Season 1",
                poster = subject.poster,
            )
            fallbackSeason.episodes = listOf(
                Episode(
                    id = "${subject.id}|1|1",
                    number = 1,
                    title = "Episode 1",
                    poster = subject.poster,
                    season = fallbackSeason,
                )
            )
            seasons = listOf(fallbackSeason)
        }

        return TvShow(
            id = subject.id,
            title = subject.name,
            poster = subject.poster,
            banner = subject.poster,
            overview = subject.description,
            rating = subject.rating?.toDoubleOrNull(),
            released = subject.year,
            seasons = seasons,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        ensureServerRunning()
        val parts = seasonId.split("|")
        val seriesId = parts.getOrNull(0) ?: seasonId
        val seasonNum = parts.getOrNull(1)?.toIntOrNull() ?: 1

        val episodesResp = try {
            api.getEpisodes(seriesId)
        } catch (_: Exception) {
            null
        }

        val seasons = episodesResp?.data?.seasons
        if (!seasons.isNullOrEmpty()) {
            val season = seasons.find { it.seasonNumber == seasonNum } ?: seasons.first()
            val epList = season.episodeList ?: emptyList()
            val seasonPoster = season.poster.takeIf { !it.isNullOrEmpty() }
            val seasonObj = com.nexastream.app.models.Season(
                id = "$seriesId|${season.seasonNumber}",
                number = season.seasonNumber,
                title = "Season ${season.seasonNumber}",
                poster = seasonPoster,
            )
            return epList.map { ep ->
                val epNum = ep.episodeNumberInt
                val epPoster = ep.poster.takeIf { !it.isNullOrEmpty() } ?: seasonPoster
                Episode(
                    id = "$seriesId|${season.seasonNumber}|$epNum",
                    title = ep.title?.ifEmpty { "Episode $epNum" } ?: "Episode $epNum",
                    number = epNum,
                    poster = epPoster,
                    season = seasonObj,
                )
            }
        }
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id, shows = emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return extractor.servers(videoType)
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return extractor.extract(server.src)
    }
}
