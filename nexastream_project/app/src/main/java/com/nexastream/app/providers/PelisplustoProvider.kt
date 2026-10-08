package com.nexastream.app.providers

import android.util.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object PelisplustoProvider : Provider {

    override val name = "Pelisplusto"
    override val baseUrl = "https://pelisplus.to"
    override val language = "es"
    override val logo = "https://pelisplus.to/images/logo2.png"
    private const val TAG = "PelisplustoProvider"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(PelisplustoService::class.java)

    private interface PelisplustoService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()

        val mainPageDeferred = async { service.getPage(baseUrl) }
        val moviesDeferred = async { service.getPage("$baseUrl/peliculas") }
        val seriesDeferred = async { service.getPage("$baseUrl/series") }

        try {
            val mainDocument = mainPageDeferred.await()

            val bannerShows = mainDocument.select("div.home__slider_index div.swiper-slide article").mapNotNull {
                val url = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null

                val banner = it.selectFirst("div.bg")?.attr("style")
                    ?.substringAfter("url(")?.substringBefore(")")
                    ?.removeSurrounding("'")?.removeSurrounding("\"") ?: return@mapNotNull null

                val title = it.selectFirst("h2")?.text()?.substringBefore(" (") ?: return@mapNotNull null
                val id = url.substringAfterLast('/').removeSuffix("/")

                when {
                    url.contains("/pelicula/") -> Movie(id = id, title = title, banner = getAbsoluteUrl(banner))
                    url.contains("/serie/") -> TvShow(id = id, title = title, banner = getAbsoluteUrl(banner))
                    else -> null
                }
            }
            if (bannerShows.isNotEmpty()) {
                categories.add(Category(Category.FEATURED, bannerShows))
            }
        } catch (e: Exception) { Log.e(TAG, "getHome (banners): ${e.message}") }

        try {
            val movies = parseShows(moviesDeferred.await()).filterIsInstance<Movie>()
            if (movies.isNotEmpty()) categories.add(Category("Películas", movies))
        } catch (e: Exception) { Log.e(TAG, "getHome (movies): ${e.message}") }

        try {
            val series = parseShows(seriesDeferred.await()).filterIsInstance<TvShow>()
            if (series.isNotEmpty()) categories.add(Category("Series", series))
        } catch (e: Exception) { Log.e(TAG, "getHome (series): ${e.message}") }

        categories
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return emptyList()
        }

        if (page > 1) {
            return emptyList()
        }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search/$encodedQuery"
        val document = service.getPage(url)
        return parseShows(document)
    }

    private fun parseShows(document: Document): List<AppAdapter.Item> {
        val elements = document.select("article.item.liste.relative a.itemA")

        return elements.mapNotNull {
            val url = it.attr("href")
            val posterUrl = it.selectFirst("img")?.attr("data-src") ?: ""
            val title = it.selectFirst("h2")?.text()?.substringBefore(" (") ?: return@mapNotNull null

            when {
                url.contains("/pelicula/") -> Movie(
                    id = url.substringAfter("/pelicula/").removeSuffix("/"),
                    title = title,
                    poster = posterUrl
                )
                url.contains("/serie/") -> TvShow(
                    id = url.substringAfter("/serie/").removeSuffix("/"),
                    title = title,
                    poster = posterUrl
                )
                else -> null
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas/$page"
        val document = service.getPage(url)
        return parseShows(document).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series/$page"
        val document = service.getPage(url)
        return parseShows(document).filterIsInstance<TvShow>()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/$id" else "$baseUrl/$id/page/$page"
        val document = service.getPage(url)
        val shows = parseShows(document).filterIsInstance<Show>()
        val genreName = id.substringAfter("genero/").replaceFirstChar { it.uppercase() }
        return Genre(id = id, name = genreName, shows = shows)
    }

    private fun getAbsoluteUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return if (url.startsWith("http")) url else "$baseUrl$url"
    }

    override suspend fun getMovie(id: String): Movie {
        val url = if (id.startsWith("http")) id else "$baseUrl/pelicula/$id"
        val document = service.getPage(url)
        val info = document.selectFirst("article.item") ?: return Movie(id = id)

        return Movie(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.description")?.text()?.trim(),
            poster = info.selectFirst("img")?.attr("src")?.let { getAbsoluteUrl(it) },
            banner = info.selectFirst("div.bg")?.attr("style")?.let { 
                it.substringAfter("url(").substringBefore(")").removeSurrounding("'").removeSurrounding("\"") 
            }?.let { getAbsoluteUrl(it) },
            genres = info.select("a[href*='/genero/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/buscar/']").map {
                People(id = it.attr("href"), name = it.text().trim())
            }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/serie/$id"
        val document = service.getPage(url)
        val info = document.selectFirst("article.item") ?: return TvShow(id = id)
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
                    poster = info.selectFirst("img")?.attr("src")?.let { getAbsoluteUrl(it) }
                )
            )
        }

        return TvShow(
            id = id,
            title = info.selectFirst("h1")?.text()?.trim() ?: "",
            overview = info.selectFirst("p.description")?.text()?.trim(),
            poster = info.selectFirst("img")?.attr("src")?.let { getAbsoluteUrl(it) },
            banner = info.selectFirst("div.bg")?.attr("style")?.let { 
                it.substringAfter("url(").substringBefore(")").removeSurrounding("'").removeSurrounding("\"") 
            }?.let { getAbsoluteUrl(it) },
            genres = info.select("a[href*='/genero/']").map {
                Genre(id = it.attr("href"), name = it.text().trim())
            },
            cast = info.select("a[href*='/buscar/']").map {
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
                title = link.selectFirst("span")?.text()?.trim(),
                poster = link.selectFirst("img")?.attr("src")?.let { getAbsoluteUrl(it) }
            )
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.src.isNotEmpty()) {
            return Extractor.extract(server.src, server)
        }
        return Extractor.extract(server.id, server)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return emptyList()
    }
}
