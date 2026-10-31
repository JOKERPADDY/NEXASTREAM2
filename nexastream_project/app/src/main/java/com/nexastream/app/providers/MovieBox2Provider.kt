package com.nexastream.app.providers

import android.content.Context
import android.util.Log
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.*
import com.nexastream.app.providers.moviebox2.*
import java.util.Locale

object MovieBox2Provider : Provider {

    override val baseUrl = "https://api.inmoviebox.com"
    override val name = "MovieBox 2"
    override val logo = "https://themoviebox.xyz/favicon.ico"
    override val language = "en"

    private val api: MovieBox2Api by lazy {
        val context = com.nexastream.app.NexastreamApp.instance.applicationContext
        MovieBox2Api.create(context)
    }

    private fun mapTitle(raw: Map<String, Any?>?): MovieBox2Title {
        if (raw == null) return MovieBox2Title("", "", "movie", "")
        val cover = raw["cover"] as? Map<String, Any?>
        val stills = raw["stills"] as? Map<String, Any?>
        val type = (raw["subjectType"] as? Number)?.toInt() ?: 1
        val kind = when (type) {
            2 -> "series"
            7 -> "short_series"
            else -> "movie"
        }
        val genresStr = raw["genre"] as? String ?: ""
        val genres = genresStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        return MovieBox2Title(
            id = (raw["subjectId"]?.toString() ?: raw["id"]?.toString() ?: ""),
            detailPath = (raw["detailPath"]?.toString() ?: ""),
            kind = kind,
            title = (raw["title"]?.toString() ?: ""),
            description = (raw["description"]?.toString()),
            releaseDate = (raw["releaseDate"]?.toString()),
            durationSeconds = (raw["duration"] as? Number)?.toInt(),
            genres = genres,
            posterUrl = (cover?.get("url")?.toString()),
            backdropUrl = (stills?.get("url")?.toString()),
            country = (raw["countryName"]?.toString()),
            rating = (raw["imdbRatingValue"]?.toString()),
            hasResource = (raw["hasResource"] as? Boolean) ?: false
        )
    }

    private fun parseTitle(title: MovieBox2Title): AppAdapter.Item? {
        val id = title.detailPath.takeIf { it.isNotBlank() } ?: title.id
        if (id.isBlank()) return null
        val isTv = title.kind == "series" || title.kind == "short_series"
        val ratingVal = title.rating?.toDoubleOrNull()
        val genresList = title.genres.map { Genre(id = it, name = it) }

        return if (isTv) {
            TvShow(
                id = id,
                title = title.title,
                overview = title.description,
                poster = title.posterUrl,
                banner = title.backdropUrl ?: title.posterUrl,
                rating = ratingVal,
                released = title.releaseDate,
                genres = genresList,
                providerName = name
            )
        } else {
            Movie(
                id = id,
                title = title.title,
                overview = title.description,
                poster = title.posterUrl,
                banner = title.backdropUrl ?: title.posterUrl,
                rating = ratingVal,
                released = title.releaseDate,
                genres = genresList,
                providerName = name
            )
        }
    }

    override suspend fun getHome(): List<Category> {
        val categories = mutableListOf<Category>()
        try {
            val response = api.getHome()
            val data = response["data"] as? Map<String, Any?> ?: emptyMap()
            val operatingList = data["operatingList"] as? List<Map<String, Any?>> ?: emptyList()

            val banners = mutableListOf<MovieBox2Title>()
            val sections = mutableListOf<MovieBox2Section>()

            for (item in operatingList) {
                val type = item["type"]?.toString()
                if (type == "BANNER") {
                    val bannerObj = item["banner"] as? Map<String, Any?>
                    val itemsList = bannerObj?.get("items") as? List<Map<String, Any?>> ?: emptyList()
                    for (bItem in itemsList) {
                        val subj = bItem["subject"] as? Map<String, Any?>
                        if (subj != null) {
                            banners.add(mapTitle(subj))
                        }
                    }
                } else {
                    val subjects = item["subjects"] as? List<Map<String, Any?>>
                    if (!subjects.isNullOrEmpty()) {
                        val sectionTitle = item["title"]?.toString() ?: "Featured"
                        val kind = item["type"]?.toString() ?: "row"
                        sections.add(MovieBox2Section(title = sectionTitle, kind = kind, items = subjects.map { mapTitle(it) }))
                    }
                }
            }

            if (banners.isNotEmpty()) {
                categories.add(Category(name = "Featured", list = banners.mapNotNull { parseTitle(it) }))
            }
            sections.forEach { section ->
                val items = section.items.mapNotNull { parseTitle(it) }
                if (items.isNotEmpty()) {
                    categories.add(Category(name = section.title, list = items))
                }
            }
        } catch (e: Exception) {
            Log.e("MovieBox2Provider", "Error in getHome: ${e.message}")
        }
        return categories
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: SearchFilters?
    ): List<AppAdapter.Item> {
        return try {
            val payload = mapOf("keyword" to query, "page" to page, "perPage" to 28, "subjectType" to 0)
            val response = api.search(payload)
            val data = response["data"] as? Map<String, Any?> ?: emptyMap()
            val itemsList = (data["items"] ?: data["subjectList"] ?: emptyList<Map<String, Any?>>()) as? List<Map<String, Any?>> ?: emptyList()
            itemsList.mapNotNull { mapTitle(it) }.mapNotNull { parseTitle(it) }
        } catch (e: Exception) {
            Log.e("MovieBox2Provider", "Error in search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val payload = mapOf("channelId" to 1, "page" to page, "perPage" to 28, "sort" to "ForYou")
            val response = api.getCatalog(payload)
            val data = response["data"] as? Map<String, Any?> ?: emptyMap()
            val itemsList = (data["items"] ?: data["subjectList"] ?: emptyList<Map<String, Any?>>()) as? List<Map<String, Any?>> ?: emptyList()
            itemsList.mapNotNull { mapTitle(it) }.mapNotNull { parseTitle(it) as? Movie }
        } catch (e: Exception) {
            Log.e("MovieBox2Provider", "Error in getMovies: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val payload = mapOf("channelId" to 2, "page" to page, "perPage" to 28, "sort" to "ForYou")
            val response = api.getCatalog(payload)
            val data = response["data"] as? Map<String, Any?> ?: emptyMap()
            val itemsList = (data["items"] ?: data["subjectList"] ?: emptyList<Map<String, Any?>>()) as? List<Map<String, Any?>> ?: emptyList()
            itemsList.mapNotNull { mapTitle(it) }.mapNotNull { parseTitle(it) as? TvShow }
        } catch (e: Exception) {
            Log.e("MovieBox2Provider", "Error in getTvShows: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val response = api.getDetail(id)
        val data = response["data"] as? Map<String, Any?> ?: emptyMap()
        val subject = data["subject"] as? Map<String, Any?> ?: emptyMap()
        val t = mapTitle(subject)
        val stars = data["stars"] as? List<Map<String, Any?>> ?: emptyList()
        val ratingVal = t.rating?.toDoubleOrNull()
        val genresList = t.genres.map { Genre(id = it, name = it) }
        val castMembers = stars.map { People(id = it["name"]?.toString() ?: "", name = it["name"]?.toString() ?: "", image = it["avatarUrl"]?.toString()) }

        return Movie(
            id = id,
            title = t.title,
            overview = t.description,
            poster = t.posterUrl,
            banner = t.backdropUrl ?: t.posterUrl,
            rating = ratingVal,
            released = t.releaseDate,
            genres = genresList,
            cast = castMembers,
            providerName = name
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val response = api.getDetail(id)
        val data = response["data"] as? Map<String, Any?> ?: emptyMap()
        val subject = data["subject"] as? Map<String, Any?> ?: emptyMap()
        val t = mapTitle(subject)
        val stars = data["stars"] as? List<Map<String, Any?>> ?: emptyList()
        val resource = data["resource"] as? Map<String, Any?> ?: emptyMap()
        val seasonsRaw = resource["seasons"] as? List<Map<String, Any?>> ?: emptyList()

        val ratingVal = t.rating?.toDoubleOrNull()
        val genresList = t.genres.map { Genre(id = it, name = it) }
        val castMembers = stars.map { People(id = it["name"]?.toString() ?: "", name = it["name"]?.toString() ?: "", image = it["avatarUrl"]?.toString()) }

        val seasons = seasonsRaw.map { s ->
            val seNum = (s["se"] as? Number)?.toInt() ?: 1
            val maxEp = (s["maxEp"] as? Number)?.toInt() ?: 1
            val eps = (1..maxEp).map { epNum ->
                Episode(
                    id = "${id}_${seNum}_$epNum",
                    number = epNum,
                    title = "Episode $epNum"
                )
            }
            Season(
                id = "${id}_$seNum",
                number = seNum,
                title = "Season $seNum",
                episodes = eps
            )
        }

        return TvShow(
            id = id,
            title = t.title,
            overview = t.description,
            poster = t.posterUrl,
            banner = t.backdropUrl ?: t.posterUrl,
            rating = ratingVal,
            released = t.releaseDate,
            seasons = seasons,
            genres = genresList,
            cast = castMembers,
            providerName = name
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("_")
        if (parts.size >= 2) {
            val detailPath = parts[0]
            val seasonNum = parts[1].toIntOrNull() ?: 1
            try {
                val response = api.getDetail(detailPath)
                val data = response["data"] as? Map<String, Any?> ?: emptyMap()
                val resource = data["resource"] as? Map<String, Any?> ?: emptyMap()
                val seasonsRaw = resource["seasons"] as? List<Map<String, Any?>> ?: emptyList()
                val s = seasonsRaw.firstOrNull { (it["se"] as? Number)?.toInt() == seasonNum }
                val maxEp = (s?.get("maxEp") as? Number)?.toInt() ?: 1
                return (1..maxEp).map { epNum ->
                    Episode(id = "${detailPath}_${seasonNum}_$epNum", number = epNum, title = "Episode $epNum")
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = id)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = id)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        try {
            val detailPath = when (videoType) {
                is Video.Type.Episode -> videoType.tvShow.id.takeIf { it.isNotBlank() } ?: id.split("_").firstOrNull() ?: id
                else -> id
            }
            val (season, episode) = when (videoType) {
                is Video.Type.Episode -> Pair(videoType.season.number, videoType.number)
                else -> Pair(0, 0)
            }
            val response = api.getPlayback(detailPath, season, episode, 1)
            val data = response["data"] as? Map<String, Any?> ?: emptyMap()
            val streamsRaw = (data["streams"] as? List<Map<String, Any?>> ?: emptyList()) +
                    (data["dash"] as? List<Map<String, Any?>> ?: emptyList()) +
                    (data["hls"] as? List<Map<String, Any?>> ?: emptyList())

            for (s in streamsRaw) {
                val streamUrl = s["url"]?.toString() ?: continue
                if (streamUrl.isBlank()) continue

                val streamId = s["id"]?.toString() ?: ""
                val format = s["format"]?.toString() ?: "MP4"
                val res = s["resolutions"]?.toString() ?: "1080"
                val sizeBytes = (s["size"] as? Number)?.toLong()

                val subtitles = try {
                    val capResp = api.getCaptions(detailPath, streamId, "MP4")
                    val capData = capResp["data"] as? Map<String, Any?> ?: emptyMap()
                    val capsRaw = capData["captions"] as? List<Map<String, Any?>> ?: emptyList()
                    capsRaw.map { cap ->
                        val lan = cap["lan"]?.toString() ?: "en"
                        val label = cap["lanName"]?.toString() ?: lan
                        val url = cap["url"]?.toString() ?: ""
                        Video.Subtitle(
                            label = label,
                            file = url,
                            language = lan,
                            default = lan.lowercase(Locale.US).startsWith("en")
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }

                val formattedSize = if (sizeBytes != null && sizeBytes > 0) {
                    val mb = sizeBytes.toDouble() / (1024.0 * 1024.0)
                    if (mb >= 1000.0) String.format(Locale.US, "%.1f GB", mb / 1024.0)
                    else String.format(Locale.US, "%.0f MB", mb)
                } else null

                val serverName = if (formattedSize != null) {
                    "${res}p ($formattedSize · $format)"
                } else {
                    "${res}p ($format)"
                }

                val server = Video.Server(
                    id = "moviebox2_${streamId}_$res",
                    name = serverName,
                    src = streamUrl
                ).apply {
                    this.video = Video(
                        source = streamUrl,
                        subtitles = subtitles,
                        headers = mapOf(
                            "Referer" to "https://themoviebox.xyz/movies/$detailPath",
                            "User-Agent" to "Mozilla/5.0"
                        ),
                        type = "video/mp4"
                    )
                }
                servers.add(server)
            }
        } catch (e: Exception) {
            Log.e("MovieBox2Provider", "Error fetching servers: ${e.message}")
        }
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return server.video ?: Video(
            source = server.src,
            headers = mapOf("Referer" to "https://themoviebox.xyz/"),
            type = "video/mp4"
        )
    }
}
