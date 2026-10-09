package com.nexastream.app.providers

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.extractors.MovieBoxExtractor
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.providers.moviebox.MovieBoxApi

object MovieBoxProvider : Provider {

    override val baseUrl = "http://127.0.0.1:3000"
    override val name = "MovieBox"
    override val logo = "https://raw.githubusercontent.com/MangaD/logos/main/moviebox.png"
    override val language = "en"

    private val api by lazy { MovieBoxApi.create(baseUrl = "$baseUrl/") }
    private val extractor by lazy { MovieBoxExtractor() }

    override suspend fun getHome(): List<Category> {
        val trendingResponse = try {
            api.getTrending()
        } catch (_: Exception) {
            null
        }

        val subjects = trendingResponse?.data?.items
            ?: trendingResponse?.data?.list
            ?: emptyList()

        val items = subjects.map { subject ->
            if (subject.isTvShow) {
                TvShow(
                    id = subject.id,
                    title = subject.name,
                    poster = subject.poster,
                    banner = subject.poster,
                    overview = subject.description,
                    rating = subject.rating.toDoubleOrNull(),
                    released = subject.year
                )
            } else {
                Movie(
                    id = subject.id,
                    title = subject.name,
                    poster = subject.poster,
                    banner = subject.poster,
                    overview = subject.description,
                    rating = subject.rating.toDoubleOrNull(),
                    released = subject.year
                )
            }
        }

        return listOf(
            Category(
                name = Category.FEATURED,
                list = items
            )
        )
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: SearchFilters?
    ): List<AppAdapter.Item> {
        val searchResponse = try {
            api.search(query, page)
        } catch (_: Exception) {
            return emptyList()
        }

        val items = searchResponse.data?.items ?: searchResponse.data?.list ?: emptyList()
        return items.map { subject ->
            if (subject.isTvShow) {
                TvShow(
                    id = subject.id,
                    title = subject.name,
                    poster = subject.poster,
                    banner = subject.poster,
                    overview = subject.description,
                    rating = subject.rating.toDoubleOrNull(),
                    released = subject.year
                )
            } else {
                Movie(
                    id = subject.id,
                    title = subject.name,
                    poster = subject.poster,
                    banner = subject.poster,
                    overview = subject.description,
                    rating = subject.rating.toDoubleOrNull(),
                    released = subject.year
                )
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val home = getHome()
        return home.flatMap { category ->
            category.list.filterIsInstance<Movie>()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val home = getHome()
        return home.flatMap { category ->
            category.list.filterIsInstance<TvShow>()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val detailResponse = api.getDetail(id)
        val subject = detailResponse.data ?: throw IllegalStateException("Movie not found: $id")
        return Movie(
            id = subject.id,
            title = subject.name,
            poster = subject.poster,
            banner = subject.poster,
            overview = subject.description,
            rating = subject.rating.toDoubleOrNull(),
            released = subject.year
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val detailResponse = api.getDetail(id)
        val subject = detailResponse.data ?: throw IllegalStateException("TvShow not found: $id")
        return TvShow(
            id = subject.id,
            title = subject.name,
            poster = subject.poster,
            banner = subject.poster,
            overview = subject.description,
            rating = subject.rating.toDoubleOrNull(),
            released = subject.year
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
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
