package com.nexastream.app.providers

import com.nexastream.app.models.SearchFilters
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.nexastream.app.BuildConfig
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.extractors.Extractor
import com.nexastream.app.extractors.VixSrcExtractor
import com.nexastream.app.extractors.MovieBoxExtractor
import com.nexastream.app.extractors.VidsrcToExtractor
import com.nexastream.app.extractors.VidsrcNetExtractor
import com.nexastream.app.extractors.VidsrcRuExtractor
import com.nexastream.app.extractors.TwoEmbedExtractor
import com.nexastream.app.extractors.VidLinkExtractor
import com.nexastream.app.extractors.MoviesapiExtractor
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.providers.Provider
import com.nexastream.app.providers.ProviderConfigUrl
import com.nexastream.app.utils.DnsResolver
import com.nexastream.app.utils.NetworkClient
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.TmdbUtils
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import retrofit2.http.Headers
import java.util.concurrent.TimeUnit

object SflixProvider : Provider, ProviderConfigUrl {

    override val defaultBaseUrl = "https://sflixz.day/"
    override val baseUrl: String
        get() {
            val cacheURL = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            return cacheURL.ifEmpty { defaultBaseUrl }
        }

    override val name = "SFlix"
    override val logo = "https://img.sflix.to/xxrz/400x400/100/66/35/66356c25ce98cb12993249e21742b129/66356c25ce98cb12993249e21742b129.png"
    override val language = "en"
    override val changeUrlMutex = Mutex()

    private var service = SflixService.build(baseUrl)


    override suspend fun getHome(): List<Category> = coroutineScope {
        val document = service.getHome()
        val categories = mutableListOf<Category>()

        val sliders = document.select("div.swiper-wrapper > div.swiper-slide")
        if (sliders.isNotEmpty()) {
            val featuredList = sliders.map {
                async {
                    val id = it.selectFirst("a")?.attr("href") ?: ""
                    val title = it.selectFirst("h2.film-title")?.text() ?: ""
                    val overview = it.selectFirst("p.sc-desc")?.text()
                    val info = it.select("div.sc-detail > div.scd-item").toInfo()
                    val poster = it.selectFirst("img.film-poster-img")?.attr("src")
                    val banner = it.selectFirst("div.slide-photo img")?.attr("src")
                    
                    val tmdbMatch = if (UserPreferences.enableTmdb) {
                        if (it.isMovie()) TmdbUtils.getMovie(title, info.released?.toIntOrNull())
                        else TmdbUtils.getTvShow(title, info.released?.toIntOrNull())
                    } else null

                    if (it.isMovie()) {
                        Movie(
                            id = id,
                            title = title,
                            overview = tmdbMatch?.overview ?: overview,
                            released = tmdbMatch?.released?.let { r -> "${r.get(java.util.Calendar.YEAR)}" } ?: info.released,
                            quality = info.quality,
                            rating = tmdbMatch?.rating ?: info.rating,
                            poster = tmdbMatch?.poster ?: poster,
                            banner = tmdbMatch?.banner ?: banner ?: tmdbMatch?.poster,
                            imdbId = tmdbMatch?.imdbId ?: tmdbMatch?.id
                        )
                    } else {
                        TvShow(
                            id = id,
                            title = title,
                            overview = tmdbMatch?.overview ?: overview,
                            quality = info.quality,
                            rating = tmdbMatch?.rating ?: info.rating,
                            poster = tmdbMatch?.poster ?: poster,
                            banner = tmdbMatch?.banner ?: banner ?: tmdbMatch?.poster,
                            imdbId = tmdbMatch?.imdbId ?: tmdbMatch?.id,
                            seasons = info.lastEpisode?.let { lastEpisode ->
                                listOf(Season(id = "", number = lastEpisode.season, episodes = listOf(Episode(id = "", number = lastEpisode.episode))))
                            } ?: listOf(),
                        )
                    }
                }
            }.awaitAll().filterNotNull()
            categories.add(Category(name = Category.FEATURED, list = featuredList))
        }

        val sections = listOf(
            "Trending Movies" to "div#trending-movies div.flw-item",
            "Trending TV Shows" to "div#trending-tv div.flw-item",
            "Latest Movies" to "section.section-id-02:has(h2:contains(Latest Movies)) div.flw-item",
            "Latest TV Shows" to "section.section-id-02:has(h2:contains(Latest TV Shows)) div.flw-item"
        )

        sections.forEach { (name, selector) ->
            val items = document.select(selector).map { el ->
                val info = el.select("div.film-detail > div.fd-infor > span").toInfo()
                val title = el.selectFirst("h3.film-name")?.text() ?: ""
                val id = el.selectFirst("a")?.attr("href") ?: ""
                val posterImg = el.selectFirst("img.film-poster-img")
                val poster = posterImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: posterImg?.attr("src")

                if (el.isMovie()) {
                    Movie(id = id, title = title, released = info.released, quality = info.quality, rating = info.rating, poster = poster)
                } else {
                    TvShow(id = id, title = title, quality = info.quality, rating = info.rating, poster = poster)
                }
            }
            if (items.isNotEmpty()) categories.add(Category(name = name, list = items))
        }

        categories
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val document = service.getHome()
            return document.select("div#sidebar_subs_genre li.nav-item a.nav-link")
                .map { Genre(id = it.attr("href").substringAfterLast("/"), name = it.text()) }
                .sortedBy { it.name }
        }

        val document = service.search(query.replace(" ", "-"), page)
        return document.select("div.flw-item").map {
            val id = it.selectFirst("a")?.attr("href")?: ""
            val title = it.selectFirst("h2.film-name")?.text() ?: ""
            val info = it.select("div.film-detail > div.fd-infor > span").toInfo()
            val posterImg = it.selectFirst("img.film-poster-img")
            val poster = posterImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: posterImg?.attr("src")

            if (it.isMovie()) {
                Movie(id = id, title = title, released = info.released, quality = info.quality, rating = info.rating, poster = poster)
            } else {
                TvShow(id = id, title = title, quality = info.quality, rating = info.rating, poster = poster)
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val document = service.getMovies(page)
        return document.select("div.flw-item").map {
            val info = it.select("div.film-detail > div.fd-infor > span").toInfo()
            val posterImg = it.selectFirst("img.film-poster-img")
            val poster = posterImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: posterImg?.attr("src")
            Movie(
                id = it.selectFirst("a")?.attr("href") ?: "",
                title = it.selectFirst("h2.film-name")?.text() ?: "",
                released = info.released,
                quality = info.quality,
                rating = info.rating,
                poster = poster
            )
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = service.getTvShows(page)
        return document.select("div.flw-item").map {
            val info = it.select("div.film-detail > div.fd-infor > span").toInfo()
            val posterImg = it.selectFirst("img.film-poster-img")
            val poster = posterImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: posterImg?.attr("src")
            TvShow(
                id = it.selectFirst("a")?.attr("href") ?: "",
                title = it.selectFirst("h2.film-name")?.text() ?: "",
                quality = info.quality,
                rating = info.rating,
                poster = poster
            )
        }
    }


    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val document = service.getMovie(id)
        val title = document.selectFirst("h2.heading-name")?.text() ?: ""
        
        val tmdbMovie = if (UserPreferences.enableTmdb) {
            TmdbUtils.getMovie(title)
        } else null

        Movie(
            id = id,
            title = tmdbMovie?.title ?: title,
            overview = tmdbMovie?.overview ?: document.selectFirst("div.description")?.ownText(),
            released = tmdbMovie?.released?.let { r -> "${r.get(java.util.Calendar.YEAR)}" } 
                ?: document.select("div.elements > .row > div > .row-line").find { it.select(".type").text().contains("Released") }?.ownText()?.trim(),
            runtime = tmdbMovie?.runtime ?: document.select("div.elements > .row > div > .row-line").find { it.select(".type").text().contains("Duration") }?.ownText()?.removeSuffix("min")?.trim()?.toIntOrNull(),
            trailer = tmdbMovie?.trailer ?: document.selectFirst("iframe#iframe-trailer")?.attr("data-src")?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=${it}" },
            quality = document.selectFirst(".fs-item > .quality")?.text()?.trim(),
            rating = tmdbMovie?.rating ?: document.selectFirst(".fs-item > .imdb")?.text()?.trim()?.removePrefix("IMDB:")?.toDoubleOrNull(),
            poster = tmdbMovie?.poster ?: document.selectFirst("div.detail_page-watch img.film-poster-img")?.attr("src"),
            banner = tmdbMovie?.banner ?: document.selectFirst("div.detail-container > div.cover_follow")?.attr("style")?.substringAfter("background-image: url(")?.substringBefore(");"),
            imdbId = tmdbMovie?.imdbId ?: tmdbMovie?.id,
            genres = tmdbMovie?.genres ?: document.select("div.elements > .row > div > .row-line").find { it.select(".type").text().contains("Genre") }?.select("a")?.map { Genre(id = it.attr("href").substringAfter("/genre/"), name = it.text()) } ?: listOf(),
            cast = tmdbMovie?.cast ?: document.select("div.elements > .row > div > .row-line").find { it.select(".type").text().contains("Cast") }?.select("a")?.map { People(id = it.attr("href").substringAfter("/cast/"), name = it.text()) } ?: listOf(),
        )
    }


    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val document = service.getTvShow(id)
        val title = document.selectFirst("h2.heading-name")?.text() ?: ""
        
        val tmdbShow = if (UserPreferences.enableTmdb) {
            TmdbUtils.getTvShow(title)
        } else null

        TvShow(
            id = id,
            title = tmdbShow?.title ?: title,
            overview = tmdbShow?.overview ?: document.selectFirst("div.description")?.ownText(),
            released = tmdbShow?.released?.let { r -> "${r.get(java.util.Calendar.YEAR)}" } 
                ?: document.select("div.elements > .row > div > .row-line").find { it.select(".type").text().contains("Released") }?.ownText()?.trim(),
            trailer = tmdbShow?.trailer ?: document.selectFirst("iframe#iframe-trailer")?.attr("data-src")?.substringAfterLast("/")?.let { "https://www.youtube.com/watch?v=${it}" },
            quality = document.selectFirst(".fs-item > .quality")?.text()?.trim(),
            rating = tmdbShow?.rating ?: document.selectFirst(".fs-item > .imdb")?.text()?.trim()?.removePrefix("IMDB:")?.toDoubleOrNull(),
            poster = tmdbShow?.poster ?: document.selectFirst("div.detail_page-watch img.film-poster-img")?.attr("src"),
            banner = tmdbShow?.banner ?: document.selectFirst("div.detail-container > div.cover_follow")?.attr("style")?.substringAfter("background-image: url(")?.substringBefore(");"),
            imdbId = tmdbShow?.imdbId ?: tmdbShow?.id,
            seasons = service.getTvShowSeasons(id.toNumericalId()).select("div.dropdown-menu.dropdown-menu-model > a").mapIndexed { sn, se ->
                val sNum = sn + 1
                Season(id = se.attr("data-id"), number = sNum, title = se.text(), poster = tmdbShow?.seasons?.find { it.number == sNum }?.poster)
            },
            genres = tmdbShow?.genres ?: document.select("div.elements > .row > div > .row-line").find { it.select(".type").text().contains("Genre") }?.select("a")?.map { Genre(id = it.attr("href").substringAfter("/genre/"), name = it.text()) } ?: listOf(),
            cast = tmdbShow?.cast ?: document.select("div.elements > .row > div > .row-line").find { it.select(".type").text().contains("Cast") }?.select("a")?.map { People(id = it.attr("href").substringAfter("/cast/"), name = it.text()) } ?: listOf(),
        ).apply {
            seasons.forEach { it.tvShow = this }
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val document = service.getSeasonEpisodes(seasonId)
        return document.select("div.flw-item.film_single-item.episode-item.eps-item").mapIndexed { en, ee ->
            Episode(
                id = ee.attr("data-id"),
                number = ee.selectFirst("div.episode-number")?.text()?.substringAfter("Episode ")?.substringBefore(":")?.toIntOrNull() ?: en,
                title = ee.selectFirst("h3.film-name")?.text(),
                poster = ee.selectFirst("img")?.attr("src"),
            )
        }
    }


    override suspend fun getGenre(id: String, page: Int): Genre {
        val document = service.getGenre(id, page)
        return Genre(
            id = id,
            name = document.selectFirst("h2.cat-heading")?.text()?.removeSuffix(" Movies and TV Shows") ?: "",
            shows = document.select("div.flw-item").map {
                val showId = it.selectFirst("a")?.attr("href") ?: ""
                val showTitle = it.selectFirst("h2.film-name")?.text() ?: ""
                val showInfo = it.select("div.film-detail > div.fd-infor > span").toInfo()
                val posterImg = it.selectFirst("img.film-poster-img")
                val showPoster = posterImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: posterImg?.attr("src")

                if (it.isMovie()) Movie(id = showId, title = showTitle, released = showInfo.released, quality = showInfo.quality, rating = showInfo.rating, poster = showPoster)
                else TvShow(id = showId, title = showTitle, quality = showInfo.quality, rating = showInfo.rating, poster = showPoster)
            }
        )
    }


    override suspend fun getPeople(id: String, page: Int): People {
        val document = service.getPeople(id, page)
        return People(
            id = id,
            name = document.selectFirst("h2.cat-heading")?.text() ?: "",
            filmography = document.select("div.flw-item").map {
                val showId = it.selectFirst("a")?.attr("href") ?: ""
                val showTitle = it.selectFirst("h2.film-name")?.text() ?: ""
                val showInfo = it.select("div.film-detail > div.fd-infor > span").toInfo()
                val posterImg = it.selectFirst("img.film-poster-img")
                val showPoster = posterImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: posterImg?.attr("src")

                if (it.isMovie()) Movie(id = showId, title = showTitle, released = showInfo.released, quality = showInfo.quality, rating = showInfo.rating, poster = showPoster)
                else TvShow(id = showId, title = showTitle, quality = showInfo.quality, rating = showInfo.rating, poster = showPoster)
            },
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()

        // 1. Multilingual / Universal Fallbacks (HIGHEST PRIORITY as they usually work)
        try {
            val tmdbId = when (videoType) {
                is Video.Type.Movie -> videoType.imdbId
                is Video.Type.Episode -> videoType.tvShow.imdbId
            }
            if (!tmdbId.isNullOrEmpty()) {
                val tmdbVideoType = when (videoType) {
                    is Video.Type.Movie -> videoType.copy(id = tmdbId)
                    is Video.Type.Episode -> videoType.copy(
                        id = id, 
                        tvShow = videoType.tvShow.copy(id = tmdbId)
                    )
                }
                
                servers.add(0, MovieBoxExtractor().server(tmdbVideoType))
                servers.add(VixSrcExtractor().server(tmdbVideoType))
                servers.add(VidsrcToExtractor().server(tmdbVideoType))
                servers.add(VidsrcNetExtractor().server(tmdbVideoType))
                servers.add(TwoEmbedExtractor().server(tmdbVideoType))
                servers.add(VidLinkExtractor().server(tmdbVideoType))
                servers.add(VidsrcRuExtractor().server(tmdbVideoType))
                servers.add(MoviesapiExtractor().server(tmdbVideoType))
            }
        } catch (e: Exception) {
            Log.e("SflixProvider", "Failed to add fallbacks: ${e.message}")
        }

        // 2. Native SFlix Servers (Trying both v2 and v1 engines)
        val numericalId = id.toNumericalId()
        listOf("v2", "v1").forEach { version ->
            try {
                val doc = if (version == "v2") {
                    if (videoType is Video.Type.Movie) service.getMovieServersV2(numericalId)
                    else service.getEpisodeServersV2(id)
                } else {
                    if (videoType is Video.Type.Movie) service.getMovieServersV1(numericalId)
                    else service.getEpisodeServersV1(id)
                }
                
                doc.select("a").forEach { el ->
                    val serverName = el.selectFirst("span")?.text()?.trim() ?: ""
                    if (servers.none { it.name == serverName }) {
                        servers.add(Video.Server(
                            id = el.attr("data-id"),
                            name = serverName,
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.d("SflixProvider", "Failed to fetch native servers ($version): ${e.message}")
            }
        }

        if (servers.isEmpty()) throw Exception("No links found for ID: $id")
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        if (server.src.isNotEmpty()) {
            return Extractor.extract(server.src, server)
        }
        val link = service.getLink(server.id)
        return Extractor.extract(link.link, server)
    }

    override suspend fun onChangeUrl(forceRefresh: Boolean): String {
        changeUrlMutex.withLock {
            service = SflixService.build(baseUrl)
        }
        return baseUrl
    }


    private fun Element.isMovie(): Boolean = this.selectFirst("a")?.attr("href")
        ?.contains("/movie/") ?: false

    private fun Elements.toInfo() = this.map { it.text() }.let {
        object {
            val rating = it.find { s -> s.matches("^\\d(?:\\.\\d)?\$".toRegex()) }?.toDoubleOrNull()
            val quality = it.find { s -> s in listOf("HD", "SD", "CAM", "TS", "HDRip") }
            val released = it.find { s -> s.matches("\\d{4}".toRegex()) }
            val lastEpisode = it.find { s -> s.matches("S\\d+\\s*:E\\d+".toRegex()) }?.let { s ->
                val result = Regex("S(\\d+)\\s*:E(\\d+)").find(s)?.groupValues
                object {
                    val season = result?.getOrNull(1)?.toIntOrNull() ?: 0
                    val episode = result?.getOrNull(2)?.toIntOrNull() ?: 0
                }
            }
        }
    }

    private fun String.toNumericalId(): String = this.trimEnd('/').substringAfterLast("-")


    private interface SflixService {

        companion object {
            fun build(baseUrl: String): SflixService {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(NetworkClient.compatibleTrustAll)
                    .build()

                return retrofit.create(SflixService::class.java)
            }
        }

        @GET("home")
        suspend fun getHome(): Document

        @GET("search/{query}")
        suspend fun search(@Path("query") query: String, @Query("page") page: Int): Document

        @GET("movie")
        suspend fun getMovies(@Query("page") page: Int): Document

        @GET("tv-show")
        suspend fun getTvShows(@Query("page") page: Int): Document


        @GET
        suspend fun getMovie(@Url url: String): Document

        @Headers("X-Requested-With: XMLHttpRequest")
        @GET("ajax/v2/movie/servers/{id}")
        suspend fun getMovieServersV2(@Path("id") id: String, @Query("id") idQuery: String = id): Document

        @Headers("X-Requested-With: XMLHttpRequest")
        @GET("ajax/episode/list/{id}")
        suspend fun getMovieServersV1(@Path("id") id: String): Document


        @GET
        suspend fun getTvShow(@Url url: String): Document

        @Headers("X-Requested-With: XMLHttpRequest")
        @GET("ajax/v2/tv/seasons/{id}")
        suspend fun getTvShowSeasons(@Path("id") id: String, @Query("id") idQuery: String = id): Document

        @Headers("X-Requested-With: XMLHttpRequest")
        @GET("ajax/v2/tv/episodes/{id}")
        suspend fun getSeasonEpisodes(@Path("id") id: String, @Query("id") idQuery: String = id): Document

        @Headers("X-Requested-With: XMLHttpRequest")
        @GET("ajax/v2/episode/servers/{id}")
        suspend fun getEpisodeServersV2(@Path("id") id: String, @Query("id") idQuery: String = id): Document

        @Headers("X-Requested-With: XMLHttpRequest")
        @GET("ajax/episode/servers/{id}")
        suspend fun getEpisodeServersV1(@Path("id") id: String): Document


        @GET("genre/{id}")
        suspend fun getGenre(@Path("id") id: String, @Query("page") page: Int): Document


        @GET("cast/{id}")
        suspend fun getPeople(@Path("id") id: String, @Query("page") page: Int): Document


        @Headers("X-Requested-With: XMLHttpRequest")
        @GET("ajax/v2/episode/sources/{id}")
        suspend fun getLink(@Path("id") id: String): Link

        @GET
        suspend fun getEmbed(
            @Url url: String,
        ): Embed


        data class Link(
            val type: String = "",
            val link: String = "",
            val sources: List<String> = listOf(),
            val tracks: List<String> = listOf(),
            val title: String = "",
        )

        data class Embed(
            val sources: List<Source>,
            val tracks: List<Track>,
            val t: Int,
            val server: Int,
        ) {
            data class Source(
                val file: String,
                val type: String,
            )

            data class Track(
                val file: String,
                val label: String,
                val kind: String,
                val default: Boolean?,
            )
        }
    }
}
