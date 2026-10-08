package com.nexastream.app.providers

import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AkSportsLiveProvider : IptvProvider {
    private var activeHost: String = "https://streamed.st"
    private val FALLBACK_HOSTS = listOf(
        "https://streamed.pk",
        "https://streamed.is",
        "https://v3.streamed.su",
        "https://strmd.link",
        "https://streampk.org"
    )
    private const val SERVER_NAME_PREFIX = "AK Sports - "
    
    private val client = com.nexastream.app.utils.NetworkClient.compatibleTrustAll.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
        
    private val sourceCache = ConcurrentHashMap<String, List<SportMatch.MatchSource>>()
    private val persistedMatches = ConcurrentHashMap<String, SportMatch>()
    private val categoryMetadata = ConcurrentHashMap<String, String>()

    override val baseUrl: String get() = activeHost
    override val name: String = "AK Sports Live"
    override val logo: String = ""
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
            val body = fetchWithFallback("/api/matches/live", "/api/matches/all-today", "/matches/live", "/matches/all-today")
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

    suspend fun getUpcomingMatches(): List<SportMatch> = withContext(Dispatchers.IO) {
        val matches = getAllMatches()
        val now = System.currentTimeMillis()
        val threeHoursAgo = now - (3 * 60 * 60 * 1000)
        
        matches.filter { (it.date ?: 0) > threeHoursAgo }
            .sortedByDescending { it.title.contains("Chelsea", ignoreCase = true) || it.league.contains("PREMIER LEAGUE", ignoreCase = true) }
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
                } else {
                    sources.add(SportMatch.MatchSource("alpha", matchId))
                    sources.add(SportMatch.MatchSource("beta", matchId))
                    sources.add(SportMatch.MatchSource("delta", matchId))
                    sources.add(SportMatch.MatchSource("gamma", matchId))
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
        val imageUrl = if (cleanPath.contains("images/")) "$baseImageUrl/$cleanPath" else "$baseImageUrl/api/$cleanPath"
        
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

        val resolvedSources = sources.ifEmpty {
            listOf(
                SportMatch.MatchSource(source = "alpha", id = matchInfo?.id ?: request.matchId),
                SportMatch.MatchSource(source = "beta", id = matchInfo?.id ?: request.matchId)
            )
        }

        try {
            val normalSources = resolvedSources.filter { !it.source.startsWith("event-") }
            val directSources = resolvedSources.filter { it.source.startsWith("event-") }
            
            if (normalSources.isNotEmpty()) {
                servers.addAll(loadSportServers(normalSources, ::getStreams))
            }
            
            directSources.forEach { source ->
                val url = source.id
                val name = source.source.removePrefix("event-")
                servers.add(Video.Server(id = url, name = "AK Sports - $name", src = url))
            }
        } catch (e: Exception) {}

        val numericId = request.matchId.substringAfterLast("-")
        val isNumericId = numericId.all { it.isDigit() }
        val idsToTry = if (isNumericId) listOf(request.matchId, numericId).distinct() else listOf(request.matchId)
        
                val hostsToTry = listOf("https://streamed.st", "https://streamed.pk", "https://streamed.is", "https://v3.streamed.su", "https://strmd.link", "https://streampk.org", "https://embed.st")
        val mirrorTypes = listOf("alpha", "beta", "delta", "gamma", "omega")
        
        hostsToTry.forEach { host ->
            mirrorTypes.forEach { type ->
                idsToTry.forEach { mId ->
                    val url = "$host/embed/$type/$mId/1"
                    if (servers.none { it.id == url }) {
                        val hostLabel = host.substringAfter("//").substringBefore(".")
                        val mirrorName = "Mirror ${servers.size + 1} ($type) [$hostLabel]"
                        servers.add(Video.Server(id = url, name = mirrorName, src = url))
                    }
                }
            }
        }

        matchInfo?.let { match ->
            if (match.homeTeam.isNotBlank() && match.awayTeam.isNotBlank()) {
                try {
                    val crossMirrors = withContext(Dispatchers.IO) {
                        kotlinx.coroutines.withTimeoutOrNull(8000) {
                            findCrossProviderMirrors(match.homeTeam, match.awayTeam)
                        } ?: emptyList()
                    }
                    servers.addAll(crossMirrors)
                } catch (e: Exception) {}
            }
        }
        
        servers.distinctBy { it.id }
    }

    private suspend fun findCrossProviderMirrors(home: String, away: String): List<Video.Server> = coroutineScope {
        val nHome = normalizeTeam(home)
        val nAway = normalizeTeam(away)
        
        val cdnDeferred = async {
            runCatching {
                CdnLiveTvProvider.getHome()
                    .flatMap { it.list }
                    .filterIsInstance<SportMatch>()
                    .firstOrNull { item ->
                        val title = normalizeTeam(item.title)
                        title.contains(nHome) && title.contains(nAway)
                    }?.let { match ->
                        CdnLiveTvProvider.getServers(match.id, Video.Type.Movie(match.id, match.title, "", "", null))
                            .map { it.copy(name = "CDN Mirror - ${it.name}") }
                    }
            }.getOrNull() ?: emptyList()
        }

        val pelotaLibreDeferred = async {
            runCatching {
                PelotaLibreTvHdProvider.getHome()
                    .find { it.name == "Agenda Deportiva" }
                    ?.list?.filterIsInstance<TvShow>()
                    ?.firstOrNull { item ->
                        val title = normalizeTeam(item.title)
                        title.contains(nHome) && title.contains(nAway)
                    }?.let { match ->
                        PelotaLibreTvHdProvider.getServers(match.id, Video.Type.Movie(match.id, match.title, "", "", null))
                            .map { it.copy(name = "Pelota Libre - ${it.name}") }
                    }
            }.getOrNull() ?: emptyList()
        }

        val tvLibreDeferred = async {
            runCatching {
                TvLibrefutbolProvider.getHome()
                    .flatMap { it.list }
                    .filterIsInstance<TvShow>()
                    .firstOrNull { item ->
                        val title = normalizeTeam(item.title)
                        title.contains(nHome) && title.contains(nAway)
                    }?.let { match ->
                        TvLibrefutbolProvider.getServers(match.id, Video.Type.Movie(match.id, match.title, "", "", null))
                            .map { it.copy(name = "TV Libre - ${it.name}") }
                    }
            }.getOrNull() ?: emptyList()
        }

        (cdnDeferred.await() + pelotaLibreDeferred.await() + tvLibreDeferred.await())
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

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifBlank { server.id }
        try {
            val domain = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(url)
            val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domain)
            com.nexastream.app.extractors.TokenManager.latestQuery = token.removePrefix("?")
        } catch (e: Exception) {}

        return try {
            com.nexastream.app.extractors.Extractor.extract(url, server)
        } catch (e: Exception) {
            val headers = mutableMapOf<String, String>()
            if (url.contains("crichd")) {
                headers["Referer"] = "https://crichd.online/"
            } else if (url.contains("embed.st") || url.contains("streamed")) {
                headers["Referer"] = "https://streamed.st/"
            } else {
                try {
                    val uri = java.net.URI(url)
                    headers["Referer"] = "${uri.scheme}://${uri.host}/"
                } catch (ex: Exception) {
                    headers["Referer"] = "https://streamed.st/"
                }
            }
            headers["Origin"] = headers["Referer"]?.trimEnd('/') ?: "https://streamed.st"
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            Video(source = url, headers = headers, maintainToken = true)
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
            .filter { it.embedUrl.isNotBlank() }
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
