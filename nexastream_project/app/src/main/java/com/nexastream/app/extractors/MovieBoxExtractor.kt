package com.nexastream.app.extractors

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
        return listOf(server(videoType))
    }

    override suspend fun extract(link: String): Video {
        return delegate.extract(link)
    }
}
