package com.nexastream.app.providers

import android.util.Log
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.*
import com.nexastream.app.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.text.Charsets

object CdnLiveTvProvider : Provider {
    private const val BASE_URL = "https://api.cdnlivetv.tv/api/v1/"
    private const val USER = "cdnlivetv"
    private const val PLAN = "free"

    override val name: String = "CDN Live TV"
    override val baseUrl: String = BASE_URL
    override val logo: String = "https://cdnlivetv.tv/assets/img/logo.png"
    override val language: String = "en"

    private val client = NetworkClient.systemDns.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(Service::class.java)

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var cachedChannels: List<CDNChannel> = emptyList()
    private var lastFetchTime: Long = 0L
    private const val CACHE_EXPIRY = 5 * 60 * 1000L // 5 minutes

    private suspend fun refreshCacheSilently() {
        runCatching {
            val channelsResp = service.getChannels().channels
            if (channelsResp.isNotEmpty()) {
                cachedChannels = channelsResp
                lastFetchTime = System.currentTimeMillis()
                Log.d("CdnLiveTv", "Cache refreshed silently. Channels: ${channelsResp.size}")
            }
        }.onFailure {
            Log.e("CdnLiveTv", "Silent cache refresh failed", it)
        }
    }

    interface Service {
        @GET("channels/")
        suspend fun getChannels(
            @Query("user") user: String = USER,
            @Query("plan") plan: String = PLAN
        ): CDNChannelsResponse
    }

    override suspend fun getHome(): List<Category> {
        val now = System.currentTimeMillis()
        if (cachedChannels.isNotEmpty()) {
            if (now - lastFetchTime > CACHE_EXPIRY) {
                Log.d("CdnLiveTv", "Cache stale, triggering silent background refresh...")
                scope.launch { refreshCacheSilently() }
            } else {
                Log.d("CdnLiveTv", "Returning fresh cached categories instantly.")
            }
            return buildHomeCategories(cachedChannels)
        }

        Log.d("CdnLiveTv", "No cache available. Performing initial direct network fetch...")
        return coroutineScope {
            val channels = runCatching { service.getChannels().channels }.getOrElse {
                Log.e("CdnLiveTv", "Error fetching channels", it)
                emptyList()
            }

            if (channels.isNotEmpty()) {
                cachedChannels = channels
                lastFetchTime = System.currentTimeMillis()
            }

            buildHomeCategories(channels)
        }
    }

    private suspend fun buildHomeCategories(channels: List<CDNChannel>): List<Category> {
        val categories = mutableListOf<Category>()

        // 1. Featured Channels
        if (channels.isNotEmpty()) {
            val tvShowItems = channels.map { it.toTvShow() }
            categories.add(
                Category(
                    name = "CDN Live Channels",
                    list = tvShowItems
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM }
            )
        }

        return categories
    }

    private suspend fun CDNChannel.toTvShow() = TvShow(
        id = if (url.isNotBlank()) "cdn:$url" else "cdn:$name:$code",
        title = name,
        poster = com.nexastream.app.utils.ChannelLogoRepository.getLogoUrl(name) ?: com.nexastream.app.utils.ArtworkRequestHeaders.run {
            val urlWithParams = appendQueryParams(image, mapOf("user" to USER, "plan" to PLAN))
            withHeaders(
                urlWithParams,
                referer = "https://cdnlivetv.tv/",
                origin = "https://cdnlivetv.tv",
                userAgent = NetworkClient.USER_AGENT
            )
        } ?: image,
        banner = image,
        quality = "LIVE",
        providerName = "CDN Live TV"
    ).apply { 
        itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM 
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id.startsWith("cdn:")) {
            val target = id.removePrefix("cdn:")
            
            if (target.startsWith("http://") || target.startsWith("https://")) {
                return listOf(Video.Server(id = target, name = "CDN Direct"))
            }

            val channels = if (cachedChannels.isNotEmpty()) cachedChannels else runCatching { service.getChannels().channels }.getOrNull() ?: emptyList()
            val channel = channels.find { ch ->
                ch.url == target ||
                "${ch.name}:${ch.code}".equals(target, ignoreCase = true) ||
                ch.name.equals(target, ignoreCase = true) ||
                ch.code.equals(target, ignoreCase = true)
            }
            if (channel != null) {
                return listOf(Video.Server(id = channel.url, name = "CDN Direct"))
            }
        }

        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val url = server.id
        Log.e("CdnLiveTv", "getVideo(url=$url)")
        
        if (url.contains("/player/") || url.contains("/channels/player/")) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Referer", "https://cdnlivetv.tv/")
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .build()
                
                val response = client.newCall(request).execute()
                val html = response.body?.string().orEmpty()
                Log.e("CdnLiveTv", "Fetched player HTML, length: ${html.length}")
                
                // Log in chunks to see all scripts
                html.chunked(3000).forEachIndexed { index, chunk ->
                    Log.e("CdnLiveTv", "HTML Chunk $index: $chunk")
                }
                
                // Try to find m3u8 in the HTML (greedy search)
                val m3u8Regex = Regex("""["'](https?[:\\]+[^"']+\.m3u8[^"']*)["']""")
                val fileRegex = Regex("""file\s*[:\s]+["']([^"']+)["']""")
                val sourceRegex = Regex("""source\s*[:\s]+["']([^"']+)["']""")
                
                var streamUrl = m3u8Regex.find(html)?.groupValues?.get(1)
                    ?: fileRegex.find(html)?.groupValues?.get(1)
                    ?: sourceRegex.find(html)?.groupValues?.get(1)
                
                // 2. Try atob patterns
                if (streamUrl == null) {
                    Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""").findAll(html).forEach { match ->
                        try {
                            val decoded = String(android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT))
                            if (decoded.contains(".m3u8")) {
                                streamUrl = decoded
                                Log.e("CdnLiveTv", "Found decoded m3u8: $streamUrl")
                            }
                        } catch (e: Exception) {}
                    }
                }

                // 3. Try iframe redirection
                if (streamUrl == null) {
                    val doc = org.jsoup.Jsoup.parse(html)
                    doc.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src")
                        if (src.isNotEmpty() && src.startsWith("http")) {
                            Log.e("CdnLiveTv", "Found iframe: $src")
                        }
                    }
                }

                if (streamUrl == null && html.contains("eval(function(p,a,c,k,e,d)")) {
                    Log.e("CdnLiveTv", "Packed JS detected, attempting to unpack...")
                    val unpacked = com.nexastream.app.utils.JsUnpacker(html).unpack()
                    if (unpacked != null) {
                        streamUrl = m3u8Regex.find(unpacked)?.groupValues?.get(1)
                            ?: fileRegex.find(unpacked)?.groupValues?.get(1)
                    }
                }

                // 5. Try to extract stream URL from custom obfuscation (CDN Live TV specific)
                if (streamUrl == null) {
                    streamUrl = extractObfuscatedStreamUrl(html)
                    if (streamUrl != null) {
                        Log.e("CdnLiveTv", "Found obfuscated stream URL: $streamUrl")
                    }
                }

                val currentUrl = streamUrl
                if (currentUrl != null) {
                    val finalStreamUrl = try {
                        currentUrl.replace("\\/", "/")
                            .let { if (it.startsWith("//")) "https:$it" else it }
                    } catch (e: Exception) {
                        Log.e("CdnLiveTv", "Error formatting stream URL: $currentUrl", e)
                        null
                    }
                    
                    if (finalStreamUrl != null) {
                        Log.e("CdnLiveTv", "Extracted stream Success: $finalStreamUrl")
                        return@withContext Video(
                            source = finalStreamUrl,
                            headers = mapOf(
                                "Referer" to "https://cdnlivetv.tv/",
                                "Origin" to "https://cdnlivetv.tv",
                                "User-Agent" to NetworkClient.USER_AGENT
                            )
                        )
                    }
                }
                
                Log.e("CdnLiveTv", "Stream not found or invalid in player HTML. Body length: ${html.length}")
            } catch (e: Exception) {
                Log.e("CdnLiveTv", "Error extracting stream from $url", e)
            }
        }
        Video(source = url)
    }

    /**
     * Extracts stream URL from CDN Live TV's specific obfuscation pattern.
     * The pattern involves:
     * 1. Multiple variables with base64-encoded string parts
     * 2. A function with a randomized name (e.g., ZBeEXEZVzJ or EuDBXDBAHg) that decodes base64
     * 3. Concatenation of decoded parts to form the final URL
     */
    private fun extractObfuscatedStreamUrl(html: String): String? {
        try {
            // 1. Detect the decoding function name dynamically
            // Look for: function NAME(s){s=s.replace(/-/g,'+').replace(/_/g,'/') ... }
            val funcNamePattern = Regex("""function\s+([a-zA-Z0-9_]+)\s*\(\s*[a-z]\s*\)\s*\{\s*[a-z]\s*=\s*[a-z]\.replace\(""")
            val funcNameMatch = funcNamePattern.find(html) ?: return null
            val funcName = funcNameMatch.groupValues[1]
            Log.d("CdnLiveTv", "Detected obfuscation function: $funcName")

            // 2. Extract all variable assignments: var xxx='yyy' or var xxx = "yyy"
            val varAssignments = mutableMapOf<String, String>()
            val varPattern = Regex("""var\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*["']([^"']*)["']""")
            for (match in varPattern.findAll(html)) {
                val varName = match.groupValues[1]
                val varValue = match.groupValues[2]
                varAssignments[varName] = varValue
            }

            // 3. Look for URL construction pattern using the detected function name: 
            // var RJcIbpAgvVfE=funcName(...)+funcName(...)+...
            val urlPattern = Regex("""var\s+[a-zA-Z_][a-zA-Z0-9_]*\s*=\s*((?:$funcName\s*\(\s*[a-zA-Z_][a-zA-Z0-9_]*\s*\)\s*(?:\+\s*)?)+)""")
            val urlMatch = urlPattern.find(html) ?: return null

            // Extract the sequence of function calls
            val callSequence = urlMatch.groupValues[1]

            // Split by '+' to get individual function calls
            val functionCalls = callSequence.split("+").map { it.trim() }

            // 4. Process each function call: funcName(variableName)
            val decodedParts = StringBuilder()
            val callPattern = Regex("""$funcName\s*\(\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\)""")
            
            for (call in functionCalls) {
                val varMatch = callPattern.find(call)
                if (varMatch != null) {
                    val varName = varMatch.groupValues[1]
                    val varValue = varAssignments[varName]
                    if (varValue != null) {
                        decodedParts.append(obfuscatedBase64Decode(varValue))
                    }
                }
            }

            return decodedParts.toString().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e("CdnLiveTv", "Error extracting obfuscated stream URL", e)
            return null
        }
    }

    /**
     * Implements the randomized decoding logic (formerly EuDBXDBAHg):
     * function NAME(s){s=s.replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';try{return decodeURIComponent(escape(atob(s)))}catch(e){return atob(s)}}
     */
    private fun obfuscatedBase64Decode(input: String): String {
        return try {
            // Convert URL-safe base64 to standard base64
            var s = input.replace("-", "+").replace("_", "/")

            // Add padding if needed
            while (s.length % 4 != 0) {
                s += "="
            }

            // Decode base64
            val decodedBytes = android.util.Base64.decode(s, android.util.Base64.DEFAULT)

            // Standard JS UTF-8 decode trick
            String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                // Fallback: direct decode as ISO-8859-1 (standard atob behavior)
                val s = input.replace("-", "+").replace("_", "/")
                var padded = s
                while (padded.length % 4 != 0) padded += "="
                val decodedBytes = android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
                String(decodedBytes, Charsets.ISO_8859_1)
            } catch (e2: Exception) {
                input
            }
        }
    }

    // Stubs
    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> = emptyList()
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Not Supported")
    override suspend fun getTvShow(id: String): TvShow {
        if (id.startsWith("cdn:")) {
            val target = id.removePrefix("cdn:")
            val channels = if (cachedChannels.isNotEmpty()) cachedChannels else runCatching { service.getChannels().channels }.getOrNull() ?: emptyList()
            val channel = channels.find { ch ->
                ch.url == target ||
                "${ch.name}:${ch.code}".equals(target, ignoreCase = true) ||
                ch.name.equals(target, ignoreCase = true) ||
                ch.code.equals(target, ignoreCase = true)
            }
            if (channel != null) return channel.toTvShow()
            if (target.startsWith("http://") || target.startsWith("https://")) {
                return TvShow(id = id, title = "Live Stream", quality = "LIVE", providerName = "CDN Live TV")
            }
            return TvShow(id = id, title = "Not Found")
        }
        return TvShow(id = id, title = "Unsupported")
    }
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = try {
        coroutineScope {
            if (id == "cdn_all_channels" || id == "cdn_sports") {
                if (page > 1) {
                    return@coroutineScope Genre(id = id, name = "CDN Live TV", shows = emptyList())
                }
                val channels = if (cachedChannels.isNotEmpty()) cachedChannels else runCatching { service.getChannels().channels }.getOrElse { 
                    Log.e("CdnLiveTv", "getGenre failed to fetch channels")
                    emptyList() 
                }
                
                val filteredChannels = if (id == "cdn_sports") {
                    val sportsKeywords = listOf("Sky Sport", "Premier League", "DAZN", "ESPN", "Fox Sports", "SuperSport", "BT Sport", "BeIN", "Football", "Soccer", "NBA", "NFL", "F1", "MotoGP")
                    channels.filter { channel ->
                        sportsKeywords.any { channel.name.contains(it, ignoreCase = true) }
                    }
                } else {
                    channels
                }

                Log.d("CdnLiveTv", "getGenre ($id) returning ${filteredChannels.size} channels")
                Genre(
                    id = id,
                    name = if (id == "cdn_sports") "Live Sports" else "CDN Live TV",
                    shows = filteredChannels.map { async { it.toTvShow() } }.awaitAll()
                )
            } else {
                Genre(id = id, name = "Unknown", shows = emptyList())
            }
        }
    } catch (e: Exception) {
        Log.e("CdnLiveTv", "getGenre failed for $id", e)
        Genre(id = id, name = "Error", shows = emptyList())
    }

    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()
}
