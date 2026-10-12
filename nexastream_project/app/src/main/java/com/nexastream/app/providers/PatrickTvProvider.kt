package com.nexastream.app.providers

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.ChannelLogoRepository
import java.io.IOException

object PatrickTvProvider : IptvProvider, VersionedHomeCache {

    override val name = "PatrickTV"
    override val baseUrl = ""
    override val logo = "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg"
    override val language = "en"
    override val homeCacheVersion = "cricfy-v1"

    private const val PLAYBACK_ID_PREFIX = "patricktv:cricfy:"
    private const val LEGACY_PLAYBACK_ID_PREFIX = "patricktv:v2:"
    private val gson = Gson()

    private data class LegacyPlaybackData(
        val url: String,
        val name: String,
        val logo: String? = null,
        val userAgent: String? = null,
        val referer: String? = null,
        val cookie: String? = null,
        val headers: Map<String, String>? = null,
    )

    private data class PlaybackData(
        val url: String,
        val title: String,
        val logo: String? = null,
        val providerTitle: String? = null,
        val providerLogo: String? = null,
        val userAgent: String? = null,
        val cookie: String? = null,
        val referer: String? = null,
        val licenseString: String? = null,
        val headers: Map<String, String>? = null,
    )

    private val staticLogos = mapOf(
        "red bull" to "https://i.ibb.co/3mY9M3n/redbull.png",
        "nova sport" to "https://i.ibb.co/LKGg4xhJ/novasport.png",
        "eurosport" to "https://i.ibb.co/EurosportHD/eurosport-1.png",
        "sky sport" to "https://i.ibb.co/3mY9M3n/sky-sports-arena.png",
        "bein sport" to "http://logo.multicms.info/logoslar/beinsports1.png",
        "матч" to "https://iptvx.one/picons/match-tv.png",
        "кхл" to "https://iptvx.one/picons/khl-hd.png",
        "real madrid" to "https://upload.wikimedia.org/wikipedia/en/thumb/5/56/Real_Madrid_CF.svg/1200px-Real_Madrid_CF.svg.png",
        "setanta" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c9/Setanta_Sports_logo.svg/1200px-Setanta_Sports_logo.svg.png",
        "ufc" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0d/UFC_logo.svg/1200px-UFC_logo.svg.png",
    )

    private fun normalizeName(rawName: String): String =
        rawName.replace(Regex("""\s+"""), " ").trim()

    private suspend fun resolveLogoUrl(channelName: String, channelLogo: String?, providerLogo: String?): String {
        channelLogo?.takeIf(String::isNotBlank)?.let { return it }

        val normalizedName = channelName.lowercase().trim()
        staticLogos.entries.firstOrNull { normalizedName.contains(it.key) }?.value?.let { return it }
        ChannelLogoRepository.getLogoUrl(channelName)?.let { return it }
        providerLogo?.takeIf(String::isNotBlank)?.let { return it }
        return logo
    }

    private fun createId(source: CricfyProviderSource, channel: CricfyChannel): String {
        val data = PlaybackData(
            url = channel.url,
            title = channel.title,
            logo = channel.logo,
            providerTitle = source.title,
            providerLogo = source.image,
            userAgent = channel.userAgent,
            cookie = channel.cookie,
            referer = channel.referer,
            licenseString = channel.licenseString,
            headers = channel.headers,
        )
        val encoded = Base64.encodeToString(
            gson.toJson(data).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return PLAYBACK_ID_PREFIX + encoded
    }

    fun decodeId(id: String): Triple<String, String, String> {
        val data = decodePlaybackData(id)
        return Triple(data.url, data.title, data.logo.orEmpty())
    }

    private fun decodePlaybackData(id: String): PlaybackData {
        val encodedPrefix = when {
            id.startsWith(PLAYBACK_ID_PREFIX) -> PLAYBACK_ID_PREFIX
            id.startsWith(LEGACY_PLAYBACK_ID_PREFIX) -> LEGACY_PLAYBACK_ID_PREFIX
            else -> null
        }
        if (encodedPrefix != null) {
            return try {
                val encodingFlags = if (encodedPrefix == LEGACY_PLAYBACK_ID_PREFIX) {
                    Base64.DEFAULT
                } else {
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                }
                val json = String(
                    Base64.decode(id.removePrefix(encodedPrefix), encodingFlags),
                    Charsets.UTF_8,
                )
                if (encodedPrefix == LEGACY_PLAYBACK_ID_PREFIX) {
                    val legacy = gson.fromJson(json, LegacyPlaybackData::class.java)
                        ?: throw IllegalArgumentException("Empty legacy PatrickTV playback data")
                    PlaybackData(
                        url = legacy.url,
                        title = legacy.name,
                        logo = legacy.logo,
                        userAgent = legacy.userAgent,
                        cookie = legacy.cookie,
                        referer = legacy.referer,
                        headers = legacy.headers,
                    )
                } else {
                    gson.fromJson(json, PlaybackData::class.java)
                        ?: throw IllegalArgumentException("Empty PatrickTV playback data")
                }.also { data ->
                    require(data.url.isNotBlank() && data.title.isNotBlank()) {
                        "PatrickTV playback data is missing a URL or channel title"
                    }
                }
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid PatrickTV playback ID", error)
            }
        }

        return try {
            val raw = String(Base64.decode(id.removePrefix("patricktv:"), Base64.DEFAULT), Charsets.UTF_8)
            val parts = raw.split("|")
            PlaybackData(
                url = parts.first().removePrefix("patricktv:").trim(),
                title = parts.getOrNull(1).orEmpty(),
                logo = parts.getOrNull(2)?.ifBlank { null },
                userAgent = parts.getOrNull(3)?.ifBlank { null },
                referer = parts.getOrNull(4)?.ifBlank { null },
            )
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid PatrickTV playback ID", error)
        }
    }

    private suspend fun allProviderChannels(): List<Pair<CricfyProviderSource, List<CricfyChannel>>> =
        CricfyProviderClient.getChannelsForAllProviders()

    private suspend fun channelItem(
        source: CricfyProviderSource,
        channel: CricfyChannel,
    ): TvShow {
        val posterUrl = resolveLogoUrl(channel.title, channel.logo, source.image)
        return TvShow(
            id = createId(source, channel),
            title = normalizeName(channel.title).ifBlank { "Unknown Channel" },
            poster = posterUrl,
            banner = posterUrl,
            overview = buildString {
                append("Live channel")
                channel.group?.takeIf(String::isNotBlank)?.let { append("\nGroup: $it") }
                append("\nSource: ${source.title}")
            },
            providerName = name,
        ).apply {
            itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
        }
    }

    override suspend fun getHome(): List<Category> {
        val providerChannels = allProviderChannels()
        val categories = providerChannels.mapNotNull { (source, channels) ->
            val items = channels
                .distinctBy { it.url }
                .map { channel -> channelItem(source, channel) }
            if (items.isEmpty()) null
            else Category(name = source.title, list = items).apply {
                itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
        }
        if (categories.isEmpty()) {
            throw IOException("Cricfy providers loaded, but none returned playable channels")
        }
        return categories
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: SearchFilters?,
    ): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()

        return allProviderChannels()
            .flatMap { (source, channels) ->
                channels.asSequence()
                    .filter { it.title.contains(normalizedQuery, ignoreCase = true) }
                    .distinctBy { it.url }
                    .map { channel -> source to channel }
                    .toList()
            }
            .take(60)
            .map { (source, channel) -> channelItem(source, channel) }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val playback = decodePlaybackData(id)
        val posterUrl = resolveLogoUrl(playback.title, playback.logo, playback.providerLogo)
        return TvShow(
            id = id,
            title = playback.title,
            poster = posterUrl,
            banner = posterUrl,
            overview = "PatrickTV live stream: ${playback.title}",
            seasons = listOf(Season(id = id, number = 1, title = "Live Stream")),
            providerName = name,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        listOf(Episode(id = seasonId, number = 1, title = "Play Stream", season = null))

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> =
        listOf(Video.Server(id = id, name = "Direct Stream"))

    override suspend fun getVideo(server: Video.Server): Video {
        val playback = decodePlaybackData(server.id)
        val drmConfiguration = playback.licenseString
            ?.takeIf(String::isNotBlank)
            ?.let { license ->
                if (license.startsWith("http://", true) || license.startsWith("https://", true)) {
                    Video.DrmConfiguration(
                        licenseUri = license.substringBefore('|'),
                        licenseRequestHeaders = mapOf(
                            "Content-Type" to "*/*",
                            "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android)",
                        ),
                    )
                } else if (license.contains(":")) {
                    val parts = license.trim().split(":", limit = 2)
                    if (parts.size == 2) {
                        val jwk = buildClearKeyJwk(parts[0], parts[1])
                        if (jwk != null) Video.DrmConfiguration(licenseUri = jwk) else null
                    } else null
                } else null
            }

        val headers = linkedMapOf<String, String>()
        playback.headers.orEmpty().forEach { (key, value) -> headers.setHeader(key, value) }
        playback.userAgent?.let { headers.setHeader("User-Agent", it) }
        playback.referer?.let { headers.setHeader("Referer", it) }
        playback.cookie?.let { headers.setHeader("Cookie", it) }

        if (playback.url.contains("ronaldo.tvfor.pro")) {
            if (headers["User-Agent"].isNullOrBlank() || headers["User-Agent"]?.startsWith("http") == true) {
                headers.setHeader("User-Agent", "Lavf/56.15.102")
            }
            if (!headers.containsKey("Referer")) {
                headers.setHeader("Referer", "http://ronaldo.tvfor.pro/")
            }
        }

        return Video(
            source = playback.url,
            headers = headers.takeIf { it.isNotEmpty() },
            maintainToken = true,
            drmConfiguration = drmConfiguration,
        )
    }

    private fun buildClearKeyJwk(kidHex: String, keyHex: String): String? {
        return try {
            val kidClean = kidHex.filterNot(Char::isWhitespace)
            val keyClean = keyHex.filterNot(Char::isWhitespace)
            if (kidClean.length % 2 != 0 || keyClean.length % 2 != 0) return null
            val kidBytes = kidClean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val keyBytes = keyClean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val kidB64 = Base64.encodeToString(kidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val keyB64 = Base64.encodeToString(keyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val jwkJson = """{"keys":[{"kty":"oct","k":"$keyB64","kid":"$kidB64"}],"type":"temporary"}"""
            val jwkB64 = Base64.encodeToString(jwkJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "data:application/json;base64,$jwkB64"
        } catch (_: Exception) {
            null
        }
    }

    private fun MutableMap<String, String>.setHeader(name: String, value: String) {
        val existingName = keys.firstOrNull { it.equals(name, ignoreCase = true) }
        if (existingName != null) remove(existingName)
        put(name, value)
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")

    override suspend fun getGenre(id: String, page: Int): Genre {
        if (page > 1) return Genre(id, id, emptyList())

        val normalizedId = id.trim()
        val shows = allProviderChannels()
            .flatMap { (source, channels) ->
                if (!source.title.equals(normalizedId, ignoreCase = true)) return@flatMap emptyList()
                channels.distinctBy { it.url }.map { channel ->
                    val posterUrl = resolveLogoUrl(channel.title, channel.logo, source.image)
                    TvShow(
                        id = createId(source, channel),
                        title = normalizeName(channel.title),
                        poster = posterUrl,
                        banner = posterUrl,
                        providerName = name,
                    )
                }
            }
        return Genre(id, id, shows)
    }

    override suspend fun getPeople(id: String, page: Int): People = People(id, id)
}
