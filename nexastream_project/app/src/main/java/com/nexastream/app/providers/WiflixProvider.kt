package com.nexastream.app.providers

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.extractors.Extractor
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.Season
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.DnsResolver
import com.nexastream.app.models.SearchFilters
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object WiflixProvider : Provider {

    override val name = "Wiflix"
    override val baseUrl = "https://flemmix.team/"
    override val language = "fr"
    override val logo = "https://flemmix.team/logo.png"

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
        .create(WiflixService::class.java)

    private interface WiflixService {
        @GET
        suspend fun getPage(@Url url: String): Document

        @GET
        suspend fun search(
            @Url url: String,
            @Query("story") query: String,
            @Query("search_start") page: Int
        ): Document
    }

    override suspend fun getHome(): List<Category> {
        val document = service.getPage(baseUrl)

        val categories = mutableListOf<Category>()

        document.select("div.block-main").forEachIndexed { index, block ->
            val title = block.selectFirst("h2")?.text() ?: "Category $index"
            val shows = block.select("div.mov").mapNotNull {
                val id = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: ""
                val title = it.selectFirst("a.mov-t")?.text() ?: ""
                val poster = it.selectFirst("img")?.attr("src")?.let { baseUrl + it }

                when {
                    id.contains("film") -> Movie(id = id, title = title, poster = poster)
                    id.contains("serie") -> TvShow(id = id, title = title, poster = poster)
                    else -> null
                }
            }

            if (shows.isNotEmpty()) {
                categories.add(Category(title, shows))
            }
        }

        return categories
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return emptyList()
        }

        val document = service.search("$baseUrl/", query, page)

        return document.select("div.mov").mapNotNull {
            val id = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: ""
            val title = it.selectFirst("a.mov-t")?.text() ?: ""
            val poster = it.selectFirst("img")?.attr("src")?.let { baseUrl + it }

            when {
                id.contains("film") -> Movie(id = id, title = title, poster = poster)
                id.contains("serie") -> TvShow(id = id, title = title, poster = poster)
                else -> null
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page == 1) "$baseUrl/films" else "$baseUrl/films/page/$page"
        val document = service.getPage(url)

        return document.select("div.mov").mapNotNull {
            val id = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: ""
            val title = it.selectFirst("a.mov-t")?.text() ?: ""
            val poster = it.selectFirst("img")?.attr("src")?.let { baseUrl + it }

            if (id.contains("film")) {
                Movie(id = id, title = title, poster = poster)
            } else null
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series/page/$page"
        val document = service.getPage(url)

        return document.select("div.mov").mapNotNull {
            val id = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: ""
            val title = it.selectFirst("a.mov-t")?.text() ?: ""
            val poster = it.selectFirst("img")?.attr("src")?.let { baseUrl + it }

            if (id.contains("serie")) {
                TvShow(id = id, title = title, poster = poster)
            } else null
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)
        val info = document.selectFirst("div.film-info") ?: return Movie(id = id)

        return Movie(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.description")?.text()?.trim() ?: "",
            poster = info.selectFirst("img.poster")?.attr("src")?.let { baseUrl + it },
            banner = info.selectFirst("div.banner")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            }?.let { baseUrl + it },
            genres = info.select("a[href*='/genre/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/actor/']").map {
                People(id = it.attr("href"), name = it.text().trim())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val document = service.getPage(url)
        val info = document.selectFirst("div.film-info") ?: return TvShow(id = id)
        val seasons = linkedMapOf<String, Season>()

        document.select("div.seasons a").forEach { link ->
            val href = link.attr("href")
            val seasonNumber = Regex("""season-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEach

            seasons.putIfAbsent(
                href,
                Season(
                    id = href,
                    number = seasonNumber,
                    title = "Season $seasonNumber",
                    poster = info.selectFirst("img.poster")?.attr("src")?.let { baseUrl + it }
                )
            )
        }

        return TvShow(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.description")?.text()?.trim() ?: "",
            poster = info.selectFirst("img.poster")?.attr("src")?.let { baseUrl + it },
            banner = info.selectFirst("div.banner")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            }?.let { baseUrl + it },
            genres = info.select("a[href*='/genre/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/actor/']").map {
                People(id = it.attr("href"), name = it.text().trim())
            },
            seasons = seasons.values.sortedBy { it.number }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val url = if (seasonId.startsWith("http")) seasonId else "$baseUrl/$seasonId"
        val document = service.getPage(url)

        return document.select("div.episodes a").mapNotNull { link ->
            val episodeNumber = Regex("""episodio-(\d+)""").find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            Episode(
                id = link.attr("href"),
                number = episodeNumber,
                title = link.selectFirst("span")?.text()?.trim() ?: "",
                poster = link.selectFirst("img")?.attr("src")?.let { baseUrl + it }
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/$id" else "$baseUrl/$id/page/$page"
        val document = service.getPage(url)
        val shows = document.select("div.mov").mapNotNull {
            val showId = it.selectFirst("a.mov-t")?.attr("href")?.substringAfterLast("/") ?: ""
            val title = it.selectFirst("a.mov-t")?.text() ?: ""
            val poster = it.selectFirst("img")?.attr("src")?.let { baseUrl + it }

            when {
                showId.contains("film") -> Movie(id = showId, title = title, poster = poster)
                showId.contains("serie") -> TvShow(id = showId, title = title, poster = poster)
                else -> null
            }
        }.filterIsInstance<Show>()

        val genreName = id.substringAfterLast("/").replace("-", " ")
        return Genre(id = id, name = genreName, shows = shows)
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
