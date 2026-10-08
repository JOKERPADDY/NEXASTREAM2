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
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object AnimeFlvProvider : Provider {

    override val name = "AnimeFLV"
    override val baseUrl = "https://www3.animeflv.net"
    override val language = "es"
    override val logo = "https://www3.animeflv.net/assets/animeflv/img/logo.png"

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
        .create(AnimeFlvService::class.java)

    private interface AnimeFlvService {
        @GET
        suspend fun getPage(@Url url: String): Document

        @GET("browse")
        suspend fun search(@Query("q") query: String, @Query("page") page: Int): Document

        @GET("browse")
        suspend fun getTvShows(@Query("order") order: String = "rating", @Query("page") page: Int): Document

        @GET("anime/{id}")
        suspend fun getShowDetails(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        val categories = mutableListOf<Category>()

        try {
            val addedDocument = service.getPage("$baseUrl/browse?order=added&page=1")
            val bannerShows = addedDocument.select("ul.ListAnimes li article").mapNotNull { element ->
                val url = element.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null
                val posterUrl = element.selectFirst("a div.Image figure img")?.attr("src")
                val finalPoster = if (posterUrl?.startsWith("http") == true) posterUrl else posterUrl?.let { "$baseUrl$it" }

                TvShow(
                    id = url.substringAfterLast("/"),
                    title = element.selectFirst("h3.Title")?.text() ?: "",
                    poster = finalPoster ?: ""
                )
            }

            if (bannerShows.isNotEmpty()) {
                categories.add(Category("Latest", bannerShows))
            }
        } catch (e: Exception) {
            // Ignore errors
        }

        return categories
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return emptyList()
        }

        val document = service.search(query, page)
        return document.select("ul.ListAnimes li article").mapNotNull { element ->
            val url = element.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null
            val posterUrl = element.selectFirst("a div.Image figure img")?.attr("src")
            val finalPoster = if (posterUrl?.startsWith("http") == true) posterUrl else posterUrl?.let { "$baseUrl$it" }

            TvShow(
                id = url.substringAfterLast("/"),
                title = element.selectFirst("h3.Title")?.text() ?: "",
                poster = finalPoster ?: ""
            )
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return emptyList()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = service.getTvShows(page = page)
        return document.select("ul.ListAnimes li article").mapNotNull { element ->
            val url = element.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null
            val posterUrl = element.selectFirst("a div.Image figure img")?.attr("src")
            val finalPoster = if (posterUrl?.startsWith("http") == true) posterUrl else posterUrl?.let { "$baseUrl$it" }

            TvShow(
                id = url.substringAfterLast("/"),
                title = element.selectFirst("h3.Title")?.text() ?: "",
                poster = finalPoster ?: ""
            )
        }
    }

    override suspend fun getMovie(id: String): Movie {
        return Movie(id = id)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/anime/$id"
        val document = service.getShowDetails(url)
        val info = document.selectFirst("div.AnimeCover") ?: return TvShow(id = id)
        val seasons = linkedMapOf<String, Season>()

        document.select("ul.ListEpisodios li").forEach { episode ->
            val href = episode.selectFirst("a")?.attr("href") ?: return@forEach
            val episodeText = episode.selectFirst("p")?.text() ?: ""
            val episodeNumber = Regex("""Episodio (\d+)""").find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEach

            // Treat each episode as a "season" for simplicity
            seasons.putIfAbsent(
                href,
                Season(
                    id = href,
                    number = episodeNumber,
                    title = "Episode $episodeNumber",
                    poster = info.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
                )
            )
        }

        return TvShow(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.Description")?.text()?.trim() ?: "",
            poster = info.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
            banner = info.selectFirst("div.AnimeCover")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
            genres = info.select("nav.Nvgnrs a").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = emptyList(),
            seasons = seasons.values.sortedBy { it.number }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val url = if (seasonId.startsWith("http")) seasonId else "$baseUrl$seasonId"
        val document = service.getPage(url)

        return document.select("ul.ListEpisodios li").mapNotNull { episode ->
            val href = episode.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val episodeText = episode.selectFirst("p")?.text() ?: ""
            val episodeNumber = Regex("""Episodio (\d+)""").find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            Episode(
                id = href,
                number = episodeNumber,
                title = "Episode $episodeNumber",
                poster = ""
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/browse?genre[]=$id" else "$baseUrl/browse?genre[]=$id&page=$page"
        val document = service.getPage(url)
        val shows = document.select("ul.ListAnimes li article").mapNotNull { element ->
            val url = element.selectFirst("div.Description a.Button")?.attr("href") ?: return@mapNotNull null
            val posterUrl = element.selectFirst("a div.Image figure img")?.attr("src")
            val finalPoster = if (posterUrl?.startsWith("http") == true) posterUrl else posterUrl?.let { "$baseUrl$it" }

            TvShow(
                id = url.substringAfterLast("/"),
                title = element.selectFirst("h3.Title")?.text() ?: "",
                poster = finalPoster ?: ""
            )
        }

        return Genre(id = id, name = id.replace("-", " "), shows = shows)
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
