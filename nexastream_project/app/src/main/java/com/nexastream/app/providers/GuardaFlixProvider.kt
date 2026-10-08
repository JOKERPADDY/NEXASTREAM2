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
import com.nexastream.app.models.Show
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

object GuardaFlixProvider : Provider {

    override val name = "GuardaFlix"
    override val baseUrl = "https://guardaplay.live"
    override val language = "it"
    override val logo = "$baseUrl/wp-content/uploads/2021/05/cropped-Guarda-Flix-2.png"

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
        .create(GuardaFlixService::class.java)

    private interface GuardaFlixService {
        @GET
        suspend fun getPage(@Url url: String): Document

        @GET
        suspend fun search(@Query("s") query: String): Document
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseUrl.trimEnd('/') + url
            url.isBlank() -> ""
            else -> baseUrl.trimEnd('/') + "/" + url.trimStart('/')
        }
    }

    override suspend fun getHome(): List<Category> {
        val document = service.getPage(baseUrl)
        val categories = mutableListOf<Category>()

        document.select("section.section.movies").forEach { section ->
            val title = section.selectFirst("header .section-title")?.text()?.trim() ?: return@forEach
            val items = section.select(".post-lst li").mapNotNull { el -> parseGridItem(el) }
            if (items.isNotEmpty()) {
                categories.add(Category(title, items))
            }
        }

        return categories
    }

    private fun parseGridItem(element: org.jsoup.nodes.Element): Show? {
        val linkElement = element.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        val id = normalizeUrl(href)
        val title = element.selectFirst("h3")?.text() ?: element.selectFirst("img")?.attr("alt") ?: ""
        val poster = element.selectFirst("img")?.attr("src")?.let { normalizeUrl(it) }

        return when {
            href.contains("/film/") -> Movie(id = id, title = title, poster = poster) as Show
            href.contains("/serie/") -> TvShow(id = id, title = title, poster = poster) as Show
            else -> null
        }
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return emptyList()
        }

        val document = service.search(query)
        return document.select(".post-lst li").mapNotNull { parseGridItem(it) }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page == 1) "$baseUrl/film/" else "$baseUrl/page/$page/"
        val document = service.getPage(url)
        return document.select(".post-lst li").mapNotNull { parseGridItem(it) }.filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page == 1) "$baseUrl/serie/" else "$baseUrl/page/$page/"
        val document = service.getPage(url)
        return document.select(".post-lst li").mapNotNull { parseGridItem(it) }.filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val url = normalizeUrl(id)
        val document = service.getPage(url)
        val info = document.selectFirst("div.info") ?: return Movie(id = id)

        return Movie(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.description")?.text()?.trim() ?: "",
            poster = info.selectFirst("img.poster")?.attr("src")?.let { normalizeUrl(it) },
            banner = info.selectFirst("div.banner")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            }?.let { normalizeUrl(it) },
            genres = info.select("a[href*='/genre/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/actor/']").map {
                People(id = it.attr("href"), name = it.text().trim())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = normalizeUrl(id)
        val document = service.getPage(url)
        val info = document.selectFirst("div.info") ?: return TvShow(id = id)
        val seasons = linkedMapOf<String, Season>()

        document.select("div.seasons a").forEach { link ->
            val href = link.attr("href")
            val seasonNumber = Regex("""stagione-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEach

            seasons.putIfAbsent(
                href,
                Season(
                    id = href,
                    number = seasonNumber,
                    title = "Stagione $seasonNumber",
                    poster = info.selectFirst("img.poster")?.attr("src")?.let { normalizeUrl(it) }
                )
            )
        }

        return TvShow(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.description")?.text()?.trim() ?: "",
            poster = info.selectFirst("img.poster")?.attr("src")?.let { normalizeUrl(it) },
            banner = info.selectFirst("div.banner")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            }?.let { normalizeUrl(it) },
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
        val url = normalizeUrl(seasonId)
        val document = service.getPage(url)

        return document.select("div.episodes a").mapNotNull { link ->
            val episodeNumber = Regex("""episodio-(\d+)""").find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            Episode(
                id = link.attr("href"),
                number = episodeNumber,
                title = link.selectFirst("span")?.text()?.trim() ?: "",
                poster = link.selectFirst("img")?.attr("src")?.let { normalizeUrl(it) }
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) normalizeUrl(id) else "$baseUrl/page/$page/"
        val document = service.getPage(url)
        val shows = document.select(".post-lst li").mapNotNull { parseGridItem(it) }.filterIsInstance<Show>()
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
