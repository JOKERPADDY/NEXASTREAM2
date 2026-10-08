package com.nexastream.app.providers

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.extractors.Extractor
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object CableVisionHDProvider : Provider {

    override val name = "CableVisionHD"
    override val baseUrl = "https://www.cablevisionhd.com"
    override val language = "es"
    override val logo = "https://i.ibb.co/4gMQkN2b/imagen-2025-09-05-212536248.png"

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(CableVisionHDService::class.java)

    private interface CableVisionHDService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        val document = service.getPage(baseUrl)
        val categories = mutableListOf<Category>()

        // Parse live TV channels
        val channels = parseChannels(document)
        if (channels.isNotEmpty()) {
            categories.add(Category("Live TV", channels))
        }

        return categories
    }

    private fun parseChannels(document: Document): List<TvShow> {
        val channels = mutableListOf<TvShow>()

        document.select("a[href*='/ver/']").forEach { link ->
            val href = link.attr("href")
            val title = link.text().trim().ifEmpty { link.selectFirst("img")?.attr("alt") ?: "" }
            val poster = link.selectFirst("img")?.attr("src")?.let { 
                if (it.startsWith("http")) it else "$baseUrl$it" 
            }

            if (title.isNotEmpty() && href.isNotEmpty()) {
                val id = if (href.startsWith("http")) href else "$baseUrl$href"
                channels.add(TvShow(id = id, title = title, poster = poster))
            }
        }

        return channels
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        return emptyList()
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return emptyList()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return parseChannels(service.getPage(baseUrl))
    }

    override suspend fun getMovie(id: String): Movie {
        return Movie(id = id)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl$id"
        val document = service.getPage(url)
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("img")?.attr("src")?.let { 
            if (it.startsWith("http")) it else "$baseUrl$it" 
        }

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            overview = ""
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return Genre(id = id, name = "", shows = emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.src.isNotEmpty()) {
            return Extractor.extract(server.src, server)
        }
        return Extractor.extract(server.id, server)
    }
}
