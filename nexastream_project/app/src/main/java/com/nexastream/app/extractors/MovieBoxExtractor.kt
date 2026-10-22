package com.nexastream.app.extractors

import android.util.Log
import com.nexastream.app.models.Video

class MovieBoxExtractor : Extractor() {

    override val name = "MovieBox"
    override val mainUrl = "http://127.0.0.1:3001"
    override val aliasUrls = listOf("movie|", "tv|", "movie:", "tv:")

    private val delegate = GengaExtractor()

    fun server(videoType: Video.Type): Video.Server {
        return delegate.server(videoType).copy(id = name, name = name)
    }

    fun servers(videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        // 1. VixSrc as default primary server
        try {
            servers.add(VixSrcExtractor().server(videoType))
        } catch (e: Exception) {
            Log.e("MovieBoxExtractor", "Failed to add VixSrc: ${e.message}")
        }
        // 2. MovieBox as first fallback
        try {
            servers.add(server(videoType))
        } catch (e: Exception) {
            Log.e("MovieBoxExtractor", "Failed to add MovieBox: ${e.message}")
        }
        return servers
    }

    override suspend fun extract(link: String): Video {
        return delegate.extract(link)
    }
}
