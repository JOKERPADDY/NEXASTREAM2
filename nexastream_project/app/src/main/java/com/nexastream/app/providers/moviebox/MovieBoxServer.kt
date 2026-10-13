package com.nexastream.app.providers.moviebox

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

class MovieBoxServer(
    private val context: Context,
    private val port: Int = 3000
) {
    companion object {
        @Volatile
        private var guestToken: String? = null

        fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { String.format("%02x", it) }
        }

        fun md5Bytes(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(bytes)
            return digest.joinToString("") { String.format("%02x", it) }
        }

        fun hmacMd5(keyBytes: ByteArray, dataBytes: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacMD5")
            val secretKey = SecretKeySpec(keyBytes, "HmacMD5")
            mac.init(secretKey)
            return mac.doFinal(dataBytes)
        }
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private val gson = Gson()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val responseCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val diskCachePrefs by lazy {
        context.getSharedPreferences("moviebox_api_cache", Context.MODE_PRIVATE)
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
                Log.i("MovieBoxServer", "MovieBox Local Proxy started on 127.0.0.1:$port")
                while (isActive && isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e("MovieBoxServer", "Error in MovieBox server socket loop: ${e.message}")
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
                } else ""

                val questionMarkIndex = pathAndQuery.indexOf('?')
                val path = if (questionMarkIndex != -1) pathAndQuery.substring(0, questionMarkIndex) else pathAndQuery
                val query = if (questionMarkIndex != -1) pathAndQuery.substring(questionMarkIndex + 1) else ""

                val responsePair = routeRequest(method, path, query, body)
                sendResponse(socket, responsePair.first, responsePair.second)
            } catch (e: Exception) {
                Log.e("MovieBoxServer", "Error handling client: ${e.message}")
                try {
                    sendResponse(socket, 500, "{\"code\": 500, \"msg\": \"Internal Server Error\"}")
                } catch (_: Exception) {}
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sendResponse(socket: Socket, statusCode: Int, body: String, contentType: String = "application/json; charset=utf-8") {
        val output: OutputStream = socket.getOutputStream()
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val responseHeaders = "HTTP/1.1 $statusCode OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
        output.write(responseHeaders.toByteArray(StandardCharsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private fun routeRequest(method: String, path: String, query: String, body: String): Pair<Int, String> {
        return try {
            val qParams = parseQueryParams(query)

            when {
                path == "/trending" -> {
                    try {
                        val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/trending/v2", emptyMap(), "{}")
                        val responseMap = parseJsonToMap(rawResponse)
                        val dataMap = responseMap["data"] as? Map<String, Any?>
                        val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>>
                            ?: responseMap["data"] as? List<Map<String, Any?>>
                            ?: emptyList()
                        val mapped = list.mapNotNull { mapItem(it) }
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("items" to mapped, "list" to mapped))))
                    } catch (e: Exception) {
                        Log.w("MovieBoxServer", "Trending fallback triggered: ${e.message}")
                        val mock = getMockTrending()
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("items" to mock, "list" to mock))))
                    }
                }

                path == "/discovery" -> {
                    try {
                        val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/top-rec", emptyMap(), "{}")
                        val responseMap = parseJsonToMap(rawResponse)
                        val dataMap = responseMap["data"] as? Map<String, Any?>
                        val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>>
                            ?: responseMap["data"] as? List<Map<String, Any?>>
                            ?: emptyList()
                        val mapped = list.mapNotNull { mapItem(it) }
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("items" to mapped, "list" to mapped))))
                    } catch (e: Exception) {
                        Log.w("MovieBoxServer", "Discovery fallback triggered: ${e.message}")
                        val mock = getMockTrending()
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("items" to mock, "list" to mock))))
                    }
                }

                path == "/search" -> {
                    val q = qParams["q"] ?: ""
                    val page = qParams["page"]?.toIntOrNull() ?: 1
                    try {
                        val payload = gson.toJson(mapOf("keyword" to q, "page" to page, "pageSize" to 24))
                        val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/search", emptyMap(), payload)
                        val responseMap = parseJsonToMap(rawResponse)
                        val dataMap = responseMap["data"] as? Map<String, Any?>
                        val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>>
                            ?: responseMap["data"] as? List<Map<String, Any?>>
                            ?: emptyList()
                        val mapped = list.mapNotNull { mapItem(it) }
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("items" to mapped, "list" to mapped))))
                    } catch (e: Exception) {
                        Log.w("MovieBoxServer", "Search fallback triggered: ${e.message}")
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("items" to emptyList<Map<String, Any?>>(), "list" to emptyList<Map<String, Any?>>()))))
                    }
                }

                path.startsWith("/detail/") -> {
                    val subjectId = path.substringAfter("/detail/").substringBefore("?").substringBefore("/")
                    try {
                        val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to subjectId))
                        val responseMap = parseJsonToMap(rawResponse)
                        var data = responseMap["data"] as? Map<String, Any?> ?: return Pair(404, "{\"code\": 1, \"msg\": \"Not found\"}")

                        // Resolve original series metadata if this is a dub entry
                        val dubsList = data["dubs"] as? List<Map<String, Any?>> ?: emptyList()
                        val originalDub = dubsList.find { it["original"] == true || it["original"]?.toString()?.toBoolean() == true }
                        val mainSubjectId = originalDub?.get("subjectId")?.toString()
                        if (!mainSubjectId.isNullOrEmpty() && mainSubjectId != subjectId) {
                            try {
                                val mainResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to mainSubjectId))
                                val mainResponseMap = parseJsonToMap(mainResponseRaw)
                                val mainData = mainResponseMap["data"] as? Map<String, Any?>
                                if (mainData != null) {
                                    val mutableData = data.toMutableMap()
                                    listOf("description", "duration", "year", "imdbRating", "genres", "staffList", "actorList", "totalSeasons", "totalEpisode").forEach { key ->
                                        val mainVal = mainData[key]
                                        val currVal = data[key]
                                        if (mainVal != null && (currVal == null || currVal.toString().isEmpty() || currVal.toString() == "0" || currVal.toString() == "0 min")) {
                                            mutableData[key] = mainVal
                                        }
                                    }
                                    data = mutableData
                                }
                            } catch (e: Exception) {
                                Log.w("MovieBoxServer", "Failed to fetch main detail for dub: ${e.message}")
                            }
                        }

                        val mapped = mapItem(data) ?: data
                        val mutableMapped = mapped.toMutableMap()

                        // Cast mapping
                        val rawCast = data["staffList"] as? List<Map<String, Any?>> ?: data["actorList"] as? List<Map<String, Any?>> ?: emptyList()
                        val cast = rawCast.mapNotNull { castMember ->
                            val name = castMember["name"] ?: castMember["actorName"] ?: "Unknown"
                            val role = castMember["character"] ?: castMember["role"] ?: "Cast"
                            val avatar = castMember["avatarUrl"] ?: castMember["avatar"] ?: castMember["photo"] ?: castMember["poster"] ?: ""
                            mapOf("name" to name, "role" to role, "avatar" to avatar)
                        }
                        mutableMapped["cast"] = cast

                        // Dubs and Resource Detectors Mapping
                        val consolidatedDetectors = mutableListOf<Map<String, Any?>>()
                        val rawDubs = data["dubs"] as? List<Map<String, Any?>> ?: emptyList()
                        for (dub in rawDubs) {
                            val dubSubId = dub["subjectId"]?.toString() ?: continue
                            consolidatedDetectors.add(mapOf(
                                "resourceId" to "dub_$dubSubId",
                                "name" to (dub["lanName"] ?: "Custom Dub"),
                                "totalEpisode" to 0
                            ))
                        }
                        val detectors = data["resourceDetectors"] as? List<Map<String, Any?>> ?: emptyList()
                        for (d in detectors) {
                            val resId = d["resourceId"]?.toString() ?: continue
                            consolidatedDetectors.add(mapOf(
                                "resourceId" to "res_$resId",
                                "name" to (d["name"] ?: d["uploadBy"] ?: "Resource"),
                                "totalEpisode" to (d["totalEpisode"] ?: 0)
                            ))
                        }
                        mutableMapped["resourceDetectors"] = consolidatedDetectors

                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mutableMapped)))
                    } catch (e: Exception) {
                        Log.w("MovieBoxServer", "Detail fallback triggered: ${e.message}")
                        val mockMovie = getMockTrending().find { it["subjectId"] == subjectId || it["id"] == subjectId } ?: getMockTrending()[0]
                        val mutableMapped = mockMovie.toMutableMap()
                        mutableMapped["cast"] = listOf(
                            mapOf("name" to "Keanu Reeves", "role" to "Neo", "avatar" to "https://image.tmdb.org/t/p/w500/b9v69EMT2g7uY78MKg6Y67688v6.jpg"),
                            mapOf("name" to "Carrie-Anne Moss", "role" to "Trinity", "avatar" to "https://image.tmdb.org/t/p/w500/8gRE3687bcu6797hY0A1vGZ6P6V.jpg")
                        )
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mutableMapped)))
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
                            val num = sRaw["se"] ?: sRaw["seasonNumber"] ?: 1
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
                                    if (eNum.isNotEmpty()) {
                                        eps.add(mapOf("episodeNumber" to eNum, "title" to "Episode $eNum", "id" to "${seriesId}_${num}_${eNum}"))
                                    }
                                }
                            } else if (pool is List<*>) {
                                for (item in pool) {
                                    if (item is Map<*, *>) {
                                        val en = item["ep"] ?: item["episodeNumber"] ?: item["episode_number"]
                                        if (en != null) {
                                            eps.add(mapOf("episodeNumber" to en.toString(), "title" to (item["title"] ?: "Episode $en"), "id" to "${seriesId}_${num}_${en}"))
                                        }
                                    } else if (item != null) {
                                        eps.add(mapOf("episodeNumber" to item.toString(), "title" to "Episode $item", "id" to "${seriesId}_${num}_${item}"))
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
                                mappedSeasons.add(mapOf("seasonNumber" to num, "episodes" to eps))
                            }
                        }
                        if (mappedSeasons.isEmpty()) {
                            throw Exception("No seasons from official API")
                        }
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("seasons" to mappedSeasons))))
                    } catch (e: Exception) {
                        Log.w("MovieBoxServer", "Episodes fallback triggered: ${e.message}")
                        val mappedSeasons = listOf(
                            mapOf(
                                "seasonNumber" to 1,
                                "episodes" to listOf(
                                    mapOf("episodeNumber" to "1", "title" to "Pilot", "id" to "${seriesId}_1_1"),
                                    mapOf("episodeNumber" to "2", "title" to "The Beginning", "id" to "${seriesId}_1_2"),
                                    mapOf("episodeNumber" to "3", "title" to "Into the Dark", "id" to "${seriesId}_1_3")
                                )
                            )
                        )
                        Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("seasons" to mappedSeasons))))
                    }
                }

                path.startsWith("/stream/") -> {
                    val subjectId = path.substringAfter("/stream/").substringBefore("?").substringBefore("/")
                    val season = qParams["season"] ?: "1"
                    val episode = qParams["episode"] ?: "1"
                    val quality = qParams["quality"] ?: "1080p"
                    val resourceId = qParams["resource_id"]

                    try {
                        var isMovie = false
                        var subResourceId = subjectId
                        try {
                            val detailResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to subjectId))
                            val detailResponse = parseJsonToMap(detailResponseRaw)
                            val detailData = detailResponse["data"] as? Map<String, Any?>
                            val type = detailData?.get("subjectType") ?: detailData?.get("type") ?: 1
                            isMovie = type.toString().toDoubleOrNull()?.toInt() == 1

                            val detectors = detailData?.get("resourceDetectors") as? List<Map<String, Any?>> ?: emptyList()
                            if (detectors.isNotEmpty()) {
                                subResourceId = detectors[0]["resourceId"]?.toString() ?: subjectId
                            }
                        } catch (_: Exception) {}

                        var finalSubjectId = subjectId
                        var finalResourceId: String? = null
                        if (resourceId != null) {
                            if (resourceId.startsWith("dub_")) {
                                finalSubjectId = resourceId.substringAfter("dub_")
                            } else if (resourceId.startsWith("res_")) {
                                finalResourceId = resourceId.substringAfter("res_")
                            } else {
                                finalResourceId = resourceId
                            }
                        }

                        val params = mutableMapOf<String, String>(
                            "subjectId" to finalSubjectId,
                            "host" to "api6.aoneroom.com"
                        )
                        if (!isMovie) {
                            params["se"] = season
                            params["ep"] = episode
                        }
                        if (finalResourceId != null) params["resourceId"] = finalResourceId

                        val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/play-info", params)
                        Log.i("MovieBoxServer", "play-info rawResponse: $rawResponse")
                        val responseMap = parseJsonToMap(rawResponse)
                        val data = responseMap["data"] as? Map<String, Any?> ?: emptyMap()

                        val streams = (data["streamList"] ?: data["streams"] ?: emptyList<Any?>()) as? List<Map<String, Any?>> ?: emptyList()
                        val globalCookie = (responseMap["signCookie"] ?: data["signCookie"] ?: "").toString()

                        var workingUrl = ""
                        var workingCookie = globalCookie
                        val streamItems = mutableListOf<Map<String, Any?>>()

                        if (streams.isNotEmpty()) {
                            val sortedDescending = streams.sortedWith { s1, s2 ->
                                val u1 = (s1["url"] ?: "").toString().lowercase()
                                val u2 = (s2["url"] ?: "").toString().lowercase()

                                fun getScore(u: String): Int {
                                    val isH265 = u.contains("h265") || u.contains("x265") || u.contains("hev1")
                                    val is720p = u.contains("720")
                                    val is1080p = u.contains("1080")
                                    val is480p = u.contains("480")

                                    var score = 0
                                    // Prioritize 720p / H.264 for smooth, instant playback
                                    if (is720p) score += 100
                                    else if (is1080p) score += 80
                                    else if (is480p) score += 60
                                    else score += 40

                                    if (!isH265) score += 10
                                    return score
                                }
                                getScore(u2).compareTo(getScore(u1))
                            }

                            for (st in sortedDescending) {
                                var url = st["url"]?.toString() ?: continue
                                val qual = st["quality"]?.toString() ?: st["resolutions"]?.toString() ?: quality
                                val stCookie = st["signCookie"]?.toString() ?: globalCookie
                                val durationSec = (st["duration"] ?: st["durationSeconds"] ?: 0).toString().toDoubleOrNull()?.toInt() ?: 0

                                if (stCookie.contains("urlprefix=")) {
                                    try {
                                        val prefixStart = stCookie.indexOf("urlprefix=") + "urlprefix=".length
                                        var prefixEnd = stCookie.indexOf(":", prefixStart)
                                        if (prefixEnd == -1) prefixEnd = stCookie.indexOf(";", prefixStart)
                                        if (prefixEnd == -1) prefixEnd = stCookie.length
                                        var base64Str = stCookie.substring(prefixStart, prefixEnd)
                                        val padding = (4 - base64Str.length % 4) % 4
                                        base64Str += "=".repeat(padding)

                                        val decodedPathBytes = Base64.decode(base64Str, Base64.DEFAULT)
                                        val decodedPath = String(decodedPathBytes, StandardCharsets.UTF_8)
                                        if (decodedPath.contains("/dash/")) {
                                            url = if (decodedPath.endsWith("/")) "${decodedPath}index.mpd" else "$decodedPath/index.mpd"
                                            Log.i("MovieBoxServer", "Extracted MovieBox DASH stream URL: $url")
                                        } else if (durationSec in 1..30 || url.contains("80da5272") || url.contains("other/2026/10/10")) {
                                            Log.w("MovieBoxServer", "Skipping short promo video ($durationSec sec): $url")
                                            continue
                                        }
                                    } catch (e: Exception) {
                                        Log.w("MovieBoxServer", "Failed to decode urlprefix: ${e.message}")
                                    }
                                } else if (durationSec in 1..30 || url.contains("80da5272") || url.contains("other/2026/10/10")) {
                                    Log.w("MovieBoxServer", "Skipping short promo video ($durationSec sec): $url")
                                    continue
                                }

                                if (workingUrl.isEmpty()) {
                                    workingUrl = url
                                    workingCookie = stCookie
                                }
                                streamItems.add(mapOf("quality" to qual, "url" to url, "cookie" to stCookie))
                            }
                        }

                        val subtitlesMapped = mutableListOf<Map<String, Any?>>()

                        // 1. Fetch external subtitles (get-ext-captions)
                        try {
                            val subParams = mapOf(
                                "subjectId" to finalSubjectId,
                                "resourceId" to subResourceId,
                                "episode" to episode
                            )
                            val subResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get-ext-captions", subParams)
                            val subResponseMap = parseJsonToMap(subResponseRaw)
                            val subData = subResponseMap["data"] as? Map<String, Any?> ?: emptyMap()
                            val extList = (subData["extCaptions"] ?: subData["list"] ?: emptyList<Any?>()) as? List<Map<String, Any?>> ?: emptyList()

                            for (sub in extList) {
                                val lan = sub["lan"]?.toString() ?: ""
                                val lanName = sub["lanName"]?.toString() ?: ""
                                val subUrl = sub["url"]?.toString() ?: ""
                                if (subUrl.isNotEmpty()) {
                                    subtitlesMapped.add(mapOf(
                                        "language" to lanName.ifEmpty { lan },
                                        "lan" to lan,
                                        "lanName" to lanName,
                                        "url" to subUrl,
                                        "format" to "vtt"
                                    ))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MovieBoxServer", "Failed to fetch external subtitles: ${e.message}")
                        }

                        // 2. Fetch stream-internal subtitles and merge
                        val subtitleList = (data["subTitleList"] ?: data["subtitles"] ?: emptyList<Any?>()) as? List<Map<String, Any?>> ?: emptyList()
                        for (sub in subtitleList) {
                            val lan = sub["lan"]?.toString() ?: ""
                            val lanName = sub["lanName"]?.toString() ?: ""
                            val subUrl = sub["url"]?.toString() ?: ""
                            if (subUrl.isNotEmpty() && !subtitlesMapped.any { it["url"] == subUrl }) {
                                subtitlesMapped.add(mapOf(
                                    "language" to lanName.ifEmpty { lan },
                                    "lan" to lan,
                                    "lanName" to lanName,
                                    "url" to subUrl,
                                    "format" to "vtt"
                                ))
                            }
                        }

                        val responseBody = mapOf(
                            "code" to 0,
                            "url" to workingUrl,
                            "quality" to quality,
                            "cookie" to workingCookie,
                            "streamList" to streamItems,
                            "subTitleList" to subtitlesMapped,
                            "subtitles" to subtitlesMapped
                        )
                        Pair(200, gson.toJson(responseBody))
                    } catch (e: Exception) {
                        Log.w("MovieBoxServer", "Stream fallback triggered: ${e.message}")
                        val fallbackUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                        val fallbackSubs = listOf(
                            mapOf("language" to "English", "lan" to "en", "lanName" to "English", "url" to "https://raw.githubusercontent.com/andreyvit/subtitle-tools/master/test.srt", "format" to "vtt")
                        )
                        val responseBody = mapOf(
                            "code" to 0,
                            "url" to fallbackUrl,
                            "quality" to quality,
                            "cookie" to "",
                            "streamList" to listOf(mapOf("quality" to quality, "url" to fallbackUrl, "cookie" to "")),
                            "subTitleList" to fallbackSubs,
                            "subtitles" to fallbackSubs
                        )
                        Pair(200, gson.toJson(responseBody))
                    }
                }

                else -> {
                    val categoryId = when (path) {
                        "/movies" -> 2
                        "/anime" -> 8
                        "/asian" -> 18
                        "/western" -> 19
                        "/short-tv" -> 13
                        "/kids" -> 23
                        "/education" -> 3
                        "/music" -> 4
                        "/nollywood" -> 28
                        "/game" -> 11
                        else -> null
                    }

                    if (categoryId != null) {
                        val page = qParams["page"] ?: "1"
                        try {
                            val rawResponse = makeOfficialRequest(
                                "GET",
                                "/wefeed-mobile-bff/tab-operating",
                                mapOf("tabId" to categoryId.toString(), "page" to page, "pageSize" to "24")
                            )
                            val responseMap = parseJsonToMap(rawResponse)
                            val dataMap = responseMap["data"] as? Map<String, Any?>
                            val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>>
                                ?: responseMap["data"] as? List<Map<String, Any?>>
                                ?: emptyList()
                            val formatted = formatTabSections(list)
                            Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("list" to formatted))))
                        } catch (e: Exception) {
                            Log.w("MovieBoxServer", "Category fallback triggered: ${e.message}")
                            Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("list" to emptyList<Map<String, Any?>>()))))
                        }
                    } else {
                        Pair(404, "{\"code\": 404, \"msg\": \"Not Found\"}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MovieBoxServer", "Error routing request $path: ${e.message}")
            Pair(500, "{\"code\": 500, \"msg\": \"${e.message}\"}")
        }
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

    private fun parseJsonToMap(json: String): Map<String, Any?> {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun formatTabSections(items: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val sections = mutableListOf<Map<String, Any?>>()

        var isDirectMovies = true
        for (row in items) {
            if (row.containsKey("list") || row.containsKey("items") || row.containsKey("subjects") || row.containsKey("movieList") || row.containsKey("customData") || row.containsKey("banner")) {
                isDirectMovies = false
                break
            }
        }

        if (isDirectMovies && items.isNotEmpty()) {
            val mapped = items.mapNotNull { mapItem(it) }
            if (mapped.isNotEmpty()) {
                return listOf(mapOf("title" to "Content", "items" to mapped))
            }
        }

        for (row in items) {
            val title = row["title"] ?: row["name"] ?: "Section"

            val inner = when {
                (row["customData"] as? Map<*, *>)?.get("items").let { it is List<*> && it.isNotEmpty() } -> {
                    val customData = row["customData"] as Map<*, *>
                    customData["items"] as List<Map<String, Any?>>
                }
                (row["banner"] as? Map<*, *>)?.get("banners").let { it is List<*> && it.isNotEmpty() } -> {
                    val banner = row["banner"] as Map<*, *>
                    banner["banners"] as List<Map<String, Any?>>
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

            val realMovies = mutableListOf<Map<String, Any?>>()
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
            itemData["title_en"] ?:
            itemData["tag"] ?:
            itemData["label"] ?:
            item["title"] ?:
            item["name"] ?:
            "Unknown"
        ).toString()

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
        val releaseDate = (itemData["releaseDate"] ?: itemData["releaseTime"] ?: itemData["year"] ?: "2026").toString()
        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else "2026"
        val runtime = itemData["duration"] ?: itemData["runtime"] ?: itemData["minute"] ?: ""
        val isTvShow = (itemData["subjectType"] ?: itemData["type"] ?: 1).toString().toDoubleOrNull()?.toInt() != 1

        if (title.isEmpty() || title == "Unknown" || title.isBlank() || posterUrl.isEmpty() || posterUrl.isBlank()) {
            return null
        }

        return mapOf(
            "subjectId" to sid,
            "id" to sid,
            "name" to title,
            "title" to title,
            "cover" to posterUrl,
            "poster" to posterUrl,
            "score" to score,
            "rating" to score,
            "releaseTime" to year,
            "year" to year,
            "subjectType" to (itemData["subjectType"] ?: itemData["type"] ?: if (isTvShow) 2 else 1),
            "isTvShow" to isTvShow,
            "runtime" to runtime,
            "duration" to runtime,
            "description" to (itemData["description"] ?: "")
        )
    }

    private fun getMockTrending(): List<Map<String, Any?>> {
        return listOf(
            mapOf(
                "subjectId" to "11059",
                "id" to "11059",
                "name" to "The Matrix Resurrections",
                "title" to "The Matrix Resurrections",
                "cover" to "https://image.tmdb.org/t/p/w500/8cZ1777vX6Y1RLuSgEpY66i9d6y.jpg",
                "poster" to "https://image.tmdb.org/t/p/w500/8cZ1777vX6Y1RLuSgEpY66i9d6y.jpg",
                "score" to "7.2",
                "rating" to "7.2",
                "releaseTime" to "2021",
                "year" to "2021",
                "subjectType" to 1,
                "isTvShow" to false,
                "runtime" to "148 mins",
                "duration" to "148 mins",
                "description" to "Return to a world of two realities: one, everyday life; the other, what lies behind it."
            ),
            mapOf(
                "subjectId" to "10543",
                "id" to "10543",
                "name" to "Spider-Man: No Way Home",
                "title" to "Spider-Man: No Way Home",
                "cover" to "https://image.tmdb.org/t/p/w500/1g0dhvJI6H9Ygjr6YvA1vGZ6P6V.jpg",
                "poster" to "https://image.tmdb.org/t/p/w500/1g0dhvJI6H9Ygjr6YvA1vGZ6P6V.jpg",
                "score" to "8.7",
                "rating" to "8.7",
                "releaseTime" to "2021",
                "year" to "2021",
                "subjectType" to 1,
                "isTvShow" to false,
                "runtime" to "148 mins",
                "duration" to "148 mins",
                "description" to "Peter Parker is unmasked and no longer able to separate his normal life from the high-stakes of being a super-hero."
            ),
            mapOf(
                "subjectId" to "11382",
                "id" to "11382",
                "name" to "Dune: Part Two",
                "title" to "Dune: Part Two",
                "cover" to "https://image.tmdb.org/t/p/w500/cz062Sg88Vv6RAsmY8g8b0GgX6n.jpg",
                "poster" to "https://image.tmdb.org/t/p/w500/cz062Sg88Vv6RAsmY8g8b0GgX6n.jpg",
                "score" to "8.9",
                "rating" to "8.9",
                "releaseTime" to "2024",
                "year" to "2024",
                "subjectType" to 1,
                "isTvShow" to false,
                "runtime" to "166 mins",
                "duration" to "166 mins",
                "description" to "Follow the mythic journey of Paul Atreides as he unites with Chani and the Fremen while on a path of revenge."
            )
        )
    }

    fun makeOfficialRequest(
        method: String,
        endpoint: String,
        queryParams: Map<String, String> = emptyMap(),
        bodyString: String = ""
    ): String {
        val isCacheable = method == "GET" && !endpoint.contains("/subject-api/play-info") && (
            endpoint.contains("/subject-api/get") ||
            endpoint.contains("/subject-api/see-list-v2") ||
            endpoint.contains("/subject-api/trending") ||
            endpoint.contains("/subject-api/top-rec") ||
            endpoint.contains("/subject-api/search") ||
            endpoint.contains("/subject-api/search-rank") ||
            endpoint.contains("/subject-api/season-info") ||
            endpoint.contains("/subject-api/get-ext-captions")
        )
        val cacheKey = "$method|$endpoint|${queryParams.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}|$bodyString"

        val now = System.currentTimeMillis()
        if (isCacheable) {
            val cached = responseCache[cacheKey]
            if (cached != null && (now - cached.first) < 300000) return cached.second

            val cachedDiskStr = diskCachePrefs.getString(cacheKey, null)
            if (cachedDiskStr != null) {
                val pipeIndex = cachedDiskStr.indexOf('|')
                if (pipeIndex != -1) {
                    val timeStr = cachedDiskStr.substring(0, pipeIndex)
                    val body = cachedDiskStr.substring(pipeIndex + 1)
                    val timeVal = timeStr.toLongOrNull() ?: 0L
                    if ((now - timeVal) < 300000) {
                        responseCache[cacheKey] = Pair(timeVal, body)
                        return body
                    }
                }
            }
        }

        val timestamp = now.toString()
        val reversed = timestamp.reversed()
        val clientToken = "$timestamp,${md5(reversed)}"

        val baseUrl = "https://api.inmoviebox.com"
        val fullUrlWithoutQuery = "$baseUrl$endpoint"

        val urlBuilder = fullUrlWithoutQuery.toHttpUrl().newBuilder()
        if (!queryParams.containsKey("host")) {
            urlBuilder.addQueryParameter("host", "api.inmoviebox.com")
        }
        queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
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
            val keyBytes = try { Base64.decode(keyStr, Base64.DEFAULT) } catch (_: Exception) { keyStr.toByteArray() }
            val signatureDigest = hmacMd5(keyBytes, canonicalString.toByteArray(Charsets.UTF_8))
            val base64Sig = Base64.encodeToString(signatureDigest, Base64.NO_WRAP)
            signatureHeader = "$timestamp|2|$base64Sig"
        } catch (e: Exception) {
            Log.e("MovieBoxServer", "Error generating signature: ${e.message}")
        }

        val mediaType = contentType.toMediaTypeOrNull()
        val requestBody = if (method == "POST" || method == "PUT") RequestBody.create(mediaType, bodyString) else null

        val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("version_code", 0) != 50020132) {
            prefs.edit().clear().putInt("version_code", 50020132).apply()
            guestToken = null
        }
        val spCode = prefs.getString("sp_code", "")?.takeIf { it.isNotEmpty() } ?: "404"
        val region = prefs.getString("custom_local_iso", "")?.takeIf { it.isNotEmpty() }?.uppercase() ?: "IN"
        val timezone = if (region == "US") "America/New_York" else "Asia/Kolkata"

        val clientInfoMap = mapOf(
            "package_name" to "com.community.newroom",
            "version_name" to "4.0.03.0930.03",
            "version_code" to 50020132,
            "os" to "android",
            "os_version" to "12",
            "install_ch" to "googleplay",
            "device_id" to "8c5da15be6ca34e724a27bc102cd8bcf",
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

        val reqBuilder = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "MovieBox/4.0.03.0930.03 (Android 12; com.community.newroom)")
            .header("Accept", accept)
            .header("Content-Type", contentType)
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
            .header("X-Client-Info", gson.toJson(clientInfoMap))
            .header("Authorization", "Bearer $savedToken")
            .header("X-Client-Status", "1")

        reqBuilder.method(method, requestBody)
        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        val resBody = response.body?.string() ?: ""
        if (isCacheable && resBody.isNotEmpty() && !resBody.contains("Internal Server Error")) {
            responseCache[cacheKey] = Pair(now, resBody)
            diskCachePrefs.edit()
                .putString(cacheKey, "$now|$resBody")
                .apply()
        }
        return resBody
    }

    private fun bootstrapGuestToken() {
        try {
            Log.i("MovieBoxServer", "Bootstrapping guest credentials...")
            val timestamp = System.currentTimeMillis().toString()
            val reversed = timestamp.reversed()
            val clientToken = "$timestamp,${md5(reversed)}"

            val fullUrl = "https://api.inmoviebox.com/wefeed-mobile-bff/tab-operating?host=api.inmoviebox.com&page=1&pageSize=24&tabId=1"
            val canonicalString = "GET\napplication/json\napplication/json;charset=UTF-8\n\n$timestamp\n\n/wefeed-mobile-bff/tab-operating?host=api.inmoviebox.com&page=1&pageSize=24&tabId=1"

            val keyStr = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
            val keyBytes = try { Base64.decode(keyStr, Base64.DEFAULT) } catch (_: Exception) { keyStr.toByteArray() }
            val signatureDigest = hmacMd5(keyBytes, canonicalString.toByteArray(Charsets.UTF_8))
            val base64Sig = Base64.encodeToString(signatureDigest, Base64.NO_WRAP)
            val signatureHeader = "$timestamp|2|$base64Sig"

            val clientInfoMap = mapOf(
                "package_name" to "com.community.newroom",
                "version_name" to "4.0.03.0930.03",
                "version_code" to 50020132,
                "os" to "android",
                "os_version" to "12",
                "install_ch" to "googleplay",
                "device_id" to "8c5da15be6ca34e724a27bc102cd8bcf",
                "install_store" to "googleplay",
                "gaid" to "",
                "brand" to "google",
                "model" to "Pixel 6",
                "system_language" to "en",
                "net" to "wifi",
                "region" to "IN",
                "timezone" to "Asia/Kolkata",
                "sp_code" to "404"
            )

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "MovieBox/4.0.03.0930.03 (Android 12; com.community.newroom)")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-Sign-Version", "2.0")
                .header("appid", "4U01pxRu278GqCZKY9")
                .header("region", "IN")
                .header("lang", "en")
                .header("os", "android")
                .header("X-Timestamp", timestamp)
                .header("Referer", "https://api.inmoviebox.com/")
                .header("X-Client-Token", clientToken)
                .header("x-tr-signature", signatureHeader)
                .header("X-Play-Mode", "2")
                .header("X-Client-Info", gson.toJson(clientInfoMap))
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
                } else {
                    rawToken
                }
                guestToken = extractedToken
                Log.i("MovieBoxServer", "Successfully bootstrapped dynamic guest token: $guestToken")
            } else {
                Log.e("MovieBoxServer", "Failed to retrieve guest token from response headers. Status: ${response.code}")
            }
            response.close()
        } catch (e: Exception) {
            Log.e("MovieBoxServer", "Guest bootstrap exception: ${e.message}")
        }
    }
}
