package com.nexastream.app.providers

import android.util.Log
import com.google.gson.JsonParser
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
import com.nexastream.app.providers.moviebox.*
import java.util.Locale

object MovieBoxProvider : Provider {

    override val baseUrl = "https://themoviebox.xyz"
    override val name = "MovieBox"
    override val logo = "https://themoviebox.xyz/favicon.ico"
    override val language = "en"

    private val api: MovieBoxApi by lazy {
        MovieBoxApi.create()
    }

    private var cachedGuestToken: String? = null

    private suspend fun getGuestToken(): String {
        cachedGuestToken?.let { return it }
        return try {
            val response = api.getTrending(page = 1, perPage = 1)
            val xUser = response.headers()["x-user"]
            if (!xUser.isNullOrBlank()) {
                val token = JsonParser.parseString(xUser).asJsonObject.get("token")?.asString
                if (!token.isNullOrBlank()) {
                    cachedGuestToken = token
                    token
                } else ""
            } else ""
        } catch (e: Exception) {
            Log.e("MovieBoxProvider", "Error fetching guest token", e)
            ""
        }
    }

    override suspend fun getHome(): List<Category> {
        val categories = mutableListOf<Category>()
        try {
            val homeResponse = api.getHome()
            val operatingList = homeResponse.data?.operatingList.orEmpty()

            for (item in operatingList) {
                val itemsList = mutableListOf<AppAdapter.Item>()
                if (item.type == "BANNER") {
                    item.banner?.items?.forEach { bannerItem ->
                        bannerItem.subject?.let { subjectToAppItem(it)?.let { appItem -> itemsList.add(appItem) } }
                    }
                } else {
                    item.subjects?.forEach { subject ->
                        subjectToAppItem(subject)?.let { appItem -> itemsList.add(appItem) }
                    }
                }
                if (itemsList.isNotEmpty()) {
                    categories.add(Category(name = item.title ?: "Recommended", list = itemsList))
                }
            }
        } catch (e: Exception) {
            Log.e("MovieBoxProvider", "Error in getHome", e)
        }
        return categories
    }

    suspend fun getCategory(category: String, page: Int = 1): List<AppAdapter.Item> {
        return when (category.lowercase(Locale.US)) {
            "kids" -> getMovies(page)
            "anime" -> getTvShows(page)
            "asian" -> getTvShows(page)
            else -> getMovies(page)
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val response = api.filterSubjects(BffFilterRequest(channelId = 1, page = page))
            response.data?.items?.mapNotNull { subjectToMovie(it) }.orEmpty()
        } catch (e: Exception) {
            Log.e("MovieBoxProvider", "Error in getMovies", e)
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val response = api.filterSubjects(BffFilterRequest(channelId = 2, page = page))
            response.data?.items?.mapNotNull { subjectToTvShow(it) }.orEmpty()
        } catch (e: Exception) {
            Log.e("MovieBoxProvider", "Error in getTvShows", e)
            emptyList()
        }
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: SearchFilters?
    ): List<AppAdapter.Item> {
        return try {
            val token = getGuestToken()
            val response = api.searchSubjects(
                authHeader = if (token.isNotBlank()) "Bearer $token" else "",
                referer = "https://themoviebox.xyz/newWeb/searchResult?keyword=$query",
                request = BffSearchRequest(keyword = query, page = page)
            )
            response.data?.items?.mapNotNull { subjectToAppItem(it) }.orEmpty()
        } catch (e: Exception) {
            Log.e("MovieBoxProvider", "Error in search", e)
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val detailResponse = api.getDetail(detailPath = id)
        val data = detailResponse.data ?: throw IllegalStateException("Movie data not found: $id")
        val subject = data.subject ?: throw IllegalStateException("Movie subject not found: $id")
        val stars = data.stars.orEmpty()

        val dubRecommendations = mutableListOf<Show>()
        subject.dubs?.forEach { dub ->
            val dubPath = dub.detailPath ?: dub.subjectId ?: return@forEach
            val label = dub.lanName ?: dub.lanCode ?: "Audio"
            val dubTitle = if (dub.original == true) "$label (Original)" else "$label Audio"
            dubRecommendations.add(
                Movie(
                    id = dubPath,
                    title = dubTitle,
                    overview = subject.description,
                    poster = subject.cover?.url,
                    banner = subject.stills?.url ?: subject.cover?.url,
                    rating = subject.imdbRatingValue?.toDoubleOrNull(),
                    released = subject.releaseDate,
                    providerName = name
                )
            )
        }

        val castMembers = stars.map { star ->
            People(
                id = star.name ?: "",
                name = star.name ?: "",
                image = star.avatarUrl
            )
        }

        val genreList = subject.genre?.split(",")?.map { Genre(id = it.trim(), name = it.trim()) }.orEmpty()

        return Movie(
            id = subject.detailPath?.takeIf { it.isNotBlank() } ?: subject.subjectId ?: id,
            title = subject.title ?: "",
            overview = subject.description,
            poster = subject.cover?.url,
            banner = subject.stills?.url ?: subject.cover?.url,
            rating = subject.imdbRatingValue?.toDoubleOrNull(),
            released = subject.releaseDate,
            genres = genreList,
            cast = castMembers,
            recommendations = dubRecommendations,
            providerName = name
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val detailResponse = api.getDetail(detailPath = id)
        val data = detailResponse.data ?: throw IllegalStateException("TvShow data not found: $id")
        val subject = data.subject ?: throw IllegalStateException("TvShow subject not found: $id")
        val stars = data.stars.orEmpty()
        val seasonsResource = data.resource?.seasons.orEmpty()

        val dubRecommendations = mutableListOf<Show>()
        subject.dubs?.forEach { dub ->
            val dubPath = dub.detailPath ?: dub.subjectId ?: return@forEach
            val label = dub.lanName ?: dub.lanCode ?: "Audio"
            val dubTitle = if (dub.original == true) "$label (Original)" else "$label Audio"
            dubRecommendations.add(
                TvShow(
                    id = dubPath,
                    title = dubTitle,
                    overview = subject.description,
                    poster = subject.cover?.url,
                    banner = subject.stills?.url ?: subject.cover?.url,
                    rating = subject.imdbRatingValue?.toDoubleOrNull(),
                    released = subject.releaseDate,
                    providerName = name
                )
            )
        }

        val seasons = seasonsResource.map { seasonRes ->
            val seasonNum = seasonRes.se ?: 1
            val maxEp = seasonRes.maxEp ?: 1
            val episodes = (1..maxEp).map { epNum ->
                Episode(
                    id = "${subject.detailPath}_${seasonNum}_$epNum",
                    number = epNum,
                    title = "Episode $epNum"
                )
            }
            Season(
                id = "${subject.detailPath}_$seasonNum",
                number = seasonNum,
                title = "Season $seasonNum",
                episodes = episodes
            )
        }

        val castMembers = stars.map { star ->
            People(
                id = star.name ?: "",
                name = star.name ?: "",
                image = star.avatarUrl
            )
        }

        val genreList = subject.genre?.split(",")?.map { Genre(id = it.trim(), name = it.trim()) }.orEmpty()

        val show = TvShow(
            id = subject.detailPath?.takeIf { it.isNotBlank() } ?: subject.subjectId ?: id,
            title = subject.title ?: "",
            overview = subject.description,
            poster = subject.cover?.url,
            banner = subject.stills?.url ?: subject.cover?.url,
            rating = subject.imdbRatingValue?.toDoubleOrNull(),
            released = subject.releaseDate,
            seasons = seasons,
            genres = genreList,
            cast = castMembers,
            recommendations = dubRecommendations,
            providerName = name
        )
        seasons.forEach { season ->
            season.tvShow = show
            season.episodes.forEach {
                it.tvShow = show
                it.season = season
            }
        }
        return TmdbUtils.enrichTvShow(show)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("_")
        if (parts.size >= 2) {
            val detailPath = parts[0]
            val seasonNum = parts[1].toIntOrNull() ?: 1
            val tvShow = getTvShow(detailPath)
            return tvShow.seasons.find { it.number == seasonNum }?.episodes.orEmpty()
        }
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id)
    }

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
            Log.w("MovieBoxProvider", "Failed to resolve TMDB videoType", e)
            null
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()

        val tmdbVideoType = resolveTmdbVideoType(videoType)

        if (tmdbVideoType != null) {
            try {
                servers.add(VixSrcExtractor().server(tmdbVideoType))
            } catch (e: Exception) {
                Log.w("MovieBoxProvider", "Failed to add VixSrc server", e)
            }
        }

        try {
            val detailPath = when (videoType) {
                is Video.Type.Episode -> videoType.tvShow.id.takeIf { it.isNotBlank() } ?: id.split("_").firstOrNull() ?: id
                else -> id
            }
            val (season, episode) = when (videoType) {
                is Video.Type.Episode -> Pair(videoType.season.number, videoType.number)
                else -> Pair(0, 0)
            }

            val rawResponse = api.getPlayInfo(
                subjectId = null,
                season = season,
                episode = episode,
                detailPath = detailPath,
                referer = "https://themoviebox.xyz/movies/$detailPath"
            )
            val playResponse = if (rawResponse.data?.streams.isNullOrEmpty() && season == 0 && episode == 0) {
                runCatching {
                    api.getPlayInfo(
                        subjectId = null,
                        season = 1,
                        episode = 1,
                        detailPath = detailPath,
                        referer = "https://themoviebox.xyz/movies/$detailPath"
                    )
                }.getOrDefault(rawResponse)
            } else {
                rawResponse
            }

            val streams = playResponse.data?.streams.orEmpty()
            for (stream in streams) {
                val streamUrl = stream.url ?: continue
                if (stream.vipLocked == true && streamUrl.isBlank()) continue

                val res = stream.resolutions ?: "1080"
                val streamId = stream.id ?: res
                val sizeBytes = stream.size

                val subtitlesList = try {
                    val captionResponse = api.getCaptions(
                        streamId = streamId,
                        detailPath = detailPath,
                        referer = "https://themoviebox.xyz/movies/$detailPath"
                    )
                    captionResponse.data?.captions?.map { cap ->
                        Video.Subtitle(
                            label = cap.lanName ?: cap.lan ?: "Subtitle",
                            file = cap.url ?: "",
                            language = cap.lan,
                            default = cap.lan?.lowercase(Locale.US)?.startsWith("en") == true
                        )
                    }.orEmpty()
                } catch (e: Exception) {
                    emptyList()
                }

                val formattedSize = if (sizeBytes != null && sizeBytes > 0) {
                    val mb = sizeBytes.toDouble() / (1024.0 * 1024.0)
                    if (mb >= 1000.0) String.format(Locale.US, "%.1f GB", mb / 1024.0)
                    else String.format(Locale.US, "%.0f MB", mb)
                } else null

                val serverName = if (formattedSize != null) {
                    "${res}p ($formattedSize · ${stream.format ?: "MP4"})"
                } else {
                    "${res}p (${stream.format ?: "MP4"})"
                }

                val server = Video.Server(
                    id = "moviebox_${streamId}_$res",
                    name = serverName,
                    src = streamUrl
                ).apply {
                    this.video = Video(
                        source = streamUrl,
                        subtitles = subtitlesList,
                        headers = mapOf(
                            "Referer" to "https://themoviebox.xyz/movies/$detailPath",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        ),
                        type = "video/mp4"
                    )
                }
                servers.add(server)
            }
        } catch (e: Exception) {
            Log.e("MovieBoxProvider", "Error fetching play info", e)
        }

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
                Log.w("MovieBoxProvider", "Failed to add multi-embed fallbacks", e)
            }
        }

        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.video != null) return server.video!!
        val url = server.src.ifEmpty { server.id }
        return Extractor.extract(url, server)
    }

    private fun subjectToAppItem(subject: BffSubject): AppAdapter.Item? {
        val isTv = subject.subjectType == 2 || subject.subjectType == 7
        return if (isTv) {
            subjectToTvShow(subject)
        } else {
            subjectToMovie(subject)
        }
    }

    private fun subjectToMovie(subject: BffSubject): Movie? {
        val detailPath = subject.detailPath?.takeIf { it.isNotBlank() } ?: subject.subjectId ?: return null
        return Movie(
            id = detailPath,
            title = subject.title ?: "",
            overview = subject.description,
            poster = subject.cover?.url,
            banner = subject.stills?.url ?: subject.cover?.url,
            rating = subject.imdbRatingValue?.toDoubleOrNull(),
            released = subject.releaseDate,
            providerName = name
        )
    }

    private fun subjectToTvShow(subject: BffSubject): TvShow? {
        val detailPath = subject.detailPath?.takeIf { it.isNotBlank() } ?: subject.subjectId ?: return null
        return TvShow(
            id = detailPath,
            title = subject.title ?: "",
            overview = subject.description,
            poster = subject.cover?.url,
            banner = subject.stills?.url ?: subject.cover?.url,
            rating = subject.imdbRatingValue?.toDoubleOrNull(),
            released = subject.releaseDate,
            providerName = name
        )
    }
}
