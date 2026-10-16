package com.nexastream.app.providers.genga

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LocalGengaServer(
    private val context: Context,
    private val port: Int = 3001
) {
    companion object {
        @Volatile
        private var guestToken: String? = null

        @Volatile
        private var instance: LocalGengaServer? = null

        fun getInstance(context: Context, port: Int = 3001): LocalGengaServer {
            return instance ?: synchronized(this) {
                instance ?: LocalGengaServer(context.applicationContext, port).also {
                    it.start()
                    instance = it
                }
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val responseCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val diskCachePrefs by lazy {
        context.getSharedPreferences("genga_disk_cache", Context.MODE_PRIVATE)
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        responseCache.clear()
        try { diskCachePrefs.edit().clear().apply() } catch (_: Exception) {}
        scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                Log.i("LocalGengaServer", "Genga Local Proxy started on 127.0.0.1:$port")
                while (isActive && isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                Log.e("LocalGengaServer", "Error in Genga server socket loop: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val line = reader.readLine() ?: return@withContext
                val parts = line.split(" ")
                if (parts.size < 2) return@withContext
                val method = parts[0]
                val pathAndQuery = parts[1]

                var contentLength = 0
                var lineHeader = reader.readLine()
                while (lineHeader != null && lineHeader.isNotEmpty()) {
                    if (lineHeader.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = lineHeader.substring("Content-Length:".length).trim().toIntOrNull() ?: 0
                    }
                    lineHeader = reader.readLine()
                }

                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = reader.read(buffer, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    String(buffer, 0, totalRead)
                } else {
                    ""
                }

                val questionMarkIndex = pathAndQuery.indexOf('?')
                val path = if (questionMarkIndex != -1) pathAndQuery.substring(0, questionMarkIndex) else pathAndQuery
                val query = if (questionMarkIndex != -1) pathAndQuery.substring(questionMarkIndex + 1) else ""

                val responsePair = routeRequest(method, path, query, body)
                sendResponse(socket, responsePair.first, responsePair.second)
            } catch (e: Exception) {
                Log.e("LocalGengaServer", "Error handling client", e)
                try {
                    sendResponse(socket, 500, "{\"code\": 500, \"msg\": \"Internal Server Error\"}")
                } catch (_: Exception) {}
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun routeRequest(method: String, path: String, query: String, body: String): Pair<Int, String> {
        val qParams = parseQueryParams(query)
        return when {
            path == "/trending" -> {
                try {
                    val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/trending/v2", emptyMap(), "{}")
                    val responseMap = parseJsonToMap(rawResponse)
                    val dataMap = responseMap["data"] as? Map<String, Any?>
                    val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>>
                        ?: responseMap["data"] as? List<Map<String, Any?>>
                        ?: emptyList()
                    var mapped = list.mapNotNull { mapItem(it) }
                    if (mapped.isEmpty()) {
                        mapped = getMockTrending()
                    }
                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapOf("items" to mapped, "list" to mapped)
                    )
                    Pair(200, toJsonString(responseBody))
                } catch (e: Exception) {
                    Log.e("LocalGengaServer", "Trending error: ${e.message}")
                    val mapped = getMockTrending()
                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapOf("items" to mapped, "list" to mapped)
                    )
                    Pair(200, toJsonString(responseBody))
                }
            }
            path == "/search" -> {
                val q = qParams["q"] ?: ""
                val page = qParams["page"]?.toIntOrNull() ?: 1
                try {
                    val payload = mapOf(
                        "keyword" to q,
                        "page" to page,
                        "pageSize" to 20
                    )
                    val payloadString = toJsonString(payload)
                    val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/search", emptyMap(), payloadString)
                    val responseMap = parseJsonToMap(rawResponse)
                    val dataMap = responseMap["data"] as? Map<String, Any?>
                    val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>> 
                        ?: responseMap["data"] as? List<Map<String, Any?>> 
                        ?: emptyList()
                    var mapped = list.mapNotNull { mapItem(it) }
                    if (mapped.isEmpty() && q.isNotEmpty()) {
                        mapped = getMockTrending().filter { (it["name"] as? String)?.contains(q, ignoreCase = true) == true }
                    }
                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapOf("items" to mapped, "list" to mapped)
                    )
                    Pair(200, toJsonString(responseBody))
                } catch (e: Exception) {
                    Log.e("LocalGengaServer", "Search error: ${e.message}")
                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapOf("items" to emptyList<Map<String, Any?>>(), "list" to emptyList<Map<String, Any?>>())
                    )
                    Pair(200, toJsonString(responseBody))
                }
            }
            path.startsWith("/detail/") -> {
                val subjectId = path.substringAfter("/detail/").substringBefore("?").substringBefore("/")
                try {
                    val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to subjectId))
                    val responseMap = parseJsonToMap(rawResponse)
                    val data = responseMap["data"] as? Map<String, Any?>
                    var mapped = if (data != null) mapItem(data) else null
                    if (mapped == null) {
                        mapped = getMockTrending().find { it["id"] == subjectId } ?: getMockTrending().first()
                    }
                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapped
                    )
                    Pair(200, toJsonString(responseBody))
                } catch (e: Exception) {
                    Log.w("LocalGengaServer", "Detail error: ${e.message}")
                    val fallback = getMockTrending().find { it["id"] == subjectId } ?: getMockTrending().first()
                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to fallback
                    )
                    Pair(200, toJsonString(responseBody))
                }
            }
            path.startsWith("/episodes/") -> {
                val seriesId = path.substringAfter("/episodes/").substringBefore("?").substringBefore("/")
                try {
                    val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/season-info", mapOf("subjectId" to seriesId))
                    val responseMap = parseJsonToMap(rawResponse)
                    val data = responseMap["data"] as? Map<String, Any?> ?: responseMap

                    val rawSeasons = data["seasons"] as? List<Map<String, Any?>> ?: data["seasonList"] as? List<Map<String, Any?>> ?: data["list"] as? List<Map<String, Any?>> ?: emptyList()
                    val mappedSeasons = mutableListOf<Map<String, Any?>>()

                    for (sRaw in rawSeasons) {
                        val num = (sRaw["se"] ?: sRaw["seasonNumber"] ?: 1).toString().toDoubleOrNull()?.toInt() ?: 1
                        val eps = mutableListOf<Map<String, Any?>>()

                        val pool = when {
                            sRaw["allEp"] != null -> sRaw["allEp"]
                            sRaw["epList"] != null -> sRaw["epList"]
                            sRaw["episodeList"] != null -> sRaw["episodeList"]
                            sRaw["episodes"] != null -> sRaw["episodes"]
                            sRaw["list"] != null -> sRaw["list"]
                            sRaw["items"] != null -> sRaw["items"]
                            else -> null
                        }

                        if (pool is String) {
                            pool.split(",").forEach { eNum ->
                                val trimmed = eNum.trim()
                                if (trimmed.isNotEmpty()) {
                                    val n = trimmed.toIntOrNull() ?: 1
                                    eps.add(mapOf("episodeNumber" to n.toString(), "title" to "Episode $trimmed", "id" to "${seriesId}_${num}_${trimmed}"))
                                }
                            }
                        } else if (pool is List<*>) {
                            for (item in pool) {
                                if (item is Map<*, *>) {
                                    val en = item["ep"] ?: item["episodeNumber"] ?: item["episode_number"] ?: item["episode"]
                                    if (en != null) {
                                        val strEn = en.toString()
                                        eps.add(mapOf("episodeNumber" to strEn, "title" to (item["title"] ?: "Episode $en"), "id" to "${seriesId}_${num}_${en}"))
                                    }
                                } else if (item != null) {
                                    val strItem = item.toString()
                                    eps.add(mapOf("episodeNumber" to strItem, "title" to "Episode $item", "id" to "${seriesId}_${num}_${item}"))
                                }
                            }
                        }

                        if (eps.isEmpty()) {
                            val maxEpVal = sRaw["maxEp"] ?: sRaw["max_ep"] ?: 0
                            val maxEp = maxEpVal.toString().toDoubleOrNull()?.toInt() ?: 0
                            if (maxEp > 0) {
                                for (i in 1..maxEp) {
                                    eps.add(mapOf("episodeNumber" to i.toString(), "title" to "Episode $i", "id" to "${seriesId}_${num}_${i}"))
                                }
                            }
                        }

                        if (eps.isNotEmpty()) {
                            val seasonMap = mutableMapOf<String, Any?>()
                            seasonMap["seasonNumber"] = num
                            seasonMap["episodes"] = eps
                            seasonMap["episodeList"] = eps
                            mappedSeasons.add(seasonMap)
                        }
                    }
                    if (mappedSeasons.isEmpty()) {
                        mappedSeasons.add(
                            mapOf(
                                "seasonNumber" to 1,
                                "episodeList" to listOf(
                                    mapOf("episodeNumber" to 1, "title" to "Episode 1", "id" to "${seriesId}_1_1"),
                                    mapOf("episodeNumber" to 2, "title" to "Episode 2", "id" to "${seriesId}_1_2")
                                )
                            )
                        )
                    }
                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapOf("seasons" to mappedSeasons)
                    )
                    Pair(200, toJsonString(responseBody))
                } catch (e: Exception) {
                    Log.w("LocalGengaServer", "Episodes error: ${e.message}")
                    val fallbackSeasons = listOf(
                        mapOf(
                            "seasonNumber" to 1,
                            "episodeList" to listOf(
                                mapOf("episodeNumber" to 1, "title" to "Episode 1", "id" to "${seriesId}_1_1")
                            )
                        )
                    )
                    Pair(200, toJsonString(mapOf("code" to 0, "data" to mapOf("seasons" to fallbackSeasons))))
                }
            }
            path.startsWith("/stream/") -> {
                val subjectId = path.substringAfter("/stream/").substringBefore("?").substringBefore("/")
                val season = qParams["season"] ?: "1"
                val episode = qParams["episode"] ?: "1"
                val quality = qParams["quality"] ?: "1080p"
                val resourceId = qParams["resource_id"]

                try {
                    val params = mutableMapOf<String, String>(
                        "subjectId" to subjectId,
                        "host" to "api6.aoneroom.com"
                    )
                    params["se"] = season
                    params["ep"] = episode
                    resourceId?.let { params["resourceId"] = it }

                    val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/play-info", params)
                    val responseMap = parseJsonToMap(rawResponse)
                    val data = responseMap["data"] as? Map<String, Any?> ?: emptyMap()
                    var streams = data["streamList"] as? List<Map<String, Any?>> ?: data["streams"] as? List<Map<String, Any?>> ?: emptyList()
                    val globalCookie = responseMap["signCookie"] ?: data["signCookie"] ?: ""

                    // 1. UGC BYPASS TRAP CHECK
                    var allDummy = true
                    for (st in streams) {
                        val u = st["url"]?.toString() ?: ""
                        if (!u.contains("macdn.aoneroom.com") && !u.contains("please_update") && !u.contains("update_app") && !u.contains("b164fbfb43")) {
                            allDummy = false
                            break
                        }
                    }
                    if (streams.isEmpty()) allDummy = true

                    if (allDummy) {
                        try {
                            val ugcFirstRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/ugc/video/first-by-subject", mapOf("subjectId" to subjectId))
                            val ugcFirstMap = parseJsonToMap(ugcFirstRaw)
                            val ugcFirstData = ugcFirstMap["data"] as? Map<String, Any?>
                            val ugcVidId = ugcFirstData?.get("ugcVideoId")?.toString()
                            val belongToCollection = ugcFirstData?.get("belongToCollection") as? Map<String, Any?>
                            val collectionId = belongToCollection?.get("collectionId")?.toString()

                            val ugcStreams = mutableListOf<Map<String, Any?>>()
                            var foundTargetInCollection = false

                            if (!collectionId.isNullOrEmpty()) {
                                val ugcDlRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/ugc/collection/download-list", mapOf("collectionId" to collectionId, "page" to "1", "perPage" to "50"))
                                val ugcDlMap = parseJsonToMap(ugcDlRaw)
                                val ugcDlData = ugcDlMap["data"] as? Map<String, Any?>
                                val ugcItems = ugcDlData?.get("items") as? List<Map<String, Any?>> ?: emptyList()

                                val targetEpStr = "S${season}EP${episode}"
                                val targetEpAlt = "E${episode}"

                                var targetItem: Map<String, Any?>? = null
                                for (item in ugcItems) {
                                    val epTitle = item["epTitle"]?.toString()?.uppercase() ?: ""
                                    val title = item["title"]?.toString()?.uppercase() ?: ""
                                    if (epTitle.contains(targetEpStr) || epTitle.contains(targetEpAlt) || title.contains(targetEpStr) || title.contains(targetEpAlt)) {
                                        targetItem = item
                                        break
                                    }
                                }

                                if (targetItem == null && episode == "1" && ugcItems.isNotEmpty()) {
                                    targetItem = ugcItems[0]
                                }

                                if (targetItem != null) {
                                    foundTargetInCollection = true
                                    val resources = targetItem["resources"] as? List<Map<String, Any?>> ?: emptyList()
                                    for (res in resources) {
                                        val rUrl = res["url"]?.toString() ?: ""
                                        if (rUrl.isNotEmpty() && !rUrl.contains("macdn.aoneroom.com") && !rUrl.contains("b164fbfb43")) {
                                            ugcStreams.add(res)
                                        }
                                    }
                                }
                            }

                            if (!foundTargetInCollection && !ugcVidId.isNullOrEmpty()) {
                                val ugcDlRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/ugc/video/download", mapOf("ugcVideoId" to ugcVidId))
                                val ugcDlMap = parseJsonToMap(ugcDlRaw)
                                val ugcDlData = ugcDlMap["data"] as? Map<String, Any?>
                                val ugcItems = ugcDlData?.get("items") as? List<Map<String, Any?>> ?: emptyList()
                                for (item in ugcItems) {
                                    val resources = item["resources"] as? List<Map<String, Any?>> ?: emptyList()
                                    for (res in resources) {
                                        val rUrl = res["url"]?.toString() ?: ""
                                        if (rUrl.isNotEmpty() && !rUrl.contains("macdn.aoneroom.com") && !rUrl.contains("b164fbfb43")) {
                                            ugcStreams.add(res)
                                        }
                                    }
                                }
                            }

                            if (ugcStreams.isNotEmpty()) {
                                streams = ugcStreams
                                Log.i("LocalGengaServer", "UGC BYPASS SUCCESS: Recovered ${ugcStreams.size} REAL streams!")
                            }
                        } catch (e: Exception) {
                            Log.w("LocalGengaServer", "UGC bypass failed: ${e.message}")
                        }
                    }

                    // 2. COOKIE BASE64 DECODING & DUMMY FILTERING WITH ENGLISH AUDIO PREFERENCE
                    val sortedStreams = streams.sortedWith { s1, s2 ->
                        val u1 = (s1["url"] ?: "").toString().lowercase()
                        val u2 = (s2["url"] ?: "").toString().lowercase()
                        val name1 = (s1["name"] ?: s1["quality"] ?: "").toString().lowercase()
                        val name2 = (s2["name"] ?: s2["quality"] ?: "").toString().lowercase()

                        fun getScore(u: String, name: String): Int {
                            var score = 0
                            val isHindi = u.contains("hindi") || u.contains("dubbed_hi") || u.contains("_hi_") || name.contains("hindi")
                            val isEnglish = u.contains("english") || u.contains("_en_") || name.contains("english")
                            if (isHindi) score -= 100
                            if (isEnglish) score += 50
                            if (u.contains(".m3u8")) score += 10
                            return score
                        }
                        getScore(u2, name2).compareTo(getScore(u1, name1))
                    }

                    var workingUrl = ""
                    for (st in sortedStreams) {
                        var u = st["url"]?.toString() ?: ""
                        val cookie = st["signCookie"]?.toString() ?: globalCookie.toString()
                        var isDummy = u.contains("macdn.aoneroom.com") || u.contains("please_update") || u.contains("update_app")

                        if (cookie.contains("urlprefix=")) {
                            try {
                                val prefixStart = cookie.indexOf("urlprefix=") + "urlprefix=".length
                                var prefixEnd = cookie.indexOf(":", prefixStart)
                                if (prefixEnd == -1) prefixEnd = cookie.indexOf(";", prefixStart)
                                if (prefixEnd == -1) prefixEnd = cookie.length
                                var base64Str = cookie.substring(prefixStart, prefixEnd)
                                val padding = (4 - base64Str.length % 4) % 4
                                base64Str += "=".repeat(padding)
                                val decodedPathBytes = Base64.decode(base64Str, Base64.DEFAULT)
                                val decodedPath = String(decodedPathBytes, StandardCharsets.UTF_8)
                                u = if (decodedPath.endsWith("/")) "${decodedPath}index.m3u8" else "${decodedPath}/index.m3u8"
                                isDummy = false
                                Log.i("LocalGengaServer", "Dummy video bypassed. Real URL: $u")
                            } catch (e: Exception) {
                                Log.w("LocalGengaServer", "Failed to decode urlprefix cookie: ${e.message}")
                            }
                        }

                        if (!isDummy && u.isNotEmpty()) {
                            workingUrl = u
                            break
                        }
                    }

                    // 3. SUBTITLES RESOLUTION
                    val subtitlesMapped = mutableListOf<Map<String, Any?>>()
                    try {
                        val subParams = mutableMapOf("subjectId" to subjectId, "se" to season, "ep" to episode)
                        val subResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get-ext-captions", subParams)
                        val subResponseMap = parseJsonToMap(subResponseRaw)
                        val subData = subResponseMap["data"] as? Map<String, Any?> ?: emptyMap()
                        val extList = (subData["extCaptions"] ?: subData["list"] ?: emptyList<Any?>()) as? List<Map<String, Any?>> ?: emptyList()
                        for (sub in extList) {
                            val lan = sub["lanName"]?.toString() ?: sub["lan"]?.toString() ?: "English"
                            val subUrl = sub["url"]?.toString() ?: ""
                            if (subUrl.isNotEmpty()) {
                                subtitlesMapped.add(mapOf("language" to lan, "url" to subUrl))
                            }
                        }
                    } catch (_: Exception) {}

                    val internalSubtitles = (data["subTitleList"] as? List<Map<String, Any?>>) ?: emptyList()
                    for (sub in internalSubtitles) {
                        val lan = sub["lanName"]?.toString() ?: sub["lan"]?.toString() ?: "English"
                        val subUrl = sub["url"]?.toString() ?: ""
                        if (subUrl.isNotEmpty() && !subtitlesMapped.any { it["url"] == subUrl }) {
                            subtitlesMapped.add(mapOf("language" to lan, "url" to subUrl))
                        }
                    }

                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapOf(
                            "streamUrl" to workingUrl,
                            "subtitles" to subtitlesMapped
                        )
                    )
                    Pair(200, toJsonString(responseBody))
                } catch (e: Exception) {
                    Log.w("LocalGengaServer", "Stream error: ${e.message}")
                    Pair(200, toJsonString(mapOf("code" to 0, "data" to mapOf("streamUrl" to "", "subtitles" to emptyList<Map<String, Any?>>()))))
                }
            }
            path == "/movies" || path == "/anime" || path == "/asian" || path == "/western" || path == "/kids" || (!path.startsWith("/detail/") && !path.startsWith("/episodes/") && !path.startsWith("/stream/") && path != "/trending" && path != "/search") -> {
                val tabId = when {
                    path == "/movies" -> "2"
                    path == "/anime" -> "8"
                    path == "/asian" -> "18"
                    path == "/western" -> "19"
                    path == "/kids" -> "23"
                    path.startsWith("/category/") -> path.removePrefix("/category/")
                    path.startsWith("/genre/") -> path.removePrefix("/genre/")
                    else -> "2"
                }
                val page = qParams["page"] ?: "1"
                try {
                    val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/tab-operating", mapOf("tabId" to tabId, "page" to page, "pageSize" to "24"))
                    val responseMap = parseJsonToMap(rawResponse)
                    val dataMap = responseMap["data"] as? Map<String, Any?>
                    val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>>
                        ?: responseMap["data"] as? List<Map<String, Any?>>
                        ?: emptyList()

                    val formatted = formatTabSections(list)
                    var flatItems = mutableListOf<Map<String, Any?>>()
                    for (sec in formatted) {
                        val items = sec["items"] as? List<Map<String, Any?>> ?: emptyList()
                        flatItems.addAll(items)
                    }

                    if (flatItems.isEmpty()) {
                        flatItems = getMockTrending().toMutableList()
                    }

                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapOf("items" to flatItems, "list" to flatItems)
                    )
                    Pair(200, toJsonString(responseBody))
                } catch (e: Exception) {
                    Log.w("LocalGengaServer", "Category error: ${e.message}")
                    val flatItems = getMockTrending()
                    val responseBody = mapOf(
                        "code" to 0,
                        "data" to mapOf("items" to flatItems, "list" to flatItems)
                    )
                    Pair(200, toJsonString(responseBody))
                }
            }
            else -> Pair(404, "{\"code\": 404, \"msg\": \"Not Found\"}")
        }
    }

    private fun getMockTrending(): List<Map<String, Any?>> {
        return listOf(
            mapOf(
                "id" to "7065948410213816232",
                "subjectId" to "7065948410213816232",
                "name" to "Moana 2",
                "title" to "Moana 2",
                "poster" to "https://image.tmdb.org/t/p/w500/a26cFiCh23192CeeC22m3ioA13R.jpg",
                "cover" to "https://image.tmdb.org/t/p/w500/a26cFiCh23192CeeC22m3ioA13R.jpg",
                "rating" to "7.2",
                "score" to "7.2",
                "year" to 2024,
                "releaseTime" to "2024",
                "subjectType" to 1,
                "isTvShow" to false,
                "description" to "Moana receives an unexpected call from her wayfinding ancestors and must journey to the far seas of Oceania."
            ),
            mapOf(
                "id" to "7065948410213816233",
                "subjectId" to "7065948410213816233",
                "name" to "Wicked",
                "title" to "Wicked",
                "poster" to "https://image.tmdb.org/t/p/w500/25L4S422R97587Y0aB4s511g1P.jpg",
                "cover" to "https://image.tmdb.org/t/p/w500/25L4S422R97587Y0aB4s511g1P.jpg",
                "rating" to "7.7",
                "score" to "7.7",
                "year" to 2024,
                "releaseTime" to "2024",
                "subjectType" to 1,
                "isTvShow" to false,
                "description" to "Elphaba, a misunderstood young woman because of her green skin, discovers her true power."
            ),
            mapOf(
                "id" to "7065948410213816234",
                "subjectId" to "7065948410213816234",
                "name" to "Gladiator II",
                "title" to "Gladiator II",
                "poster" to "https://image.tmdb.org/t/p/w500/v9L161qT92C92I1e6704N94P8P.jpg",
                "cover" to "https://image.tmdb.org/t/p/w500/v9L161qT92C92I1e6704N94P8P.jpg",
                "rating" to "6.8",
                "score" to "6.8",
                "year" to 2024,
                "releaseTime" to "2024",
                "subjectType" to 1,
                "isTvShow" to false,
                "description" to "Years after witnessing the death of Maximus at the hands of his uncle, Lucius must enter the Colosseum."
            ),
            mapOf(
                "id" to "7065948410213816235",
                "subjectId" to "7065948410213816235",
                "name" to "Squid Game - Season 2",
                "title" to "Squid Game - Season 2",
                "poster" to "https://image.tmdb.org/t/p/w500/dDlEmu3EZ0Pgg93K2SVNen33w2d.jpg",
                "cover" to "https://image.tmdb.org/t/p/w500/dDlEmu3EZ0Pgg93K2SVNen33w2d.jpg",
                "rating" to "8.0",
                "score" to "8.0",
                "year" to 2024,
                "releaseTime" to "2024",
                "subjectType" to 2,
                "isTvShow" to true,
                "description" to "Gi-hun abandons his plans to go to the US and starts a chase with a motive."
            ),
            mapOf(
                "id" to "7065948410213816236",
                "subjectId" to "7065948410213816236",
                "name" to "Solo Leveling",
                "title" to "Solo Leveling",
                "poster" to "https://image.tmdb.org/t/p/w500/geCRI233P6P91811G6E220L11Q.jpg",
                "cover" to "https://image.tmdb.org/t/p/w500/geCRI233P6P91811G6E220L11Q.jpg",
                "rating" to "8.5",
                "score" to "8.5",
                "year" to 2024,
                "releaseTime" to "2024",
                "subjectType" to 2,
                "isTvShow" to true,
                "description" to "They say whatever doesn't kill you makes you stronger, but that's not the case for the world's weakest hunter Sung Jinwoo."
            )
        )
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (query.isEmpty()) return result
        query.split("&").forEach { param ->
            val parts = param.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            result[key] = value
        }
        return result
    }

    private fun formatTabSections(items: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val sections = mutableListOf<Map<String, Any?>>()
        var isDirectMovies = true
        for (row in items) {
            if (row.containsKey("list") || row.containsKey("items") || row.containsKey("subjects") || row.containsKey("movieList") || row.containsKey("customData") || row.containsKey("banner") || row.containsKey("rankingListData")) {
                isDirectMovies = false
                break
            }
        }

        if (isDirectMovies && items.isNotEmpty()) {
            val mapped = items.mapNotNull { mapItem(it) }
            return listOf(mapOf(
                "title" to "Movies",
                "type" to "SUBJECTS_MOVIE",
                "items" to mapped
            ))
        }

        for (row in items) {
            val title = row["title"] ?: row["name"] ?: "Section"
            if (title.toString().contains("Join Us", ignoreCase = true)) continue

            val realMovies = mutableListOf<Map<String, Any?>>()
            val inner = when {
                (row["customData"] as? Map<*, *>)?.get("items").let { it is List<*> && it.isNotEmpty() } -> {
                    val customData = row["customData"] as Map<String, Any?>
                    customData["items"] as List<Map<String, Any?>>
                }
                (row["banner"] as? Map<*, *>)?.get("banners").let { it is List<*> && it.isNotEmpty() } -> {
                    val banner = row["banner"] as Map<String, Any?>
                    banner["banners"] as List<Map<String, Any?>>
                }
                row["rankingListData"].let { it is Map<*, *> && it.isNotEmpty() } -> {
                    val ranking = row["rankingListData"] as Map<String, Any?>
                    ranking["items"] as? List<Map<String, Any?>> ?: emptyList()
                }
                row["list"].let { it is List<*> && it.isNotEmpty() } -> {
                    row["list"] as List<Map<String, Any?>>
                }
                row["items"].let { it is List<*> && it.isNotEmpty() } -> {
                    row["items"] as List<Map<String, Any?>>
                }
                row["subjects"].let { it is List<*> && it.isNotEmpty() } -> {
                    row["subjects"] as List<Map<String, Any?>>
                }
                row["movieList"].let { it is List<*> && it.isNotEmpty() } -> {
                    row["movieList"] as List<Map<String, Any?>>
                }
                else -> emptyList()
            }

            for (i in inner) {
                val subject = i["subject"] as? Map<String, Any?>
                if (subject != null) {
                    realMovies.add(subject)
                } else if (i.containsKey("subjectId") || i.containsKey("id")) {
                    realMovies.add(i)
                }
            }

            if (realMovies.isNotEmpty()) {
                val mapped = realMovies.mapNotNull { mapItem(it) }
                sections.add(mapOf(
                    "title" to title,
                    "type" to (row["subjectType"] ?: row["type"] ?: "SUBJECTS_MOVIE"),
                    "items" to mapped
                ))
            }
        }
        return sections
    }

    private fun mapItem(item: Map<String, Any?>): Map<String, Any?>? {
        val itemData = (item["subject"] as? Map<String, Any?>) ?: item
        val sid = (itemData["subjectId"] ?: itemData["id"] ?: "").toString()
        if (sid.isEmpty()) return null

        val title = (
            itemData["title_en"] ?: 
            itemData["englishTitle"] ?: 
            itemData["englishName"] ?: 
            itemData["titleEn"] ?: 
            itemData["en_title"] ?: 
            itemData["title"] ?: 
            itemData["name"] ?: 
            itemData["subjectName"] ?: 
            itemData["subject_name"] ?: 
            itemData["categoryName"] ?: 
            itemData["content"] ?: 
            itemData["keyword"] ?: 
            itemData["keywordName"] ?: 
            itemData["itemName"] ?: 
            itemData["show_name"] ?: 
            itemData["showTitle"] ?: 
            itemData["titleName"] ?: 
            itemData["tag"] ?: 
            itemData["label"] ?: 
            item["title_en"] ?:
            item["englishTitle"] ?:
            item["title"] ?:
            item["name"] ?:
            ""
        ).toString()

        val fullText = "$title ${itemData["description"]} ${itemData["tags"]} ${itemData["keywords"]} ${itemData["categoryName"]}".lowercase()
        val adultKeywords = listOf("adult", "18+", "xxx", "porn", "erotic", "sex", "hentai", "nsfw", "sensual", "nude", "uncensored", "pornstar", "playboy", "penthouse")
        if (com.nexastream.app.utils.UserPreferences.familyMode && adultKeywords.any { fullText.contains(it) }) {
            Log.d("LocalGengaServer", "Filtered adult card in family mode: $title ($sid)")
            return null
        }

        var posterUrl = ""
        val poster = itemData["poster"]
        if (poster is Map<*, *>) {
            posterUrl = poster["url"]?.toString() ?: ""
        } else if (poster is String) {
            posterUrl = poster
        }
        if (posterUrl.isEmpty()) {
            val cover = itemData["cover"]
            if (cover is Map<*, *>) {
                posterUrl = cover["url"]?.toString() ?: ""
            } else if (cover is String) {
                posterUrl = cover
            }
        }
        if (posterUrl.isEmpty()) {
            val hp = itemData["horizontalPoster"] ?: itemData["horizontalCover"]
            if (hp is Map<*, *>) {
                posterUrl = hp["url"]?.toString() ?: ""
            } else if (hp is String) {
                posterUrl = hp
            }
        }
        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val score = (itemData["imdbRatingValue"] ?: itemData["imdbRate"] ?: itemData["starRating"] ?: itemData["score"] ?: "8.5").toString()
        val releaseDate = (itemData["releaseDate"] ?: itemData["releaseTime"] ?: itemData["year"] ?: "2024").toString()
        val yearInt = releaseDate.take(4).toIntOrNull() ?: 2024
        val runtime = itemData["duration"] ?: itemData["runtime"] ?: itemData["minute"] ?: ""
        val subjectType = (itemData["subjectType"] ?: itemData["type"] ?: 1).toString().toIntOrNull() ?: 1
        val isTvShow = subjectType != 1

        if (title.isEmpty()) return null

        return mapOf(
            "id" to sid,
            "subjectId" to sid,
            "name" to title,
            "title" to title,
            "poster" to posterUrl,
            "cover" to posterUrl,
            "rating" to score,
            "score" to score,
            "year" to yearInt,
            "releaseTime" to yearInt.toString(),
            "subjectType" to subjectType,
            "isTvShow" to isTvShow,
            "runtime" to runtime,
            "description" to (itemData["description"] ?: "")
        )
    }

    private suspend fun makeOfficialRequest(
        method: String,
        endpoint: String,
        queryParams: Map<String, String> = emptyMap(),
        bodyString: String = ""
    ): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("genga_prefs", Context.MODE_PRIVATE)
        val spCode = prefs.getString("sp_code", "")?.takeIf { it.isNotEmpty() } ?: "404"
        val region = prefs.getString("custom_local_iso", "")?.takeIf { it.isNotEmpty() }?.uppercase() ?: "US"
        val timezone = "America/New_York"

        val isCacheable = method == "GET"
        val cacheKey = "$method|$endpoint|${queryParams.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}"
        if (isCacheable) {
            val now = System.currentTimeMillis()
            val cached = responseCache[cacheKey]
            if (cached != null && (now - cached.first) < 300000) {
                return@withContext cached.second
            }
            val cachedDiskStr = diskCachePrefs.getString(cacheKey, null)
            if (cachedDiskStr != null) {
                val pipeIndex = cachedDiskStr.indexOf('|')
                if (pipeIndex != -1) {
                    val timeStr = cachedDiskStr.substring(0, pipeIndex)
                    val body = cachedDiskStr.substring(pipeIndex + 1)
                    val timeVal = timeStr.toLongOrNull() ?: 0L
                    if ((now - timeVal) < 300000) {
                        responseCache[cacheKey] = Pair(timeVal, body)
                        return@withContext body
                    }
                }
            }
        }

        val timestamp = System.currentTimeMillis().toString()
        val reversed = timestamp.reversed()
        val clientToken = "$timestamp,${md5(reversed)}"

        val baseUrl = "https://api.inmoviebox.com"
        val fullUrlWithoutQuery = "$baseUrl$endpoint"

        val urlBuilder = fullUrlWithoutQuery.toHttpUrl().newBuilder()
        if (!queryParams.containsKey("host")) {
            urlBuilder.addQueryParameter("host", "api.inmoviebox.com")
        }
        if (!queryParams.containsKey("lang")) {
            urlBuilder.addQueryParameter("lang", "en")
        }
        if (!queryParams.containsKey("language")) {
            urlBuilder.addQueryParameter("language", "en")
        }
        queryParams.forEach { (k, v) ->
            urlBuilder.addQueryParameter(k, v)
        }
        val httpUrl = urlBuilder.build()

        val sortedQueryString = httpUrl.queryParameterNames.sorted().joinToString("&") { name ->
            val value = httpUrl.queryParameter(name) ?: ""
            "$name=$value"
        }

        var actualBodyLength = ""
        var bodyHash = ""
        if (bodyString.isNotEmpty()) {
            val bytes = bodyString.toByteArray(Charsets.UTF_8)
            actualBodyLength = bytes.size.toString()
            val limit = minOf(bytes.size, 102400)
            val subBytes = bytes.copyOfRange(0, limit)
            bodyHash = md5Bytes(subBytes)
        }

        val canonicalPathAndQuery = httpUrl.encodedPath + if (sortedQueryString.isNotEmpty()) "?$sortedQueryString" else ""
        val accept = "application/json"
        val contentType = "application/json;charset=UTF-8"

        val canonicalString = "$method\n$accept\n$contentType\n$actualBodyLength\n$timestamp\n$bodyHash\n$canonicalPathAndQuery"

        var signatureHeader = ""
        try {
            val keyStr = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
            val keyBytes = Base64.decode(keyStr, Base64.DEFAULT)
            val signatureDigest = hmacMd5(keyBytes, canonicalString.toByteArray(Charsets.UTF_8))
            val base64Sig = Base64.encodeToString(signatureDigest, Base64.NO_WRAP)
            signatureHeader = "$timestamp|2|$base64Sig"
        } catch (e: Exception) {
            Log.e("LocalGengaServer", "Error generating signature", e)
        }

        val mediaType = "application/json;charset=UTF-8".toMediaTypeOrNull()
        val requestBody = if (method == "POST" || method == "PUT") {
            RequestBody.create(mediaType, bodyString)
        } else null

        val clientInfoMap = mapOf(
            "package_name" to "com.movieboxpro.android",
            "version_name" to "16.2.1",
            "version_code" to 16210,
            "os" to "android",
            "os_version" to "12",
            "install_ch" to "googleplay",
            "device_id" to getDeviceId(),
            "install_store" to "googleplay",
            "gaid" to "",
            "brand" to "google",
            "model" to "Pixel 6",
            "system_language" to "en",
            "net" to "wifi",
            "region" to region,
            "timezone" to timezone,
            "sp_code" to spCode
        )
        val clientInfoJson = toJsonString(clientInfoMap)

        val reqBuilder = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Content-Type", contentType)
            .header("X-Sign-Version", "2.0")
            .header("appid", "4U01pxRu278GqCZKY9")
            .header("region", region)
            .header("lang", "en")
            .header("language", "en")
            .header("os", "android")
            .header("X-Timestamp", timestamp)
            .header("Referer", "https://api.inmoviebox.com/")
            .header("X-Client-Token", clientToken)
            .header("x-tr-signature", signatureHeader)
            .header("X-Play-Mode", "2")
            .header("X-Client-Info", clientInfoJson)

        var savedToken = prefs.getString("auth_token", null)
        if (savedToken.isNullOrEmpty()) {
            if (guestToken.isNullOrEmpty()) {
                bootstrapGuestToken()
            }
            savedToken = guestToken
        }
        if (savedToken.isNullOrEmpty()) {
            savedToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjcwNjU5NDg0MTAyMTM4MTYyMzIsInV0cCI6MSwiZXhwIjoxNzkxNzMyMjMzLCJpYXQiOjE3ODM5NTU5MzN9.7iyEzTj4vWAbOF0oXwNnZ0p3Nc1QaO6K9eMiGFyVfGs"
        }
        reqBuilder.header("Authorization", "Bearer $savedToken")
        reqBuilder.header("X-Client-Status", "1")

        reqBuilder.method(method, requestBody)
        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        val resBody = response.body?.string() ?: ""
        if (isCacheable && resBody.isNotEmpty() && !resBody.contains("Internal Server Error")) {
            val now = System.currentTimeMillis()
            responseCache[cacheKey] = Pair(now, resBody)
            diskCachePrefs.edit().putString(cacheKey, "$now|$resBody").apply()
        }
        resBody
    }

    private fun bootstrapGuestToken() {
        try {
            val timestamp = System.currentTimeMillis().toString()
            val reversed = timestamp.reversed()
            val clientToken = "$timestamp,${md5(reversed)}"

            val baseUrl = "https://api.inmoviebox.com"
            val endpoint = "/wefeed-mobile-bff/tab-operating"
            val fullUrl = "$baseUrl$endpoint?host=api.inmoviebox.com&page=1&pageSize=24&tabId=1"

            val canonicalString = "GET\napplication/json\napplication/json;charset=UTF-8\n\n$timestamp\n\n/wefeed-mobile-bff/tab-operating?host=api.inmoviebox.com&page=1&pageSize=24&tabId=1"

            val keyStr = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
            val keyBytes = Base64.decode(keyStr, Base64.DEFAULT)
            val signatureDigest = hmacMd5(keyBytes, canonicalString.toByteArray(Charsets.UTF_8))
            val base64Sig = Base64.encodeToString(signatureDigest, Base64.NO_WRAP)
            val signatureHeader = "$timestamp|2|$base64Sig"

            val prefs = context.getSharedPreferences("genga_prefs", Context.MODE_PRIVATE)
            val spCode = prefs.getString("sp_code", "")?.takeIf { it.isNotEmpty() } ?: "404"
            val region = prefs.getString("custom_local_iso", "")?.takeIf { it.isNotEmpty() }?.uppercase() ?: "IN"
            val timezone = "Asia/Kolkata"

            val clientInfoMap = mapOf(
                "package_name" to "com.movieboxpro.android",
                "version_name" to "16.2.1",
                "version_code" to 16210,
                "os" to "android",
                "os_version" to "12",
                "install_ch" to "googleplay",
                "device_id" to getDeviceId(),
                "install_store" to "googleplay",
                "gaid" to "",
                "brand" to "google",
                "model" to "Pixel 6",
                "system_language" to "en",
                "net" to "wifi",
                "region" to region,
                "timezone" to timezone,
                "sp_code" to spCode
            )
            val clientInfoJson = toJsonString(clientInfoMap)

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-Sign-Version", "2.0")
                .header("appid", "4U01pxRu278GqCZKY9")
                .header("region", region)
                .header("lang", "en")
                .header("os", "android")
                .header("X-Timestamp", timestamp)
                .header("Referer", "https://api.inmoviebox.com/")
                .header("X-Client-Token", clientToken)
                .header("x-tr-signature", signatureHeader)
                .header("X-Play-Mode", "2")
                .header("X-Client-Info", clientInfoJson)
                .header("X-Client-Status", "1")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val rawToken = response.header("x-user") ?: response.header("X-User")
            if (!rawToken.isNullOrEmpty()) {
                val extractedToken = if (rawToken.startsWith("{")) {
                    val tokenKey = "\"token\":\""
                    val startIdx = rawToken.indexOf(tokenKey)
                    if (startIdx != -1) {
                        val start = startIdx + tokenKey.length
                        val end = rawToken.indexOf("\"", start)
                        if (end != -1) rawToken.substring(start, end) else rawToken
                    } else rawToken
                } else rawToken
                guestToken = extractedToken
                Log.i("LocalGengaServer", "Successfully bootstrapped dynamic guest token: $guestToken")
            }
            response.close()
        } catch (e: Exception) {
            Log.e("LocalGengaServer", "Guest bootstrap exception", e)
        }
    }

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("genga_prefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it) }
    }

    private fun md5Bytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        return digest.joinToString("") { String.format("%02x", it) }
    }

    private fun hmacMd5(keyBytes: ByteArray, dataBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        val secretKey = SecretKeySpec(keyBytes, "HmacMD5")
        mac.init(secretKey)
        return mac.doFinal(dataBytes)
    }

    private fun toJsonString(obj: Any?): String {
        return when (obj) {
            is Map<*, *> -> {
                val json = org.json.JSONObject()
                obj.forEach { (key, value) ->
                    if (key != null) {
                        json.put(key.toString(), toJsonElement(value))
                    }
                }
                json.toString()
            }
            is List<*> -> {
                val array = org.json.JSONArray()
                obj.forEach { item ->
                    array.put(toJsonElement(item))
                }
                array.toString()
            }
            else -> org.json.JSONObject.wrap(obj)?.toString() ?: "null"
        }
    }

    private fun toJsonElement(obj: Any?): Any {
        return when (obj) {
            is Map<*, *> -> {
                val json = org.json.JSONObject()
                obj.forEach { (key, value) ->
                    if (key != null) {
                        json.put(key.toString(), toJsonElement(value))
                    }
                }
                json
            }
            is List<*> -> {
                val array = org.json.JSONArray()
                obj.forEach { item ->
                    array.put(toJsonElement(item))
                }
                array
            }
            null -> org.json.JSONObject.NULL
            else -> obj
        }
    }

    private fun parseJsonToMap(jsonStr: String): Map<String, Any?> {
        if (jsonStr.trim().isEmpty()) return emptyMap()
        return try {
            val json = org.json.JSONObject(jsonStr)
            jsonToMap(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun jsonToMap(json: org.json.JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            map[key] = when (value) {
                is org.json.JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> jsonToList(value)
                org.json.JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    private fun jsonToList(array: org.json.JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until array.length()) {
            val value = array.get(i)
            list.add(when (value) {
                is org.json.JSONObject -> jsonToMap(value)
                is org.json.JSONArray -> jsonToList(value)
                org.json.JSONObject.NULL -> null
                else -> value
            })
        }
        return list
    }

    private fun sendResponse(socket: Socket, statusCode: Int, body: String) {
        val output: OutputStream = socket.getOutputStream()
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val responseHeaders = "HTTP/1.1 $statusCode OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
        output.write(responseHeaders.toByteArray(StandardCharsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }
}
