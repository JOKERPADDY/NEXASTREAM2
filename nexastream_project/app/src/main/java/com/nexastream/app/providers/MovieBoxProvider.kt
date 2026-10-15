package com.nexastream.app.providers

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

object MovieBoxProvider : Provider {

    override val baseUrl = "http://127.0.0.1:3001"
    override val name = "MovieBox"
    override val logo = "https://www.moviebox.ph/favicon.ico"
    override val language = "en"

    private val extractor by lazy { GengaExtractor() }

    override suspend fun getHome(): List<Category> = GengaProvider.getHome()

    override suspend fun search(
        query: String,
        page: Int,
        filters: SearchFilters?
    ): List<AppAdapter.Item> = GengaProvider.search(query, page, filters)

    override suspend fun getMovies(page: Int): List<Movie> = GengaProvider.getMovies(page)

    override suspend fun getTvShows(page: Int): List<TvShow> = GengaProvider.getTvShows(page)

    override suspend fun getMovie(id: String): Movie = GengaProvider.getMovie(id)

    override suspend fun getTvShow(id: String): TvShow = GengaProvider.getTvShow(id)

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = GengaProvider.getEpisodesBySeason(seasonId)

    override suspend fun getGenre(id: String, page: Int): Genre = GengaProvider.getGenre(id, page)

    override suspend fun getPeople(id: String, page: Int): People = GengaProvider.getPeople(id, page)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return extractor.servers(videoType)
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return extractor.extract(server.src)
    }
}
