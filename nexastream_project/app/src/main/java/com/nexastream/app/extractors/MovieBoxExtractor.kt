package com.nexastream.app.extractors

import android.util.Log
import com.nexastream.app.models.Video
import com.nexastream.app.providers.MovieBoxProvider

class MovieBoxExtractor : Extractor() {

    override val name = "MovieBox"
    override val mainUrl = "https://themoviebox.xyz"
    override val aliasUrls = listOf("moviebox", "movie|", "tv|", "movie:", "tv:")

    fun server(videoType: Video.Type): Video.Server {
        val mediaId = when (videoType) {
            is Video.Type.Movie -> videoType.id
            is Video.Type.Episode -> videoType.id
        }
        return Video.Server(
            id = name,
            name = name,
            src = mediaId
        )
    }

    suspend fun servers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            MovieBoxProvider.getServers(id, videoType)
        } catch (e: Exception) {
            Log.e("MovieBoxExtractor", "Failed to fetch MovieBox servers: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun extract(link: String): Video {
        return Video(
            source = link,
            headers = mapOf(
                "Referer" to "https://themoviebox.xyz/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            ),
            type = "video/mp4"
        )
    }
}
