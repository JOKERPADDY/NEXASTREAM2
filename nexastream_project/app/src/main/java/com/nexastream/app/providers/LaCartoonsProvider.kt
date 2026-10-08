package com.nexastream.app.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
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
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.DnsResolver
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.Locale

object LaCartoonsProvider : Provider {

    override val name = "La Cartoons"
    override val baseUrl = "https://www.lacartoons.com"
    override val language = "es"
    override val logo: String get() = "https://images2.imgbox.com/fc/26/S7f7dn42_o.png"

    private val client: OkHttpClient = getOkHttpClient()
    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
    private val service = retrofit.create(Service::class.java)

    private fun getOkHttpClient(): OkHttpClient {
        val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
        val builder = OkHttpClient.Builder()
            .cache(appCache)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
        return builder.dns(DnsResolver.doh).build()
    }

    private interface Service {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val shows = getTvShows(page = 1)
            listOf(Category(name = "Series", list = shows))
        } catch (_: Exception) { emptyList() }
    }

    private fun parseHomeShows(doc: Document): List<TvShow> {
        val list = mutableListOf<TvShow>()
        val containers = doc.select("div.conjuntos-series")
        for (container in containers) {
            val links = container.select("a[href^=/serie/], a[href*=/serie/]")
            for (a in links) {
                val href = a.attr("href")
                if (href.isBlank()) continue
                val card = a.select("div.serie").firstOrNull() ?: continue
                val img = card.select("img").firstOrNull()?.attr("src").orEmpty()
                val title = card.select("p.nombre-serie").firstOrNull()?.text() ?: ""
                val absolutePoster = if (img.startsWith("http")) img else "$baseUrl$img"
                val absoluteId = if (href.startsWith("http")) href else "$baseUrl$href"
                list.add(
                    TvShow(
                        id = absoluteId,
                        title = title,
                        poster = absolutePoster,
                        banner = absolutePoster,
                    )
                )
            }
        }
        return list
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.id }
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return try {
                val doc = service.getPage(baseUrl)
                val genres = doc.select("ul.botontes-categorias li form").mapNotNull { form ->
                    val value = form.select("input[name=Categoria_id]").firstOrNull()?.attr("value") ?: return@mapNotNull null
                    val name = form.select("button[type=submit]").firstOrNull()?.text()
                        ?: form.select("input[type=submit]").firstOrNull()?.attr("value")
                        ?: return@mapNotNull null
                    Genre(id = value, name = name)
                }
                genres
            } catch (_: Exception) { emptyList() }
        }

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/?Titulo=$encoded"
            val doc = service.getPage(url)
            val all = parseHomeShows(doc)
            if (page > 1) emptyList() else all
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page <= 1) baseUrl else "$baseUrl/?page=$page"
            val doc = service.getPage(url)
            parseHomeShows(doc)
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun getMovie(id: String): Movie {
        throw Exception("La Cartoons no ofrece películas, solo series")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl$id"
        val doc = service.getPage(url)

        val posterUrl = doc.select("div.contenedor-informacion-serie img").firstOrNull()?.attr("src").orEmpty()
        val title = doc.select("h2.subtitulo-serie-seccion").firstOrNull()?.ownText()?.trim()
            ?: doc.select("p.nombre-serie").firstOrNull()?.text()?.trim()
            ?: doc.select("h1,h2,h3").firstOrNull()?.text()?.trim().orEmpty()
        val infoSection = doc.select("div.informacion-serie-seccion").firstOrNull()
        val overview = infoSection?.select("p")?.firstOrNull { it.text().startsWith("Reseña") }
            ?.select("span")?.firstOrNull()?.text()
        val ratingText = infoSection?.select("span.valoracion1")?.firstOrNull()?.ownText()?.trim()
        val rating = ratingText?.toDoubleOrNull()

        val seasons = mutableListOf<Season>()
        val temporadaHeaders = doc.select("section.contenedor-episodio-temporada h4.accordion")
        var seasonCounter = 0
        for (h4 in temporadaHeaders) {
            val text = h4.text().trim()
            val seasonNumber = Regex("Temporada\\s+(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (++seasonCounter)
            val seasonId = buildSeasonId(url, seasonNumber)
            seasons.add(Season(id = seasonId, number = seasonNumber, title = "Temporada $seasonNumber"))
        }
        if (seasons.isEmpty()) {
            val seasonId = buildSeasonId(url, 1)
            seasons.add(Season(id = seasonId, number = 1, title = "Temporada 1"))
        }

        return TvShow(
            id = url,
            title = title,
            poster = if (posterUrl.startsWith("http")) posterUrl else "$baseUrl$posterUrl",
            overview = overview,
            rating = rating,
            seasons = seasons
        )
    }

    private fun buildSeasonId(seriesUrl: String, seasonNumber: Int): String {
        return "$seriesUrl?t=$seasonNumber"
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val doc = service.getPage(seasonId)
        val seasonNumber = seasonId.substringAfterLast("?t=").toIntOrNull()
        val episodes = mutableListOf<Episode>()
        val panels = doc.select("section.contenedor-episodio-temporada div.episodio-panel")
        val episodeLinks = if (seasonNumber != null && panels.size >= seasonNumber) {
            panels.get(seasonNumber - 1).select("ul.listas-de-episodion li a")
        } else if (seasonNumber != null) {
            doc.select("ul.listas-de-episodion li a[href*=?t=$seasonNumber]")
        } else {
            doc.select("ul.listas-de-episodion li a")
        }
        for (a in episodeLinks) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val text = a.text().trim()
            val number = extractEpisodeNumber(text)
            val absoluteId = if (href.startsWith("http")) href else "$baseUrl$href"
            episodes.add(
                Episode(
                    id = absoluteId,
                    number = number,
                    title = text
                )
            )
        }
        return episodes
    }

    private fun extractEpisodeNumber(text: String): Int {
        val cap = text.substringAfter("Capitulo ", "").substringBefore("-").trim()
        return cap.toIntOrNull() ?: 0
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val home = service.getPage(baseUrl)
            val formEl = home.select("ul.botontes-categorias li form").firstOrNull { form ->
                form.select("input[name=Categoria_id]").firstOrNull()?.attr("value") == id
            }
            val name = formEl?.let { form ->
                form.select("button[type=submit]").firstOrNull()?.text()
                    ?: form.select("input[type=submit]").firstOrNull()?.attr("value")
            } ?: id

            val url = if (page <= 1) "$baseUrl/?Categoria_id=$id" else "$baseUrl/?Categoria_id=$id&page=$page"
            val doc = service.getPage(url)
            val shows = parseHomeShows(doc)
            Genre(id = id, name = name, shows = shows)
        } catch (_: Exception) { Genre(id = id, name = id, shows = emptyList()) }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw Exception("Esta función no está disponible en La Cartoons")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = if (id.startsWith("http")) id else "$baseUrl$id"
        val doc = service.getPage(url)
        val iframe = doc.select("iframe[src]").firstOrNull()?.attr("src").orEmpty()
        val finalUrl = if (iframe.startsWith("http")) iframe else "$iframe"
        return listOfNotNull(
            if (finalUrl.isNotBlank()) {
                try {
                    val serverName = finalUrl.toHttpUrl().host
                        .replaceFirst("www.", "")
                        .substringBefore(".")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                    Video.Server(
                        id = finalUrl,
                        name = serverName,
                        src = finalUrl
                    )
                } catch (e: Exception) {
                    null
                }
            } else null
        )
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }
}
