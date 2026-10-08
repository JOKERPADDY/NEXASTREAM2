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
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object DoramasflixProvider : Provider {

    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.in"
    override val language = "es"
    override val logo = "https://doramasflix.in/logo.png"

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
        .create(DoramasflixService::class.java)

    private interface DoramasflixService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        val document = service.getPage(baseUrl)
        val categories = mutableListOf<Category>()

        document.select("section").forEach { section ->
            val title = section.selectFirst("h2")?.text() ?: return@forEach
            val shows = parseShows(section.select("article"))
            if (shows.isNotEmpty()) {
                categories.add(Category(title, shows))
            }
        }

        return categories
    }

    private fun parseShows(elements: List<org.jsoup.nodes.Element>): List<Show> {
        return elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            val id = if (href.startsWith("http")) href else "$baseUrl$href"
            val title = element.selectFirst("h3")?.text() ?: element.selectFirst("img")?.attr("alt") ?: ""
            val poster = element.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

            when {
                href.contains("/pelicula/") -> Movie(id = id, title = title, poster = poster)
                href.contains("/serie/") -> TvShow(id = id, title = title, poster = poster)
                else -> null
            }
        }
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return emptyList()
        }

        val url = "$baseUrl/search?q=$query"
        val document = service.getPage(url)
        return parseShows(document.select("article"))
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas?page=$page"
        val document = service.getPage(url)
        return parseShows(document.select("article")).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series?page=$page"
        val document = service.getPage(url)
        return parseShows(document.select("article")).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl$id"
        val document = service.getPage(url)
        val info = document.selectFirst("div.info") ?: return Movie(id = id)

        return Movie(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.description")?.text()?.trim() ?: "",
            poster = info.selectFirst("img.poster")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
            banner = info.selectFirst("div.banner")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
            genres = info.select("a[href*='/genero/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/actor/']").map {
                People(id = it.attr("href"), name = it.text().trim())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl$id"
        val document = service.getPage(url)
        val info = document.selectFirst("div.info") ?: return TvShow(id = id)
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
                    title = "Temporada $seasonNumber",
                    poster = info.selectFirst("img.poster")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
                )
            )
        }

        return TvShow(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.description")?.text()?.trim() ?: "",
            poster = info.selectFirst("img.poster")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
            banner = info.selectFirst("div.banner")?.attr("style")?.let { 
                it.substringAfter("url('").substringBefore("')") 
            }?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
            genres = info.select("a[href*='/genero/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/actor/']").map {
                People(id = it.attr("href"), name = it.text().trim())
            },
            seasons = seasons.values.sortedBy { it.number }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val url = if (seasonId.startsWith("http")) seasonId else "$baseUrl$seasonId"
        val document = service.getPage(url)

        return document.select("div.episodes a").mapNotNull { link ->
            val episodeNumber = Regex("""episodio-(\d+)""").find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            Episode(
                id = link.attr("href"),
                number = episodeNumber,
                title = link.selectFirst("span")?.text()?.trim() ?: "",
                poster = link.selectFirst("img")?.attr("src")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl$id" else "$baseUrl$id?page=$page"
        val document = service.getPage(url)
        val shows = parseShows(document.select("article")).filterIsInstance<Show>()
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
