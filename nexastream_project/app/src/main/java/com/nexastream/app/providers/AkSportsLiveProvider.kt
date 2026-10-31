package com.nexastream.app.providers

import android.util.Log
import android.util.Base64
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.SportStream
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.SportsSecurityUtils
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object AkSportsLiveProvider : IptvProvider {
    private var activeHost: String = "https://strmfree.st"
    private val FALLBACK_HOSTS = listOf(
        "https://strmfree.st",
        "https://streamfree.top",
        "https://streamed.pk",
        "https://strmd.st"
    )
    private const val SERVER_NAME_PREFIX = "AK Sports - "
    private const val LOCAL_RESOLVER_SERVER_PREFIX = "nexastream-resolver:"
    private const val RESOLVER_STREAM_CACHE_TTL_MS = 60_000L
    private const val MAX_PREFETCH_SOURCES = 12
    
    private val client = com.nexastream.app.utils.NetworkClient.compatibleTrustAll.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    private val resolverClient = com.nexastream.app.utils.NetworkClient.compatibleTrustAll.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val sourceCache = ConcurrentHashMap<String, List<SportMatch.MatchSource>>()
    private val persistedMatches = ConcurrentHashMap<String, SportMatch>()
    private val categoryMetadata = ConcurrentHashMap<String, String>()
    private data class ResolverStreamCacheEntry(val streams: List<SportStream>, val cachedAtMs: Long)
    private val resolverStreamCache = ConcurrentHashMap<String, ResolverStreamCacheEntry>()
    private val resolverStreamLocks = ConcurrentHashMap<String, Mutex>()
    private val resolverPrefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolverPrefetchSemaphore = Semaphore(3)

    override val baseUrl: String get() = activeHost
    override val name: String = "AK Sports Live"
    override val logo: String = "https://strmfree.st/logo.png"
    override val language: String = "en"

    override suspend fun getHome(): List<Category> = coroutineScope {
        val liveDeferred = async { getLiveMatches() }
        val allDeferred = async { getAllMatches() }
        val sportsCatsDeferred = async { getSportsCategories() }
        
        val liveMatches = liveDeferred.await()
        val allMatches = allDeferred.await()
        val sportsCats = sportsCatsDeferred.await()
        
        Log.d("AkSportsLiveProvider", "getHome Summary: live=${liveMatches.size}, all=${allMatches.size}, cats=${sportsCats.size}")

        // Update persisted agenda
        liveMatches.forEach { persistedMatches[it.id] = it }
        allMatches.forEach { persistedMatches[it.id] = it }

        // Clean old matches (older than 24 hours)
        val now = System.currentTimeMillis()
        val oneDayAgo = now - (24 * 60 * 60 * 1000)
        persistedMatches.entries.removeIf { 
            val matchDate = it.value.date
            matchDate != null && matchDate < oneDayAgo && it.value.status != "LIVE"
        }
        
        val threeHoursAgo = now - (3 * 60 * 60 * 1000)
        
        // Final categorization
        val liveIds = liveMatches.map { it.id }.toSet()
        val currentLiveList = persistedMatches.values.filter { it.status == "LIVE" || it.id in liveIds }
            .distinctBy { it.id }
            .sortedByDescending { it.title.contains("Chelsea", ignoreCase = true) || it.league.contains("PREMIER LEAGUE", ignoreCase = true) }
        
        val upcomingMatches = persistedMatches.values
            .filter { it.status != "LIVE" && it.id !in liveIds && (it.date ?: 0L) > threeHoursAgo }
            .sortedBy { it.date ?: Long.MAX_VALUE }
        
        val result = mutableListOf<Category>()
        
        if (currentLiveList.isNotEmpty()) {
            result.add(Category(
                name = "Live Sports",
                list = currentLiveList
            ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
        }

        if (sportsCats.isNotEmpty()) {
            val sortOrder = listOf("soccer", "football", "cricket", "basketball", "hockey", "combat", "baseball", "racing", "tennis")
            val sortedCats = sportsCats.sortedWith { c1, c2 ->
                val i1 = sortOrder.indexOf(c1.title.lowercase()).let { if (it == -1) 99 else it }
                val i2 = sortOrder.indexOf(c2.title.lowercase()).let { if (it == -1) 99 else it }
                i1.compareTo(i2)
            }
            
            result.add(Category(
                name = "Sports Categories",
                list = sortedCats
            ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
        }
        
        if (upcomingMatches.isNotEmpty()) {
            result.add(Category(
                name = "Upcoming & Recent",
                list = upcomingMatches.sortedByDescending { it.title.contains("Chelsea", ignoreCase = true) || it.league.contains("PREMIER LEAGUE", ignoreCase = true) }
            ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
        }
        
        result
    }

    suspend fun getSportsCategories(): List<TvShow> = withContext(Dispatchers.IO) {
        val hosts = (listOf(activeHost) + FALLBACK_HOSTS).distinct()
        var body = ""
        for (host in hosts) {
            for (path in listOf("/api/v1/categories", "/api/sports")) {
                try {
                    val url = if (host.endsWith("/")) host + path.removePrefix("/") else "$host$path"
                    val result = executeRequest(url)
                    if (result.isNotBlank()) {
                        body = result
                        Log.i("AkSportsLiveProvider", "Successfully fetched categories from: $url")
                        break
                    }
                } catch (e: Exception) {}
            }
            if (body.isNotBlank()) break
        }

        val cats = mutableListOf<TvShow>()
        if (body.isNotBlank()) {
            try {
                val trimmed = body.trim()
                val jsonArray = if (trimmed.startsWith("{")) {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("categories") ?: obj.optJSONArray("data") ?: JSONArray()
                } else if (trimmed.startsWith("[")) {
                    JSONArray(trimmed)
                } else {
                    JSONArray()
                }
                for (i in 0 until jsonArray.length()) {
                    try {
                        val item = jsonArray.get(i)
                        val name = when (item) {
                            is JSONObject -> item.optString("name").ifBlank { item.optString("id") }
                            is String -> item
                            else -> item.toString()
                        }.replaceFirst("psp_", "").trim()
                        if (name.isBlank()) continue
                        
                        val logo = "$activeHost/api/images/categories/$name.png"
                        
                        cats.add(
                            TvShow(
                                id = "ak-cat-$name",
                                title = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                                providerName = "AK Sports",
                                poster = normalizePosterUrl(logo, name),
                                quality = "HD"
                            ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
                        )
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
        }
        
        if (cats.isEmpty()) {
            val defaultCategories = listOf("soccer", "basketball", "hockey", "combat", "baseball", "football", "racing", "tennis", "cricket")
            for (name in defaultCategories) {
                val logo = "$activeHost/api/images/categories/$name.png"
                cats.add(
                    TvShow(
                        id = "ak-cat-$name",
                        title = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                        providerName = "AK Sports",
                        poster = normalizePosterUrl(logo, name),
                        quality = "HD"
                    ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
                )
            }
        }
        cats
    }

    private suspend fun getAllMatches(): List<SportMatch> = withContext(Dispatchers.IO) {
        try {
            val body = fetchWithFallback("/api/v1/streams")
            if (body.isBlank()) {
                Log.w("AkSportsLiveProvider", "getAllMatches: Received empty body")
                return@withContext emptyList()
            }
            parseMatchesResponse(body, "UPCOMING").also(::cacheMatchSources)
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error fetching all matches: ${e.message}")
            emptyList()
        }
    }

    suspend fun getLiveMatches(): List<SportMatch> = withContext(Dispatchers.IO) {
        try {
            val body = fetchWithFallback("/api/v1/streams")
            if (body.isBlank()) {
                Log.w("AkSportsLiveProvider", "getLiveMatches: Received empty body")
                return@withContext emptyList()
            }
            parseMatchesResponse(body, "LIVE")
                .filter { it.status == "LIVE" }
                .also(::cacheMatchSources)
                .sortedByDescending { it.title.contains("Chelsea", ignoreCase = true) || it.league.contains("PREMIER LEAGUE", ignoreCase = true) }
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error fetching live matches: ${e.message}")
            emptyList()
        }
    }

    suspend fun getUpcomingMatches(): List<SportMatch> = coroutineScope {
        val allDeferred = async { getAllMatches() }
        val liveDeferred = async { getLiveMatches() }

        val allMatches = allDeferred.await()
        val liveMatches = liveDeferred.await()

        allMatches.forEach { persistedMatches[it.id] = it }

        val liveIds = liveMatches.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val threeHoursAgo = now - (3 * 60 * 60 * 1000)

        (allMatches + persistedMatches.values)
            .filter { it.status != "LIVE" && it.id !in liveIds && ((it.date ?: 0L) == 0L || (it.date ?: 0L) > threeHoursAgo) }
            .distinctBy { it.id }
            .sortedBy { it.date ?: Long.MAX_VALUE }
    }

    private fun fetchWithFallback(vararg paths: String): String {
        val hosts = (listOf(activeHost) + FALLBACK_HOSTS).distinct()
        for (host in hosts) {
            for (path in paths) {
                repeat(2) {
                    try {
                        val url = if (host.endsWith("/")) host + path.removePrefix("/") else "$host$path"
                        Log.d("AkSportsLiveProvider", "fetchWithFallback: Trying $url")
                        val result = executeRequest(url)
                        if (result.isNotBlank()) {
                            if (activeHost != host) {
                                Log.i("AkSportsLiveProvider", "Switched active host to: $host")
                                activeHost = host
                            }
                            return result
                        }
                    } catch (e: Exception) {
                        Log.w("AkSportsLiveProvider", "fetchWithFallback error for $host: ${e.message}")
                    }
                }
            }
        }
        return ""
    }

    private fun executeRequest(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("Referer", if (activeHost.endsWith("/")) activeHost else "$activeHost/")
            .header("User-Agent", com.nexastream.app.utils.NetworkClient.USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private suspend fun parseMatchesResponse(body: String, status: String): List<SportMatch> {
        if (body.isBlank()) return emptyList()
        return try {
            val trimmed = body.trim()
            val jsonArray = if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("streams")
                    ?: obj.optJSONArray("matches")
                    ?: obj.optJSONArray("data")
                    ?: JSONArray()
            } else if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONArray()
            }
            parseMatches(jsonArray, status)
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error parsing matches response: ${e.message}")
            emptyList()
        }
    }

    private suspend fun parseMatches(jsonArray: JSONArray, status: String): List<SportMatch> = coroutineScope {
        val deferredMatches = (0 until jsonArray.length()).map { i ->
            async {
                val obj = jsonArray.getJSONObject(i)
                val matchId = obj.optString("stream_key").ifBlank { obj.optString("id") }
                val title = obj.optString("name").ifBlank { obj.optString("title") }
                val category = obj.optString("category").ifBlank { obj.optString("sport") }
                val league = obj.optString("league").ifBlank { category }

                val thumbnail = obj.optString("thumbnail_url")
                    .ifBlank { obj.optString("thumbnail") }
                    .ifBlank { obj.optString("poster") }
                    .ifBlank { obj.optString("eventLogo") }

                val poster = when {
                    thumbnail.isNotBlank() -> thumbnail
                    category.isNotBlank() && matchId.isNotBlank() -> "$activeHost/thumbnails/${category}_$matchId"
                    else -> null
                }

                val teams = obj.optJSONObject("teams")
                val homeTeamObj = teams?.optJSONObject("home")
                val awayTeamObj = teams?.optJSONObject("away")
                val homeTeam = homeTeamObj?.optString("name") ?: title.split(" vs ").firstOrNull() ?: title
                val awayTeam = awayTeamObj?.optString("name") ?: title.split(" vs ").lastOrNull() ?: "Opponent"
                
                var score = "vs"
                val scores = obj.optJSONObject("scores")
                if (scores != null) {
                    val homeScore = scores.optString("home", "")
                    val awayScore = scores.optString("away", "")
                    if (homeScore.isNotBlank() && awayScore.isNotBlank()) {
                        score = "$homeScore - $awayScore"
                    }
                }

                val sources = mutableListOf<SportMatch.MatchSource>()
                val sourcesArray = obj.optJSONArray("sources") ?: obj.optJSONArray("live_sources")
                if (sourcesArray != null && sourcesArray.length() > 0) {
                    for (j in 0 until sourcesArray.length()) {
                        val sObj = sourcesArray.getJSONObject(j)
                        val quality = sObj.optString("quality").ifBlank { "HD" }
                        val url = sObj.optString("url")
                        if (url.isNotBlank()) {
                            sources.add(SportMatch.MatchSource(quality, url))
                        }
                    }
                }
                if (sources.isEmpty() && matchId.isNotBlank() && category.isNotBlank()) {
                    sources.add(SportMatch.MatchSource("FHD", "$activeHost/embed/$category/${matchId}1080p"))
                    sources.add(SportMatch.MatchSource("SD", "$activeHost/embed/$category/${matchId}540p"))
                }

                val rawDate = obj.optLong("match_timestamp").takeIf { it > 0 } ?: obj.optLong("date")
                val matchDate = if (rawDate > 0 && rawDate < 1000000000000L) rawDate * 1000 else rawDate
                
                val now = System.currentTimeMillis()
                val liveSources = obj.optJSONArray("live_sources")
                val hasLiveSources = liveSources != null && liveSources.length() > 0

                val computedStatus = when {
                    obj.has("status") -> obj.optString("status").uppercase()
                    hasLiveSources -> "LIVE"
                    matchDate > 0 -> {
                        if (now >= matchDate - 15 * 60 * 1000 && now <= matchDate + 3 * 3600 * 1000) "LIVE"
                        else if (now < matchDate - 15 * 60 * 1000) "UPCOMING"
                        else "FINISHED"
                    }
                    status == "LIVE" -> "LIVE"
                    else -> "UPCOMING"
                }

                val formattedTime = if (computedStatus == "LIVE") {
                    "Live"
                } else if (matchDate > 0) {
                    try {
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(matchDate))
                    } catch (e: Exception) {
                        "Upcoming"
                    }
                } else {
                    "Upcoming"
                }

                SportMatch(
                    id = matchId,
                    title = title,
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    league = league.uppercase(),
                    status = computedStatus,
                    time = formattedTime,
                    score = score,
                    sport = category,
                    poster = normalizePosterUrl(poster, category),
                    date = matchDate,
                    sources = sources
                ).apply { itemType = AppAdapter.Type.SPORT_MATCH_ITEM }
            }
        }
        deferredMatches.awaitAll()
    }

    private fun cacheMatchSources(matches: List<SportMatch>) {
        matches.forEach { match ->
            if (match.id.isNotBlank() && match.sources.isNotEmpty()) {
                sourceCache[match.id] = match.sources
            }
        }
    }

    private fun normalizePosterUrl(poster: String?, category: String?): String? {
        if (!poster.isNullOrBlank()) {
            if (poster.startsWith("http") && !poster.contains("streamed") && !poster.contains("embed.st") && !poster.contains("strmfree")) {
                return poster
            }
            var cleanPath = poster
            if (poster.startsWith("http")) {
                cleanPath = when {
                    poster.contains("/api/") -> poster.substringAfter("/api/")
                    poster.contains("api/") -> poster.substringAfter("api/")
                    else -> try { java.net.URI(poster).path } catch(e: Exception) { poster }
                }
            }
            cleanPath = cleanPath?.removePrefix("/api/")?.removePrefix("api/")?.removePrefix("/")
            if (!cleanPath.isNullOrBlank()) {
                val baseImageUrl = activeHost.removeSuffix("/")
                val imageUrl = if (cleanPath.startsWith("http")) cleanPath else "$baseImageUrl/$cleanPath"
                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                return com.nexastream.app.utils.ArtworkRequestHeaders.withHeaders(
                    imageUrl,
                    referer = "$baseImageUrl/",
                    origin = baseImageUrl,
                    userAgent = userAgent
                ) ?: imageUrl
            }
        }

        val cat = category?.lowercase() ?: ""
        return when {
            cat.contains("soccer") || cat.contains("football") || cat.contains("premier") || cat.contains("laliga") || cat.contains("champions") -> 
                "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=600&auto=format&fit=crop&q=80"
            cat.contains("cricket") -> 
                "https://images.unsplash.com/photo-1540747913346-19e32dc3e97e?w=600&auto=format&fit=crop&q=80"
            cat.contains("basketball") || cat.contains("nba") -> 
                "https://images.unsplash.com/photo-1546519638-68e109498ffc?w=600&auto=format&fit=crop&q=80"
            cat.contains("racing") || cat.contains("f1") || cat.contains("formula") || cat.contains("motor") -> 
                "https://images.unsplash.com/photo-1568605117036-5fe5e7bab0b7?w=600&auto=format&fit=crop&q=80"
            cat.contains("combat") || cat.contains("boxing") || cat.contains("ufc") || cat.contains("wwe") -> 
                "https://images.unsplash.com/photo-1517649763962-0c623066013b?w=600&auto=format&fit=crop&q=80"
            cat.contains("tennis") -> 
                "https://images.unsplash.com/photo-1622279457486-62dcc4a433d6?w=600&auto=format&fit=crop&q=80"
            else -> 
                "https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=600&auto=format&fit=crop&q=80"
        }
    }

    suspend fun getStreams(source: String, id: String): List<SportStream> = withContext(Dispatchers.IO) {
        try {
            val body = fetchWithFallback(
                "/api/v1/sources/$id",
                "/api/v1/streams/$id"
            )
            if (body.isBlank()) return@withContext emptyList()
            
            val jsonObject = try { JSONObject(body) } catch (e: Exception) { null }
            val streamsArray = jsonObject?.optJSONArray("sources") 
                ?: jsonObject?.optJSONArray("live_sources") 
                ?: jsonObject?.optJSONArray("streams")

            if (streamsArray == null && body.trim().startsWith("[")) {
                val jsonArray = JSONArray(body)
                val streams = mutableListOf<SportStream>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val url = obj.optString("url").ifBlank { obj.optString("embedUrl") }
                    if (url.isNotBlank()) {
                        streams.add(SportStream("$id-$i", i + 1, "Unknown", true, url, source, null))
                    }
                }
                return@withContext streams
            }

            if (streamsArray == null) return@withContext emptyList()

            val streams = mutableListOf<SportStream>()
            for (i in 0 until streamsArray.length()) {
                val obj = streamsArray.getJSONObject(i)
                val embedUrl = obj.optString("url").ifBlank { obj.optString("embedUrl") }
                if (embedUrl.isBlank()) continue
                val quality = obj.optString("quality", "HD")
                val isHd = quality == "HD" || quality == "FHD" || quality == "4K"

                streams.add(
                    SportStream(
                        id = "$id-$i",
                        streamNo = i + 1,
                        language = "Unknown",
                        hd = isHd,
                        embedUrl = embedUrl,
                        source = quality.ifBlank { source },
                        thumbnail = null
                    )
                )
            }
            streams
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        val request = try { SportsPlaybackId.decode(id) } catch(e: Exception) { SportsPlaybackRequest(id, emptyList()) }

        var matchInfo = persistedMatches[request.matchId]
        var resolvedSources = request.sources.ifEmpty {
            sourceCache[request.matchId] ?: matchInfo?.sources.orEmpty()
        }

        if (resolvedSources.isEmpty()) {
            val liveMatchesDeferred = async { getLiveMatches() }
            val upcomingMatchesDeferred = async { getUpcomingMatches() }
            val allMatches = liveMatchesDeferred.await() + upcomingMatchesDeferred.await()

            matchInfo = allMatches.find { it.id == request.matchId } ?: matchInfo
            resolvedSources = sourceCache[request.matchId] ?: matchInfo?.sources.orEmpty()
        }

        if (resolvedSources.isEmpty() && request.matchId.isNotBlank()) {
            resolvedSources = listOf(SportMatch.MatchSource("FHD", "$activeHost/embed/soccer/${request.matchId}1080p"))
        }

        val servers = mutableListOf<Video.Server>()
        val directSources = resolvedSources.filter { it.source.startsWith("event-") }
        val resolverSources = resolvedSources.filterNot { it.source.startsWith("event-") }

        servers.addAll(loadResolverServers(resolverSources))
        directSources.forEach { source ->
            servers.add(Video.Server(id = source.id, name = "AK Sports - ${source.source.removePrefix("event-")}", src = source.id))
        }
        
        servers.distinctBy { it.id }
    }

    private suspend fun loadResolverServers(sources: List<SportMatch.MatchSource>): List<Video.Server> = coroutineScope {
        sources
            .mapNotNull(::resolverSelectionFor)
            .distinctBy { it.source to it.id }
            .map { source ->
                async {
                    try {
                        getResolverStreams(source.source, source.id).map { stream ->
                            val payload = JSONObject()
                                .put("source", stream.source)
                                .put("id", stream.id)
                                .put("stream", stream.streamNo)
                            val encoded = Base64.encodeToString(
                                payload.toString().toByteArray(Charsets.UTF_8),
                                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                            )
                            Video.Server(
                                id = "$LOCAL_RESOLVER_SERVER_PREFIX$encoded",
                                name = "AK Sports - ${stream.source} - ${stream.language} ${if (stream.hd) "HD" else "SD"}",
                                src = "$LOCAL_RESOLVER_SERVER_PREFIX$encoded"
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(
                            "AkSportsLiveProvider",
                            "Hosted resolver stream lookup failed for ${source.source}/${source.id}: ${e.message}"
                        )
                        emptyList()
                    }
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
    }

    private data class ResolverSelection(val source: String, val id: String)

    private fun resolverSelectionFor(source: SportMatch.MatchSource): ResolverSelection? {
        if (source.id.isBlank()) return null
        if (!source.id.startsWith("http", ignoreCase = true)) {
            return ResolverSelection("embed", source.id)
        }

        val segments = runCatching { java.net.URI(source.id).path }
            .getOrNull()
            ?.split('/')
            ?.filter(String::isNotBlank)
            .orEmpty()
        if (segments.size < 3 || segments[0] != "embed") return null

        val matchId = segments.last()
            .replace(Regex("(?:1080p|720p|540p|480p|360p)$", RegexOption.IGNORE_CASE), "")
            .takeIf(String::isNotBlank)
            ?: return null
        return ResolverSelection("embed", matchId)
    }

    private suspend fun getResolverStreams(source: String, id: String): List<SportStream> = withContext(Dispatchers.IO) {
        val cacheKey = "$source\u0000$id"
        val now = System.currentTimeMillis()
        resolverStreamCache[cacheKey]
            ?.takeIf { now - it.cachedAtMs < RESOLVER_STREAM_CACHE_TTL_MS }
            ?.let { return@withContext it.streams }

        resolverStreamLocks.computeIfAbsent(cacheKey) { Mutex() }.withLock {
            val checkedAt = System.currentTimeMillis()
            resolverStreamCache[cacheKey]
                ?.takeIf { checkedAt - it.cachedAtMs < RESOLVER_STREAM_CACHE_TTL_MS }
                ?.let { return@withLock it.streams }

            val resolverUrl = UserPreferences.streamResolverUrl.trimEnd('/')
            val url = android.net.Uri.parse("$resolverUrl/api/streams")
                .buildUpon()
                .appendQueryParameter("source", source)
                .appendQueryParameter("id", id)
                .build()
            val request = Request.Builder()
                .url(url.toString())
                .header("Accept", "application/json")
                .get()
                .build()
            val responseBody = resolverClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Resolver returned HTTP ${response.code}: $body")
                }
                body
            }
            val streamsArray = JSONObject(responseBody).optJSONArray("streams")
                ?: throw IOException("Resolver response did not contain a streams array")
            val streams = buildList {
                for (index in 0 until streamsArray.length()) {
                    val item = streamsArray.optJSONObject(index) ?: continue
                    val streamId = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    add(
                        SportStream(
                            id = streamId,
                            streamNo = item.optInt("streamNo", index + 1),
                            language = item.optString("language").ifBlank { "Unknown" },
                            hd = item.optBoolean("hd", true),
                            embedUrl = item.optString("embedUrl"),
                            source = item.optString("source").ifBlank { source }
                        )
                    )
                }
            }
            resolverStreamCache[cacheKey] = ResolverStreamCacheEntry(streams, System.currentTimeMillis())
            streams
        }
    }

    private suspend fun resolveWithHostedResolver(encodedPayload: String): Video = withContext(Dispatchers.IO) {
        val payload = try {
            JSONObject(String(Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("Invalid hosted resolver selection", e)
        }
        val source = payload.optString("source")
        val id = payload.optString("id")
        val stream = payload.optInt("stream", -1)
        if (source.isBlank() || id.isBlank() || stream < 1) {
            throw IOException("Hosted resolver selection is missing source, id, or stream number")
        }

        val resolverUrl = UserPreferences.streamResolverUrl.trimEnd('/')
        val requestBody = JSONObject()
            .put("source", source)
            .put("id", id)
            .put("stream", stream)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$resolverUrl/api/resolve")
            .post(requestBody)
            .header("Accept", "application/json")
            .build()
        val responseBody = resolverClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Hosted resolver returned HTTP ${response.code}: $body")
            }
            body
        }
        val result = JSONObject(responseBody)
        if (!result.optBoolean("ok")) {
            val stage = result.optString("stage").takeIf(String::isNotBlank)
            val error = result.optString("error").ifBlank { "Resolver could not unlock this stream" }
            throw IOException(if (stage == null) error else "Resolver $stage failed: $error")
        }
        val relay = result.optString("relay")
        if (relay.isBlank()) throw IOException("Hosted resolver response did not include a relay URL")
        Video(
            source = relay,
            headers = mapOf("Referer" to result.optString("referer", "$resolverUrl/")),
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            maintainToken = true
        )
    }

    private fun normalizeTeam(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun playbackId(match: SportMatch): String = SportsPlaybackId.encode(match)

    fun prefetchResolverStreams(matches: List<SportMatch>) {
        val selections = matches.asSequence()
            .flatMap { it.sources.asSequence() }
            .mapNotNull(::resolverSelectionFor)
            .distinctBy { it.source to it.id }
            .take(MAX_PREFETCH_SOURCES)
            .toList()
        if (selections.isEmpty()) return

        resolverPrefetchScope.launch {
            selections.map { selection ->
                async {
                    resolverPrefetchSemaphore.withPermit {
                        runCatching { getResolverStreams(selection.source, selection.id) }
                            .onFailure {
                                Log.w(
                                    "AkSportsLiveProvider",
                                    "Resolver prefetch failed for ${selection.source}/${selection.id}: ${it.message}"
                                )
                            }
                    }
                }
            }.awaitAll()
        }
    }

    fun ownsPlaybackId(id: String): Boolean =
        SportsPlaybackId.isEncoded(id) ||
            SportsPlaybackId.isLegacy(id) ||
            id.startsWith("match-") ||
            id.matches(Regex("^[a-z0-9-]+$"))

    fun canResolvePlaybackId(id: String): Boolean =
        ownsPlaybackId(id) || sourceCache.containsKey(id)

    fun ownsServer(server: Video.Server): Boolean {
        val id = server.id.lowercase()
        val name = server.name.lowercase()
        return name.contains(SERVER_NAME_PREFIX.lowercase()) ||
                id.contains("strmfree.st") ||
                id.contains("streamfree.top") ||
                id.contains("embed.st") ||
                id.contains("streamed.pk") ||
                name.contains("ak sports")
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val url = server.src.ifBlank { server.id }
        if (url.startsWith(LOCAL_RESOLVER_SERVER_PREFIX)) {
            return@withContext resolveWithHostedResolver(url.removePrefix(LOCAL_RESOLVER_SERVER_PREFIX))
        }

        try {
            val domain = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(url)
            val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domain)
            com.nexastream.app.extractors.TokenManager.latestQuery = token.removePrefix("?")
        } catch (_: Exception) {}

        if (url.contains(".m3u8", ignoreCase = true)) {
            var finalUrl = url
            if (!finalUrl.contains("token=")) {
                try {
                    val domain = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(finalUrl)
                    val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domain)
                    finalUrl = if (finalUrl.contains("?")) "$finalUrl&${token.removePrefix("?")}" else "$finalUrl$token"
                } catch (_: Exception) {}
            }
            return@withContext Video(
                source = finalUrl,
                headers = mapOf(
                    "Referer" to "$activeHost/",
                    "Origin" to activeHost
                ),
                type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
                maintainToken = true
            )
        }

        try {
            val video = com.nexastream.app.extractors.Extractor.extract(url, server)
            if (video.source.isNotBlank() && !video.source.contains("/embed/")) {
                var finalUrl = video.source
                if (finalUrl.contains(".m3u8") && !finalUrl.contains("token=")) {
                    try {
                        val domain = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(finalUrl)
                        val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domain)
                        finalUrl = if (finalUrl.contains("?")) "$finalUrl&${token.removePrefix("?")}" else "$finalUrl$token"
                    } catch (_: Exception) {}
                }
                return@withContext video.copy(
                    source = finalUrl,
                    headers = mapOf(
                        "Referer" to "$activeHost/",
                        "Origin" to activeHost
                    ),
                    type = androidx.media3.common.MimeTypes.APPLICATION_M3U8
                )
            }
        } catch (e: Exception) {
            Log.w("AkSportsLiveProvider", "Extractor failed for $url: ${e.message}")
        }

        val resolved = resolveEmbedStream(url)
        if (!resolved.isNullOrBlank()) {
            return@withContext Video(
                source = resolved,
                headers = mapOf(
                    "Referer" to "$activeHost/",
                    "Origin" to activeHost
                ),
                type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
                maintainToken = true
            )
        }

        throw Exception("Could not resolve real stream source for URL: $url")
    }

    internal suspend fun resolveEmbedStream(embedUrl: String): String? = withContext(Dispatchers.IO) {
        val resolvedM3u8 = resolveWithWebView(embedUrl)
        if (!resolvedM3u8.isNullOrBlank()) return@withContext resolvedM3u8
        null
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(embedUrl: String): String? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val context = com.nexastream.app.NexastreamApp.instance.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val webView = java.util.concurrent.atomic.AtomicReference<android.webkit.WebView?>()

        fun cleanup() {
            handler.removeCallbacksAndMessages(null)
            webView.getAndSet(null)?.let { view ->
                view.stopLoading()
                view.destroy()
            }
        }

        handler.post {
            if (!continuation.isActive || completed.get()) return@post
            val wv = android.webkit.WebView(context)
            webView.set(wv)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
            }

            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""
                    if (url.contains(".m3u8", ignoreCase = true)) {
                        handler.post {
                            if (completed.compareAndSet(false, true)) {
                                cleanup()
                                if (continuation.isActive) {
                                    continuation.resume(url)
                                }
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            wv.loadUrl(embedUrl)
        }

        handler.postDelayed({
            if (completed.compareAndSet(false, true)) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }, 15000)

        continuation.invokeOnCancellation {
            completed.set(true)
            handler.post { cleanup() }
        }
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> = emptyList()
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = throw UnsupportedOperationException()
    override suspend fun getTvShow(id: String): TvShow = throw UnsupportedOperationException()
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = throw UnsupportedOperationException()
    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()

    internal suspend fun loadSportServers(
        sources: List<SportMatch.MatchSource>,
        streamLoader: suspend (source: String, id: String) -> List<SportStream>,
    ): List<Video.Server> = coroutineScope {
        sources
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { source ->
                async {
                    try {
                        val embedUrl = if (source.id.startsWith("http")) source.id else "$activeHost/embed/soccer/${source.id}"
                        listOf(
                            Video.Server(
                                id = embedUrl,
                                name = "AK Sports - ${source.source}",
                                src = embedUrl
                            )
                        )
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
    }
}
