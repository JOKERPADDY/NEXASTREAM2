package com.nexastream.app.providers

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import android.util.Log
import kotlinx.coroutines.coroutineScope

object ShortTvProvider : Provider {
    override val baseUrl: String = "https://api4.aoneroom.com/"
    override val name: String = "ShortTV"
    override val logo: String = ""
    override val language: String = "en"

    private val service = ShortTvService.build()

    override suspend fun getHome(): List<Category> = coroutineScope {
        try {
            Log.d("ShortTvProvider", "getHome() calling operating list")
            val response = service.getOperatingList(tabId = 13)
            Log.d("ShortTvProvider", "getHome() response code: ${response.code}, data null: ${response.data == null}")
            if (!response.isSuccess || response.data == null) return@coroutineScope emptyList()

            response.data.items?.mapNotNull { item ->
                val subjects = item.subjects?.map { subj ->
                    TvShow(
                        id = "short_${subj.subjectId ?: ""}",
                        title = subj.title ?: "",
                        rating = subj.imdbRatingValue?.toDoubleOrNull(),
                        poster = subj.cover?.url,
                        banner = subj.bannerImage?.url,
                        released = subj.releaseDate,
                        providerName = name
                    ).apply {
                        itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                } ?: emptyList()

                if (subjects.isEmpty()) return@mapNotNull null

                Category(
                    name = item.title ?: "Short Dramas",
                    list = subjects
                ).apply {
                    itemType = AppAdapter.Type.CATEGORY_TV_ITEM
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val cleanId = id.removePrefix("short_")
        val response = service.getShortTvInfo(subjectId = cleanId)
        if (!response.isSuccess || response.data == null) throw Exception("Failed to fetch show info")

        val data = response.data
        return TvShow(
            id = id,
            title = data.title ?: "",
            overview = data.description,
            released = data.releaseDate,
            rating = data.totalEpisode?.toDouble(), // Using total episodes as rating proxy if needed, or just dummy
            poster = data.cover?.url,
            banner = data.cover?.url,
            providerName = name,
            seasons = listOf(
                Season(
                    id = id,
                    number = 1,
                    title = "Season 1",
                    poster = data.cover?.url
                )
            )
        ).apply {
            seasons.forEach { it.tvShow = this }
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val cleanId = seasonId.removePrefix("short_")
        val response = service.getShortTvEpisodes(subjectId = cleanId)
        if (!response.isSuccess || response.data == null) return emptyList()

        return response.data.items?.map { ep ->
            Episode(
                id = ep.videoId ?: "${ep.subjectId}-${ep.episode}",
                number = ep.episode ?: 0,
                title = "Episode ${ep.episode}",
                poster = ep.video?.cover?.url,
                tvShow = TvShow(id = ep.subjectId ?: ""),
            ).apply {
                itemType = AppAdapter.Type.EPISODE_TV_ITEM
            }
        } ?: emptyList()
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (videoType !is Video.Type.Episode) return emptyList()

        val subjectId = videoType.tvShow.id.removePrefix("short_")
        val response = service.getShortTvEpisodes(subjectId = subjectId)
        if (!response.isSuccess || response.data == null) return emptyList()

        val episodeData = response.data.items?.find { it.videoId == id || "${it.subjectId}-${it.episode}" == id }
            ?: return emptyList()

        return episodeData.video?.addressList?.mapIndexed { index, video ->
            Video.Server(
                id = video.url ?: "",
                name = "Server ${index + 1} (${video.resolution ?: "Auto"})",
                src = video.url ?: ""
            )
        } ?: emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Video(
            source = server.src,
            type = if (server.src.contains(".m3u8")) "application/x-mpegURL" else "video/mp4"
        )
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> = emptyList()
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = throw Exception("Not supported")
    override suspend fun getGenre(id: String, page: Int): Genre = throw Exception("Not supported")
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not supported")
}
