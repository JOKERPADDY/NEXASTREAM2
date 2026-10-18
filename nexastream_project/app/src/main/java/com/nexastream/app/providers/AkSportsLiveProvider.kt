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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
    private var activeHost: String = "https://streamed.pk"
    private val FALLBACK_HOSTS = listOf(
        "https://embed.st",
        "https://streampk.org"
    )
    private const val SERVER_NAME_PREFIX = "AK Sports - "
    private const val LOCAL_RESOLVER_SERVER_PREFIX = "nexastream-resolver:"
    
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

    override val baseUrl: String get() = activeHost
    override val name: String = "AK Sports Live"
    override val logo: String = "https://streamed.pk/logo.png"
    override val language: String = "en"

    override suspend fun getHome(): List<Category> = coroutineScope {
        val liveDeferred = async { getLiveMatches() }
        val allDeferred = async { getAllMatches() }
        val eventsDeferred = async { getEvents() }
        val sportsCatsDeferred = async { getSportsCategories() }
        
        val liveMatches = liveDeferred.await()
        val allMatches = allDeferred.await()
        val sportsEvents = eventsDeferred.await()
        val sportsCats = sportsCatsDeferred.await()
        
        Log.d("AkSportsLiveProvider", "getHome Summary: live=${liveMatches.size}, all=${allMatches.size}, events=${sportsEvents.size}, cats=${sportsCats.size}")

        // Update persisted agenda
        liveMatches.forEach { persistedMatches[it.id] = it }
        allMatches.forEach { persistedMatches[it.id] = it }
        sportsEvents.forEach { persistedMatches[it.id] = it }

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
        
        if (sportsEvents.isNotEmpty()) {
            val agendaList = sportsEvents.filter { it.status != "LIVE" }
            if (agendaList.isNotEmpty()) {
                result.add(Category(
                    name = "Daily Agenda",
                    list = agendaList.sortedBy { it.date ?: Long.MAX_VALUE }
                ).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
            }
        }

        if (sportsCats.isNotEmpty()) {
            val sortOrder = listOf("All", "Football", "Cricket", "Boxing", "Motorsport", "Motorsports", "Basketball", "Baseball", "WWE", "Wwe")
            val sortedCats = sportsCats.sortedWith { c1, c2 ->
                val i1 = sortOrder.indexOf(c1.title).let { if (it == -1) 99 else it }
                val i2 = sortOrder.indexOf(c2.title).let { if (it == -1) 99 else it }
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
        
        // 1. Fetch category metadata (logos) from event_cats.txt
        for (host in hosts) {
            for (path in listOf("/event_cats.txt", "/api/event_cats.txt")) {
                try {
                    val url = if (host.endsWith("/") && path.startsWith("/")) host + path.substring(1) else host + path
                    val result = executeRequest(url)
                    if (result.isNotBlank() && result.startsWith("{")) {
                        val json = JSONObject(result)
                        json.keys().forEach { name ->
                            categoryMetadata[name] = json.optString(name)
                        }
                        Log.i("AkSportsLiveProvider", "Successfully fetched event_cats.txt from: $url")
                        break
                    }
                } catch (e: Exception) {}
            }
        }

        // 2. Fetch category list from sports.txt
        var body = ""
        for (host in hosts) {
            for (path in listOf("/sports.txt", "/categories.txt", "/api/sports.txt", "/api/categories.txt")) {
                try {
                    val url = if (host.endsWith("/") && path.startsWith("/")) host + path.substring(1) else host + path
                    val result = executeRequest(url)
                    if (result.isNotBlank() && result.startsWith("[")) {
                        body = result
                        Log.i("AkSportsLiveProvider", "Successfully fetched sports.txt from: $url")
                        break
                    }
                } catch (e: Exception) {}
            }
            if (body.isNotBlank()) break
        }

        val cats = mutableListOf<TvShow>()
        if (body.isNotBlank()) {
            try {
                val jsonArray = JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    try {
                        val obj = JSONObject(jsonArray.getJSONObject(i).optString("cat", "{}"))
                        if (!obj.optBoolean("visible", true)) continue
                        
                        val name = obj.optString("name").replaceFirst("psp_", "")
                        val logo = obj.optString("logo").ifBlank { categoryMetadata[name] ?: "" }
                        
                        cats.add(
                            TvShow(
                                id = "ak-cat-$name",
                                title = name,
                                providerName = "AK Sports",
                                poster = normalizePosterUrl(logo),
                                quality = "HD"
                            ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
                        )
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
        }
        
        if (cats.isEmpty()) {
            val derived = persistedMatches.values
                .mapNotNull { it.sport?.takeIf { it.isNotBlank() } }
                .distinct()
                .map { name ->
                    val logo = categoryMetadata[name] ?: ""
                    TvShow(
                        id = "ak-cat-$name",
                        title = name,
                        providerName = "AK Sports",
                        poster = normalizePosterUrl(logo),
                        quality = "HD"
                    ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
                }
            cats.addAll(derived)
        }
        cats
    }

    suspend fun getEvents(): List<SportMatch> = withContext(Dispatchers.IO) {
        val body = fetchWithFallback("/api/matches/all-today", "/events.txt", "/api/events.txt", "/live/events.txt", "/admin/events.txt")
        if (body.isBlank()) return@withContext emptyList()
        
        try {
            val jsonArray = JSONArray(body)
            val events = mutableListOf<SportMatch>()
            
            if (jsonArray.length() > 0) {
                val firstObj = jsonArray.optJSONObject(0)
                if (firstObj != null && firstObj.has("event")) {
                    for (i in 0 until jsonArray.length()) {
                        try {
                            val wrapper = jsonArray.getJSONObject(i)
                            val eventStr = wrapper.optString("event")
                            if (eventStr.isBlank()) continue
                            
                            val obj = JSONObject(eventStr)
                            if (!obj.optBoolean("visible", true)) continue
                            
                            val teamA = obj.optString("teamAName")
                            val teamB = obj.optString("teamBName")
                            val date = obj.optString("date")
                            val time = obj.optString("time")
                            val league = obj.optString("category")
                            val isLive = isEventLive(date, time)
                            
                            val sources = mutableListOf<SportMatch.MatchSource>()
                            val links = obj.optString("links")
                            val linkNames = obj.optJSONArray("link_names")
                            if (links.isNotBlank()) {
                                val urls = links.split("|")
                                for (j in urls.indices) {
                                    val name = linkNames?.optString(j) ?: "Server ${j + 1}"
                                    sources.add(SportMatch.MatchSource("event-$name", urls[j]))
                                }
                            }

                            events.add(
                                SportMatch(
                                    id = "event-${(teamA + teamB + date).hashCode()}",
                                    title = obj.optString("eventName", "$teamA vs $teamB"),
                                    homeTeam = teamA,
                                    awayTeam = teamB,
                                    league = league.uppercase(),
                                    status = if (isLive) "LIVE" else "UPCOMING",
                                    time = if (isLive) "Live" else time,
                                    score = "vs",
                                    sport = league,
                                    poster = normalizePosterUrl(obj.optString("eventLogo")),
                                    date = null,
                                    sources = sources
                                ).apply { itemType = AppAdapter.Type.SPORT_MATCH_ITEM }
                            )
                        } catch (e: Exception) {}
                    }
                } else {
                    return@withContext parseMatches(jsonArray, "UPCOMING")
                }
            }
            events
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isEventLive(date: String?, time: String?): Boolean {
        if (date.isNullOrBlank() || time.isNullOrBlank()) return false
        return try {
            val formats = listOf("dd/MM/yyyy|HH:mm", "yyyy-MM-dd|HH:mm")
            var eventDate: java.util.Date? = null
            for (format in formats) {
                try {
                    val sdf = java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
                    eventDate = sdf.parse("$date|$time")
                    if (eventDate != null) break
                } catch (e: Exception) {}
            }
            
            val parsedDate = eventDate ?: return false
            val now = System.currentTimeMillis()
            val duration = 3 * 60 * 60 * 1000
            now >= (parsedDate.time - 10 * 60 * 1000) && now <= (parsedDate.time + duration)
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun getAllMatches(): List<SportMatch> = withContext(Dispatchers.IO) {
        try {
            val body = fetchWithFallback("/api/matches/all-today", "/api/matches/all", "/matches/all-today", "/matches/all", "/api/matches/live")
            if (body.isBlank()) {
                Log.w("AkSportsLiveProvider", "getAllMatches: Received empty body")
                return@withContext emptyList()
            }
            Log.d("AkSportsLiveProvider", "getAllMatches: Body length = ${body.length}")
            parseMatches(JSONArray(body), "UPCOMING").also(::cacheMatchSources)
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error fetching all matches: ${e.message}")
            emptyList()
        }
    }

    suspend fun getLiveMatches(): List<SportMatch> = withContext(Dispatchers.IO) {
        try {
            val body = fetchWithFallback("/api/matches/live", "/api/matches/all-today", "/matches/live", "/matches/all-today")
            if (body.isBlank()) {
                Log.w("AkSportsLiveProvider", "getLiveMatches: Received empty body")
                return@withContext emptyList()
            }
            Log.d("AkSportsLiveProvider", "getLiveMatches: Body length = ${body.length}")
            parseMatches(JSONArray(body), "LIVE")
                .also(::cacheMatchSources)
                .sortedByDescending { it.title.contains("Chelsea", ignoreCase = true) || it.league.contains("PREMIER LEAGUE", ignoreCase = true) }
        } catch (e: Exception) {
            Log.e("AkSportsLiveProvider", "Error fetching live matches: ${e.message}")
            emptyList()
        }
    }

    suspend fun getUpcomingMatches(): List<SportMatch> = coroutineScope {
        val allDeferred = async { getAllMatches() }
        val eventsDeferred = async { getEvents() }
        val liveDeferred = async { getLiveMatches() }

        val allMatches = allDeferred.await()
        val events = eventsDeferred.await()
        val liveMatches = liveDeferred.await()

        allMatches.forEach { persistedMatches[it.id] = it }
        events.forEach { persistedMatches[it.id] = it }

        val liveIds = liveMatches.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val threeHoursAgo = now - (3 * 60 * 60 * 1000)

        val upcomingList = (allMatches + events + persistedMatches.values)
            .filter { it.status != "LIVE" && it.id !in liveIds && ((it.date ?: 0L) == 0L || (it.date ?: 0L) > threeHoursAgo) }
            .distinctBy { it.id }
            .sortedBy { it.date ?: Long.MAX_VALUE }

        upcomingList
    }

    private fun fetchWithFallback(vararg paths: String): String {
        val hosts = (listOf(activeHost) + FALLBACK_HOSTS).distinct()
        for (host in hosts) {
            for (path in paths) {
                repeat(2) {
                    try {
                        val url = if (host.endsWith("/") && path.startsWith("/")) host + path.substring(1) else host + path
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

    private suspend fun parseMatches(jsonArray: JSONArray, status: String): List<SportMatch> = coroutineScope {
        val deferredMatches = (0 until jsonArray.length()).map { i ->
            async {
                val obj = jsonArray.getJSONObject(i)
                val matchId = obj.optString("id")
                val title = obj.optString("title")
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

                val sourcesArray = obj.optJSONArray("sources")
                val sources = mutableListOf<SportMatch.MatchSource>()
                if (sourcesArray != null && sourcesArray.length() > 0) {
                    for (j in 0 until sourcesArray.length()) {
                        val sObj = sourcesArray.getJSONObject(j)
                        val source = sObj.optString("source")
                        val sourceId = sObj.optString("id")
                        if (source.isNotBlank() && sourceId.isNotBlank()) {
                            sources.add(SportMatch.MatchSource(source, sourceId))
                        }
                    }
                }

                val poster = obj.optString("poster").ifBlank { obj.optString("eventLogo") }.takeIf { it.isNotBlank() }
                val category = obj.optString("category")
                if (category.isNotBlank() && !poster.isNullOrBlank()) {
                    categoryMetadata[category] = poster
                }

                val rawDate = obj.optLong("date")
                val matchDate = if (rawDate > 0 && rawDate < 1000000000000L) rawDate * 1000 else rawDate
                
                val formattedTime = if (status == "LIVE") {
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
                    league = obj.optString("category").uppercase(),
                    status = obj.optString("status", status).uppercase(),
                    time = formattedTime,
                    score = score,
                    sport = obj.optString("category"),
                    poster = normalizePosterUrl(poster),
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

    private fun normalizePosterUrl(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        
        if (poster.startsWith("http") && !poster.contains("streamed") && !poster.contains("embed.st")) {
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
        if (cleanPath.isNullOrBlank()) return null

        val baseImageUrl = activeHost.removeSuffix("/")
        val imageUrl = "$baseImageUrl/api/$cleanPath"
        
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        
        return com.nexastream.app.utils.ArtworkRequestHeaders.withHeaders(
            imageUrl,
            referer = "$baseImageUrl/",
            origin = baseImageUrl,
            userAgent = userAgent
        ) ?: imageUrl
    }

    suspend fun getStreams(source: String, id: String): List<SportStream> = withContext(Dispatchers.IO) {
        Log.e("AkSportsLiveProvider", "getStreams: source=$source, id=$id")
        try {
            val body = fetchWithFallback("/api/stream/$source/$id", "/stream/$source/$id")
            if (body.isBlank()) return@withContext emptyList()
            
            var processedBody = body
            var jsonObject: JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }
            var jsonArray: JSONArray? = try { JSONArray(body) } catch (e: Exception) { null }
            
            if (jsonObject == null && jsonArray == null) {
                val decoded = com.nexastream.app.utils.SportsSecurityUtils.decodeObfuscatedString(body)
                if (decoded.contains("{")) {
                    processedBody = decoded
                    jsonObject = try { JSONObject(decoded) } catch (e: Exception) { null }
                    jsonArray = try { JSONArray(decoded) } catch (e: Exception) { null }
                }
            }

            val streamsArray = when {
                jsonObject != null -> {
                    val linkKey = jsonObject.optString("link_key", "playback_url")
                    if (jsonObject.has("streams")) jsonObject.optJSONArray("streams") 
                    else if (jsonObject.has(linkKey) || jsonObject.has("playback_url") || jsonObject.has("url")) {
                         JSONArray().apply { put(jsonObject as Any) }
                    } else null
                }
                jsonArray != null -> jsonArray
                else -> null
            }

            val salt = jsonObject?.optString("default_string")?.takeIf { it.isNotBlank() } ?: "9HY(#b1q6"
            com.nexastream.app.utils.SportsSecurityUtils.activeSalt = salt

            if (streamsArray == null && processedBody.startsWith("http")) {
                if (processedBody.contains("127.0.0.1")) return@withContext emptyList()
                
                var finalUrl = processedBody
                if (processedBody.contains(".m3u8") && !processedBody.contains("token=")) {
                    val domainPart = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(processedBody)
                    val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domainPart)
                    finalUrl = if (processedBody.contains("?")) "$processedBody&${token.removePrefix("?")}" else "$processedBody$token"
                }

                return@withContext listOf(
                    SportStream(
                        id = "$source-$id-1",
                        streamNo = 1,
                        language = "Unknown",
                        hd = true,
                        embedUrl = finalUrl,
                        source = source,
                        thumbnail = null
                    )
                )
            }

            if (streamsArray == null) return@withContext emptyList()

            val streams = mutableListOf<SportStream>()
            for (i in 0 until streamsArray.length()) {
                val obj = streamsArray.getJSONObject(i)
                val linkKey = obj.optString("link_key", "playback_url")
                var embedUrl = obj.optString("embedUrl")
                    .ifBlank { obj.optString(linkKey) }
                    .ifBlank { obj.optString("playback_url") }
                
                if (embedUrl.isBlank()) continue
                
                val type = obj.optString("type", "ls")
                var isJsonParsed = false
                if (type == "sp" || type == "json") {
                    val decoded = com.nexastream.app.utils.SportsSecurityUtils.decodeObfuscatedString(embedUrl)
                    if (decoded.startsWith("http")) {
                        embedUrl = decoded
                    } else if (type == "json") {
                        val targetJson = when {
                            decoded.trim().startsWith("{") || decoded.trim().startsWith("[") -> decoded.trim()
                            embedUrl.trim().startsWith("{") || embedUrl.trim().startsWith("[") -> embedUrl.trim()
                            else -> ""
                        }
                        if (targetJson.isNotBlank()) {
                            try {
                                if (targetJson.startsWith("{")) {
                                    val innerObj = JSONObject(targetJson)
                                    val innerUrl = innerObj.optString("url")
                                        .ifBlank { innerObj.optString("playback_url") }
                                        .ifBlank { innerObj.optString("embedUrl") }
                                        .ifBlank { innerObj.optString("link") }
                                    if (innerUrl.isNotBlank()) {
                                        embedUrl = innerUrl
                                        val innerLang = innerObj.optString("language")
                                        val innerHd = if (innerObj.has("hd")) innerObj.optBoolean("hd") else obj.optBoolean("hd")
                                        val innerStreamNo = innerObj.optInt("streamNo", obj.optInt("streamNo", i + 1))
                                        
                                        streams.add(
                                            SportStream(
                                                id = innerObj.optString("id").ifBlank { obj.optString("id").ifBlank { "$source-$id-${i + 1}" } },
                                                streamNo = innerStreamNo,
                                                language = innerLang.ifBlank { obj.optString("language").ifBlank { "Unknown" } },
                                                hd = innerHd,
                                                embedUrl = embedUrl,
                                                source = innerObj.optString("source").ifBlank { obj.optString("source").ifBlank { source } },
                                                thumbnail = innerObj.optString("thumbnail").takeIf(String::isNotBlank) ?: obj.optString("thumbnail").takeIf(String::isNotBlank),
                                                healthScore = if (innerObj.has("healthScore")) innerObj.optInt("healthScore") else (obj.optInt("healthScore").takeIf { obj.has("healthScore") })
                                            )
                                        )
                                        isJsonParsed = true
                                    }
                                } else if (targetJson.startsWith("[")) {
                                    val innerArray = JSONArray(targetJson)
                                    for (j in 0 until innerArray.length()) {
                                        val innerObj = innerArray.getJSONObject(j)
                                        val innerUrl = innerObj.optString("url")
                                            .ifBlank { innerObj.optString("playback_url") }
                                            .ifBlank { innerObj.optString("embedUrl") }
                                            .ifBlank { innerObj.optString("link") }
                                        if (innerUrl.isNotBlank()) {
                                            val innerLang = innerObj.optString("language")
                                            val innerHd = if (innerObj.has("hd")) innerObj.optBoolean("hd") else obj.optBoolean("hd")
                                            
                                            streams.add(
                                                SportStream(
                                                    id = innerObj.optString("id").ifBlank { "$source-$id-${i + 1}-$j" },
                                                    streamNo = innerObj.optInt("streamNo", i + 1),
                                                    language = innerLang.ifBlank { obj.optString("language").ifBlank { "Unknown" } },
                                                    hd = innerHd,
                                                    embedUrl = innerUrl,
                                                    source = innerObj.optString("source").ifBlank { obj.optString("source").ifBlank { source } },
                                                    thumbnail = innerObj.optString("thumbnail").takeIf(String::isNotBlank) ?: obj.optString("thumbnail").takeIf(String::isNotBlank),
                                                    healthScore = if (innerObj.has("healthScore")) innerObj.optInt("healthScore") else (obj.optInt("healthScore").takeIf { obj.has("healthScore") })
                                                )
                                            )
                                            isJsonParsed = true
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("AkSportsLiveProvider", "Error parsing embedded json stream: ${e.message}")
                            }
                        }
                    }
                }

                if (!isJsonParsed) {
                    streams.add(
                        SportStream(
                            id = obj.optString("id").ifBlank { "$source-$id-${i + 1}" },
                            streamNo = obj.optInt("streamNo", i + 1),
                            language = obj.optString("language").ifBlank { "Unknown" },
                            hd = obj.optBoolean("hd"),
                            embedUrl = embedUrl,
                            source = obj.optString("source").ifBlank { source },
                            thumbnail = obj.optString("thumbnail").takeIf(String::isNotBlank),
                            healthScore = obj.optInt("healthScore").takeIf { obj.has("healthScore") }
                        )
                    )
                }
            }
            streams
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> = coroutineScope {
        val request = try { SportsPlaybackId.decode(id) } catch(e: Exception) { SportsPlaybackRequest(id, emptyList()) }
        
        val liveMatchesDeferred = async { getLiveMatches() }
        val upcomingMatchesDeferred = async { getUpcomingMatches() }
        
        val liveMatches = liveMatchesDeferred.await()
        val upcomingMatches = upcomingMatchesDeferred.await()
        val allMatches = liveMatches + upcomingMatches

        var matchInfo = allMatches.find { it.id == request.matchId }
        
        if (matchInfo == null && videoType is Video.Type.Movie) {
            val targetTitle = normalizeTeam(videoType.title)
            matchInfo = allMatches.find { m ->
                val currentTitle = normalizeTeam(m.title)
                currentTitle == targetTitle || currentTitle.contains(targetTitle) || targetTitle.contains(currentTitle)
            }
        }

        val servers = mutableListOf<Video.Server>()
        
        val sources = request.sources.ifEmpty {
            sourceCache[request.matchId] ?: matchInfo?.sources ?: emptyList()
        }

        val resolvedSources = sources

        try {
            val normalSources = resolvedSources.filter { !it.source.startsWith("event-") }
            val directSources = resolvedSources.filter { it.source.startsWith("event-") }
            
            if (normalSources.isNotEmpty()) {
                val resolverServers = loadResolverServers(normalSources)
                if (resolverServers.isNotEmpty()) {
                    servers.addAll(resolverServers)
                } else {
                    servers.addAll(loadSportServers(normalSources, ::getStreams))
                }
            }
            
            directSources.forEach { source ->
                val url = source.id
                val name = source.source.removePrefix("event-")
                servers.add(Video.Server(id = url, name = "AK Sports - $name", src = url))
            }
        } catch (e: Exception) {}

        if (servers.isEmpty() && resolvedSources.isNotEmpty()) {
            for (s in resolvedSources) {
                val url = "${activeHost}/embed/${s.source}/${s.id}/1"
                servers.add(Video.Server(id = url, name = "AK Sports - ${s.source}", src = url))
            }
        }
        
        servers.distinctBy { it.id }
    }

    private fun normalizeTeam(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    suspend fun getServersForTeams(home: String, away: String): List<Video.Server> = withContext(Dispatchers.IO) {
        val nHome = normalizeTeam(home)
        val nAway = normalizeTeam(away)
        
        val match = getLiveMatches().find { 
            val title = normalizeTeam(it.title)
            title.contains(nHome) && title.contains(nAway)
        }
        
        if (match != null) {
            getServers(playbackId(match), Video.Type.Movie(match.id, match.title, "", "", null))
        } else emptyList()
    }

    fun playbackId(match: SportMatch): String = SportsPlaybackId.encode(match)

    fun ownsPlaybackId(id: String): Boolean =
        SportsPlaybackId.isEncoded(id) ||
            SportsPlaybackId.isLegacy(id) ||
            id.startsWith("match-") ||
            id.startsWith("ppv-") ||
            id.matches(Regex("^[a-z0-9-]+-[0-9]+$"))

    fun canResolvePlaybackId(id: String): Boolean =
        ownsPlaybackId(id) || sourceCache.containsKey(id)

    fun ownsServer(server: Video.Server): Boolean {
        val id = server.id.lowercase()
        val name = server.name.lowercase()
        return name.contains(SERVER_NAME_PREFIX.lowercase()) ||
                id.contains("embed.st") ||
                id.contains("streamed.st") ||
                id.contains("streamed.su") ||
                id.contains("streamed.is") ||
                id.contains("streamed.pk") ||
                id.contains("stream.pk") ||
                id.contains("top-embed.com") ||
                id.contains("crichd") ||
                id.contains("exposestrat.com") ||
                id.contains("zohanayaan.com") ||
                name.contains("ak sports") ||
                name.contains("match!")
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val url = server.src.ifBlank { server.id }
        if (url.startsWith(LOCAL_RESOLVER_SERVER_PREFIX)) {
            return@withContext resolveWithLocalServer(url.removePrefix(LOCAL_RESOLVER_SERVER_PREFIX))
        }

        resolveEmbedWithLocalServer(url)?.let { return@withContext it }

        try {
            val domain = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(url)
            val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domain)
            com.nexastream.app.extractors.TokenManager.latestQuery = token.removePrefix("?")
        } catch (_: Exception) {}

        // If URL is already a direct .m3u8 stream, return it directly with proper token and headers
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
                    "Referer" to "https://embed.st/",
                    "Origin" to "https://embed.st"
                ),
                type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
                maintainToken = true
            )
        }

        // 1. Try extractor
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
                        "Referer" to "https://embed.st/",
                        "Origin" to "https://embed.st"
                    ),
                    type = androidx.media3.common.MimeTypes.APPLICATION_M3U8
                )
            }
        } catch (e: Exception) {
            Log.w("AkSportsLiveProvider", "Extractor failed for $url: ${e.message}")
        }

        // 2. Try resolving embed stream via API permutations
        try {
            val uri = java.net.URI(url)
            val pathSegments = uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
            if (pathSegments.size >= 3 && pathSegments[0] == "embed") {
                val source = pathSegments[1]
                val id = pathSegments.drop(2).firstOrNull { it != "1" && it != "2" } ?: pathSegments[2]
                val resolved = resolveEmbedStream(source, id)
                if (!resolved.isNullOrBlank()) {
                    var finalUrl = resolved
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
                            "Referer" to "https://embed.st/",
                            "Origin" to "https://embed.st"
                        ),
                        type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
                        maintainToken = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("AkSportsLiveProvider", "resolveEmbedStream failed for $url: ${e.message}")
        }

        throw Exception("Could not resolve real stream source for URL: $url")
    }

    private suspend fun resolveEmbedWithLocalServer(url: String): Video? {
        val pathSegments = runCatching { java.net.URI(url).path }
            .getOrNull()
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?: return null
        if (pathSegments.size < 3 || pathSegments[0] != "embed") return null

        val source = pathSegments[1]
        val id = pathSegments[2]
        val stream = pathSegments.getOrNull(3)?.toIntOrNull() ?: 1
        if (source.isBlank() || id.isBlank() || stream < 1) return null

        val encodedPayload = Base64.encodeToString(
            JSONObject()
                .put("source", source)
                .put("id", id)
                .put("stream", stream)
                .toString()
                .toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return try {
            resolveWithLocalServer(encodedPayload).also {
                Log.d("AkSportsLiveProvider", "Resolved embed server through hosted resolver: $source/$id/$stream")
            }
        } catch (e: Exception) {
            Log.w(
                "AkSportsLiveProvider",
                "Hosted resolver failed for embed server $source/$id/$stream; trying direct extractor: ${e.message}"
            )
            null
        }
    }

    private suspend fun loadResolverServers(sources: List<SportMatch.MatchSource>): List<Video.Server> = coroutineScope {
        sources
            .filter { it.source.isNotBlank() && it.id.isNotBlank() }
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
                        Log.w("AkSportsLiveProvider", "Local resolver stream lookup failed for ${source.source}/${source.id}: ${e.message}")
                        emptyList()
                    }
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
    }

    private suspend fun getResolverStreams(source: String, id: String): List<SportStream> = withContext(Dispatchers.IO) {
        val resolverUrl = UserPreferences.streamResolverUrl.trimEnd('/')
        val url = "$resolverUrl/api/streams?source=${android.net.Uri.encode(source)}&id=${android.net.Uri.encode(id)}"
        val body = executeRequest(url)
        val streamArray = JSONObject(body).optJSONArray("streams")
            ?: throw IOException("Resolver response did not contain a streams array")

        buildList {
            for (index in 0 until streamArray.length()) {
                val item = streamArray.optJSONObject(index) ?: continue
                val streamId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val streamSource = item.optString("source").takeIf { it.isNotBlank() } ?: source
                add(
                    SportStream(
                        id = streamId,
                        streamNo = item.optInt("streamNo", index + 1),
                        language = item.optString("language").ifBlank { "Unknown" },
                        hd = item.optBoolean("hd", true),
                        embedUrl = item.optString("embedUrl"),
                        source = streamSource
                    )
                )
            }
        }
    }

    private suspend fun resolveWithLocalServer(encodedPayload: String): Video {
        val payload = try {
            JSONObject(String(Base64.decode(encodedPayload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("Invalid local resolver server selection", e)
        }
        val source = payload.optString("source")
        val id = payload.optString("id")
        val stream = payload.optInt("stream", -1)
        if (source.isBlank() || id.isBlank() || stream < 1) {
            throw IOException("Local resolver server selection is missing source, id, or stream number")
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
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Local resolver returned HTTP ${response.code}: $responseText")
            }
            responseText
        }

        val result = JSONObject(responseBody)
        if (!result.optBoolean("ok")) {
            val stage = result.optString("stage").takeIf { it.isNotBlank() }
            val message = result.optString("error").ifBlank { "Resolver could not unlock this stream" }
            throw IOException(if (stage == null) message else "Resolver $stage failed: $message")
        }
        val relay = result.optString("relay")
        if (relay.isBlank()) throw IOException("Local resolver response did not include an HLS relay URL")
        return Video(
            source = relay,
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8
        )
    }

    override suspend fun search(query: String, page: Int, filters: SearchFilters?): List<AppAdapter.Item> = emptyList()
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = throw UnsupportedOperationException()
    override suspend fun getTvShow(id: String): TvShow = throw UnsupportedOperationException()
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = throw UnsupportedOperationException()
    override suspend fun getPeople(id: String, page: Int): People = throw UnsupportedOperationException()

    suspend fun resolveEmbedStream(targetSource: String, targetId: String): String? = withContext(Dispatchers.IO) {
        val hosts = (listOf(activeHost) + FALLBACK_HOSTS).distinct()
        
        val cleanId = if (targetId.contains("-") && targetId.substringAfterLast("-").all { it.isDigit() }) {
            targetId.substringBeforeLast("-")
        } else {
            targetId
        }

        val allMatches = try { getAllMatches() + getLiveMatches() } catch (_: Exception) { emptyList() }
        val matchingMatch = allMatches.find { m -> m.id == targetId || m.id == cleanId || m.sources.any { it.id == targetId || it.id == cleanId } }

        val resolvedSources = mutableListOf<SportMatch.MatchSource>()
        if (matchingMatch != null) {
            resolvedSources.addAll(matchingMatch.sources)
            resolvedSources.add(SportMatch.MatchSource("admin", matchingMatch.id))
            resolvedSources.add(SportMatch.MatchSource("admin", "ppv-${matchingMatch.id}"))
            val cleanMatchId = matchingMatch.id.substringBeforeLast("-").takeIf { matchingMatch.id.substringAfterLast("-").all { c -> c.isDigit() } } ?: matchingMatch.id
            resolvedSources.add(SportMatch.MatchSource("admin", cleanMatchId))
            resolvedSources.add(SportMatch.MatchSource("admin", "ppv-$cleanMatchId"))
        }

        resolvedSources.add(SportMatch.MatchSource(targetSource, cleanId))
        resolvedSources.add(SportMatch.MatchSource("admin", cleanId))
        resolvedSources.add(SportMatch.MatchSource("admin", "ppv-$cleanId"))
        resolvedSources.add(SportMatch.MatchSource(targetSource, targetId))
        resolvedSources.add(SportMatch.MatchSource("admin", targetId))
        resolvedSources.add(SportMatch.MatchSource("admin", "ppv-$targetId"))

        val numericId = targetId.substringAfterLast("-")
        if (numericId.isNotBlank() && numericId.all { it.isDigit() }) {
            resolvedSources.add(SportMatch.MatchSource(targetSource, numericId))
            resolvedSources.add(SportMatch.MatchSource("admin", numericId))
            resolvedSources.add(SportMatch.MatchSource("admin", "ppv-$numericId"))
        }

        val uniqueSources = resolvedSources.distinctBy { it.source to it.id }

        for (host in hosts) {
            for (s in uniqueSources) {
                try {
                    val apiUrl = "$host/api/stream/${s.source}/${s.id}"
                    val result = executeRequest(apiUrl)
                    if (result.isNotBlank() && !result.trim().equals("{}") && !result.contains("404")) {
                        val streams = parseStreamsBody(result, s.source, s.id)
                        val m3u8 = streams.firstOrNull { it.embedUrl.contains(".m3u8", ignoreCase = true) }?.embedUrl
                        if (!m3u8.isNullOrBlank()) {
                            Log.i("AkSportsLiveProvider", "Resolved embed stream via $apiUrl -> $m3u8")
                            return@withContext m3u8
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Fallback: Try resolving via WebView embed.st loader (WASM / GOAT unlock handling)
        try {
            for (s in uniqueSources.take(3)) {
                val embedUrl = "https://embed.st/embed/${s.source}/${s.id}/1"
                val resolvedM3u8 = resolveWithWebView(embedUrl)
                if (!resolvedM3u8.isNullOrBlank()) {
                    Log.i("AkSportsLiveProvider", "Resolved embed stream via WebView -> $resolvedM3u8")
                    return@withContext resolvedM3u8
                }
            }
        } catch (e: Exception) {
            Log.w("AkSportsLiveProvider", "WebView embed resolution failed: ${e.message}")
        }

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

    private fun parseStreamsBody(body: String, source: String, id: String): List<SportStream> {
        var processedBody = body
        var jsonObject: JSONObject? = try { JSONObject(body) } catch (_: Exception) { null }
        var jsonArray: JSONArray? = try { JSONArray(body) } catch (_: Exception) { null }
        
        if (jsonObject == null && jsonArray == null) {
            val decoded = com.nexastream.app.utils.SportsSecurityUtils.decodeObfuscatedString(body)
            if (decoded.contains("{")) {
                processedBody = decoded
                jsonObject = try { JSONObject(decoded) } catch (_: Exception) { null }
                jsonArray = try { JSONArray(decoded) } catch (_: Exception) { null }
            }
        }

        val streamsArray = when {
            jsonObject != null -> {
                val linkKey = jsonObject.optString("link_key", "playback_url")
                if (jsonObject.has("streams")) jsonObject.optJSONArray("streams") 
                else if (jsonObject.has(linkKey) || jsonObject.has("playback_url") || jsonObject.has("url")) {
                     JSONArray().apply { put(jsonObject as Any) }
                } else null
            }
            jsonArray != null -> jsonArray
            else -> null
        }

        val salt = jsonObject?.optString("default_string")?.takeIf { it.isNotBlank() } ?: "9HY(#b1q6"
        com.nexastream.app.utils.SportsSecurityUtils.activeSalt = salt

        if (streamsArray == null && processedBody.startsWith("http")) {
            var finalUrl = processedBody
            if (processedBody.contains(".m3u8") && !processedBody.contains("token=")) {
                val domainPart = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(processedBody)
                val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domainPart)
                finalUrl = if (processedBody.contains("?")) "$processedBody&${token.removePrefix("?")}" else "$processedBody$token"
            }
            return listOf(SportStream("$source-$id-1", 1, "Unknown", true, finalUrl, source, null))
        }

        if (streamsArray == null) return emptyList()

        val streams = mutableListOf<SportStream>()
        for (i in 0 until streamsArray.length()) {
            val obj = streamsArray.getJSONObject(i)
            val linkKey = obj.optString("link_key", "playback_url")
            var embedUrl = obj.optString("embedUrl")
                .ifBlank { obj.optString(linkKey) }
                .ifBlank { obj.optString("playback_url") }
            
            if (embedUrl.isBlank()) continue
            
            if (embedUrl.contains(".m3u8") && !embedUrl.contains("token=")) {
                val domainPart = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(embedUrl)
                val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domainPart)
                embedUrl = if (embedUrl.contains("?")) "$embedUrl&${token.removePrefix("?")}" else "$embedUrl$token"
            }

            streams.add(SportStream("$source-$id-$i", i + 1, obj.optString("language", "Unknown"), obj.optBoolean("hd", true), embedUrl, source, null))
        }
        return streams
    }

    internal suspend fun loadSportServers(
        sources: List<SportMatch.MatchSource>,
        streamLoader: suspend (source: String, id: String) -> List<SportStream>,
    ): List<Video.Server> = coroutineScope {
        val deferredMirrors = sources
            .filter { it.source.isNotBlank() && it.id.isNotBlank() }
            .distinctBy { it.source to it.id }
            .map { source ->
                async {
                    try {
                        kotlinx.coroutines.withTimeout(30000) {
                            streamLoader(source.source, source.id)
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
        val discovered = deferredMirrors.awaitAll().flatten()
        discovered
            .filter { it.embedUrl.isNotBlank() && it.embedUrl.contains(".m3u8", ignoreCase = true) }
            .distinctBy { it.embedUrl }
            .map { stream ->
                Video.Server(
                    id = stream.embedUrl,
                    name = "AK Sports - ${stream.source} - ${stream.language} ${if (stream.hd) "HD" else "SD"}",
                    src = stream.embedUrl,
                )
            }
    }
}
