package com.nexastream.app.providers

import android.util.Base64
import android.util.Log
import com.nexastream.app.NexastreamApp
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
import java.io.BufferedReader
import java.io.InputStreamReader

object LocalIptvProvider : IptvProvider {

    override val name = "IPTV DIMAN"
    override val baseUrl = ""
    override val logo = "https://i.ibb.co/W1d0CxF/Logo-IPTV-All-World.jpg"
    override val language = "en"

    private const val TAG = "LocalIptvProvider"

    private var cachedChannels: List<M3UChannel>? = null

    data class M3UChannel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
        val userAgent: String? = null,
        val referer: String? = null
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
        "ufc" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0d/UFC_logo.svg/1200px-UFC_logo.svg.png"
    )

    private suspend fun resolveLogoUrl(channelName: String, m3uLogo: String?): String {
        if (!m3uLogo.isNullOrBlank()) return m3uLogo

        val nameLower = channelName.lowercase().trim()
        staticLogos.entries.find { nameLower.contains(it.key) }?.value?.let { return it }

        ChannelLogoRepository.getLogoUrl(channelName)?.let { return it }

        return logo
    }

    private fun createId(channel: M3UChannel): String {
        val rawId = "${channel.url}|${channel.name}|${channel.logo ?: ""}|${channel.userAgent ?: ""}|${channel.referer ?: ""}"
        return "localiptv:" + Base64.encodeToString(rawId.toByteArray(), Base64.NO_WRAP)
    }

    fun decodeId(id: String): Triple<String, String, String> {
        return try {
            val raw = String(Base64.decode(id.removePrefix("localiptv:"), Base64.DEFAULT))
            val parts = raw.split("|")
            val cleanUrl = parts[0].removePrefix("localiptv:").trim()
            Triple(cleanUrl, parts.getOrNull(1) ?: "", parts.getOrNull(2) ?: "")
        } catch (e: Exception) {
            Triple(id.removePrefix("localiptv:"), "Unknown Channel", "")
        }
    }

    private fun getMetadataFromId(id: String): Map<String, String?> {
        return try {
            val raw = String(Base64.decode(id.removePrefix("localiptv:"), Base64.DEFAULT))
            val parts = raw.split("|")
            mapOf(
                "ua" to parts.getOrNull(3).takeIf { it?.isNotEmpty() == true },
                "referer" to parts.getOrNull(4).takeIf { it?.isNotEmpty() == true }
            )
        } catch (e: Exception) { emptyMap() }
    }

    private fun getAllChannels(): List<M3UChannel> {
        if (cachedChannels != null) return cachedChannels!!

        val allChannels = mutableListOf<M3UChannel>()
        try {
            val assetManager = NexastreamApp.instance.assets
            val rootFiles = assetManager.list("")?.filter { it.endsWith(".m3u", ignoreCase = true) || it.endsWith(".m3u8", ignoreCase = true) } ?: emptyList()
            val m3uSubFiles = assetManager.list("m3u")?.map { "m3u/$it" }?.filter { it.endsWith(".m3u", ignoreCase = true) || it.endsWith(".m3u8", ignoreCase = true) } ?: emptyList()
            val allFiles = (rootFiles + m3uSubFiles).distinct()

            allFiles.forEach { fileName ->
                try {
                    val inputStream = assetManager.open(fileName)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    parseM3UStream(reader, allChannels)
                    inputStream.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading $fileName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering M3U assets: ${e.message}")
        }

        cachedChannels = allChannels
        return allChannels
    }

    private fun parseM3UStream(reader: BufferedReader, channels: MutableList<M3UChannel>) {
        var curName = ""
        var curLogo = ""
        var curGroup = ""
        var curUA: String? = null
        var curRef: String? = null

        reader.forEachLine { line ->
            val t = line.trim()
            if (t.startsWith("#EXTINF")) {
                curName = t.substringAfterLast(",").trim()
                curLogo = Regex("""tvg-logo="([^"]+)"""").find(t)?.groupValues?.get(1) ?: ""
                curGroup = Regex("""group-title="([^"]+)"""").find(t)?.groupValues?.get(1) ?: ""

                if (t.contains("http-user-agent=")) {
                    curUA = t.substringAfter("http-user-agent=").substringBefore(" ").removeSurrounding("\"")
                }
                if (t.contains("http-referrer=")) {
                    curRef = t.substringAfter("http-referrer=").substringBefore(" ").removeSurrounding("\"")
                }
            } else if (t.startsWith("#EXTGRP")) {
                curGroup = t.substringAfter(":").trim()
            } else if (t.startsWith("#EXTVLCOPT:")) {
                if (t.contains("http-user-agent=")) curUA = t.substringAfter("http-user-agent=").trim()
                if (t.contains("http-referrer=")) curRef = t.substringAfter("http-referrer=").trim()
            } else if (t.startsWith("http")) {
                val urlParts = t.split("|")
                val cleanUrl = urlParts[0].trim()

                if (urlParts.size > 1) {
                    urlParts.drop(1).forEach { part ->
                        if (part.contains("User-Agent=", ignoreCase = true)) {
                            curUA = part.substringAfter("=").substringAfter("=").trim()
                        }
                    }
                }

                if (curName.isNotEmpty()) {
                    channels.add(M3UChannel(curName, cleanUrl, curLogo, curGroup, curUA, curRef))
                    curName = ""; curLogo = ""; curGroup = ""; curUA = null; curRef = null
                }
            }
        }
    }

    override suspend fun getHome(): List<Category> {
        val channels = getAllChannels()

        val sportsKeywords = listOf("sport", "espn", "fox", "sky", "bein", "nba", "nfl", "nhl", "mlb", "football", "cricket", "tennis", "basketball", "racing", "матч", "боец", "футбол")
        val movieKeywords = listOf("movie", "film", "cinema", "hbo", "star", "showtime", "кино", "сериал", "премьера", "хит")
        val newsKeywords = listOf("news", "cnn", "bbc", "fox news", "msnbc", "al jazeera", "новости", "известия", "рбк", "вести")
        val kidsKeywords = listOf("kids", "cartoon", "disney", "nickelodeon", "nick jr", "детский", "мульт", "карусель")
        val musicKeywords = listOf("music", "mtv", "vh1", "spotify", "apple music", "музыка", "bridge", "shanson")

        val categorized = mutableMapOf<String, MutableList<M3UChannel>>(
            "Live Sports" to mutableListOf(),
            "Movies & Series" to mutableListOf(),
            "News" to mutableListOf(),
            "Kids" to mutableListOf(),
            "Music" to mutableListOf(),
            "Regional & Others" to mutableListOf()
        )

        channels.forEach { channel ->
            val nameLower = channel.name.lowercase()
            val groupLower = channel.group?.lowercase() ?: ""

            when {
                sportsKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["Live Sports"]!!.add(channel)
                movieKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["Movies & Series"]!!.add(channel)
                newsKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["News"]!!.add(channel)
                kidsKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["Kids"]!!.add(channel)
                musicKeywords.any { nameLower.contains(it) || groupLower.contains(it) } -> categorized["Music"]!!.add(channel)
                else -> categorized["Regional & Others"]!!.add(channel)
            }
        }

        return categorized.mapNotNull { (groupName, list) ->
            if (list.isEmpty()) return@mapNotNull null
            Category(
                name = groupName,
                list = list.distinctBy { it.name }.take(40).map { channel ->
                    val posterUrl = resolveLogoUrl(channel.name, channel.logo)
                    TvShow(
                        id = createId(channel.copy(logo = posterUrl)),
                        title = channel.name,
                        poster = posterUrl,
                        banner = posterUrl,
                        providerName = name
                    ).apply {
                        itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                }
            ).apply {
                itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
        }
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val allChannels = getAllChannels()
        return allChannels.filter { it.name.contains(query, ignoreCase = true) }
            .distinctBy { it.name }
            .take(60)
            .map { channel ->
                val posterUrl = resolveLogoUrl(channel.name, channel.logo)
                TvShow(id = createId(channel.copy(logo = posterUrl)), title = channel.name, poster = posterUrl, providerName = name)
            }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val (_, name, logoUrl) = decodeId(id)
        val posterUrl = resolveLogoUrl(name, logoUrl.takeIf { it.isNotBlank() && it != logo })
        return TvShow(
            id = id,
            title = name,
            poster = posterUrl,
            banner = posterUrl,
            overview = "Local IPTV Stream: $name",
            seasons = listOf(Season(id = id, number = 1, title = "Live Stream")),
            providerName = name
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return listOf(Episode(id = seasonId, number = 1, title = "Play Stream", season = null))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return listOf(Video.Server(id = id, name = "Direct Stream"))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val (url, _, _) = decodeId(server.id)
        val meta = getMetadataFromId(server.id)

        val headers = mutableMapOf<String, String>()
        meta["ua"]?.let { headers["User-Agent"] = it }
        meta["referer"]?.let { headers["Referer"] = it }

        // Default UA for ronaldo.tvfor.pro if not specified
        if (url.contains("ronaldo.tvfor.pro")) {
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Lavf/56.15.102"
            }
            if (!headers.containsKey("Referer")) {
                headers["Referer"] = "http://ronaldo.tvfor.pro/"
            }
        }

        return Video(source = url, headers = headers, maintainToken = true)
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")
    override suspend fun getGenre(id: String, page: Int): Genre {
        if (page > 1) return Genre(id, id, emptyList())

        val allChannels = getAllChannels()
        val normalizedId = id.trim().lowercase()

        val sportsKeywords = listOf("sport", "espn", "fox", "sky", "bein", "nba", "nfl", "nhl", "mlb", "football", "cricket", "tennis", "basketball", "racing", "матч", "боец", "футбол")
        val newsKeywords = listOf("news", "cnn", "bbc", "fox news", "msnbc", "al jazeera", "новости", "известия", "рбк", "вести")
        val movieKeywords = listOf("movie", "film", "cinema", "hbo", "star", "showtime", "кино", "сериал", "премьера", "хит")
        val kidsKeywords = listOf("kids", "cartoon", "disney", "nickelodeon", "nick jr", "детский", "мульт", "карусель")
        val musicKeywords = listOf("music", "mtv", "vh1", "spotify", "apple music", "музыка", "bridge", "shanson")

        val matchedChannels = when {
            normalizedId == "live sports" -> allChannels.filter { ch ->
                val nameLower = ch.name.lowercase()
                val groupLower = ch.group?.lowercase() ?: ""
                sportsKeywords.any { nameLower.contains(it) || groupLower.contains(it) }
            }
            normalizedId.contains("movie") || normalizedId.contains("series") -> allChannels.filter { ch ->
                val nameLower = ch.name.lowercase()
                val groupLower = ch.group?.lowercase() ?: ""
                movieKeywords.any { nameLower.contains(it) || groupLower.contains(it) }
            }
            normalizedId.contains("news") -> allChannels.filter { ch ->
                val nameLower = ch.name.lowercase()
                val groupLower = ch.group?.lowercase() ?: ""
                newsKeywords.any { nameLower.contains(it) || groupLower.contains(it) }
            }
            normalizedId.contains("kids") -> allChannels.filter { ch ->
                val nameLower = ch.name.lowercase()
                val groupLower = ch.group?.lowercase() ?: ""
                kidsKeywords.any { nameLower.contains(it) || groupLower.contains(it) }
            }
            normalizedId.contains("music") -> allChannels.filter { ch ->
                val nameLower = ch.name.lowercase()
                val groupLower = ch.group?.lowercase() ?: ""
                musicKeywords.any { nameLower.contains(it) || groupLower.contains(it) }
            }
            else -> {
                val byGroup = allChannels.filter { ch ->
                    ch.group?.lowercase()?.contains(normalizedId) == true ||
                    normalizedId.contains(ch.group?.lowercase() ?: "___")
                }
                if (byGroup.isNotEmpty()) byGroup
                else allChannels.filter { ch -> ch.name.lowercase().contains(normalizedId) }.ifEmpty { allChannels }
            }
        }

        val shows = matchedChannels
            .distinctBy { it.name.trim().lowercase() }
            .map { channel ->
                val posterUrl = resolveLogoUrl(channel.name, channel.logo)
                TvShow(
                    id = createId(channel.copy(logo = posterUrl)),
                    title = channel.name,
                    poster = posterUrl,
                    banner = posterUrl,
                    providerName = name
                )
            }

        return Genre(id, id, shows)
    }
    override suspend fun getPeople(id: String, page: Int): People = People(id, id)
}
