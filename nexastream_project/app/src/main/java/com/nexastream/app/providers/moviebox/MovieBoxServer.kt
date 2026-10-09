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
            val digest = md.digest(input.toByteArray())
            return digest.joinToString("") { String.format("%02x", it) }
        }

        fun md5Bytes(input: ByteArray): String {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(input)
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
        scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(port))
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
                    val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/trending/v2", emptyMap(), "{}")
                    val responseMap = parseJsonToMap(rawResponse)
                    val dataMap = responseMap["data"] as? Map<String, Any?>
                    val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>>
                        ?: responseMap["data"] as? List<Map<String, Any?>>
                        ?: emptyList()
                    val mapped = list.mapNotNull { mapItem(it) }
                    Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapped)))
                }

                path == "/search" -> {
                    val q = qParams["q"] ?: ""
                    val page = qParams["page"]?.toIntOrNull() ?: 1
                    val payload = gson.toJson(mapOf("keyword" to q, "page" to page, "pageSize" to 20))
                    val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/search", emptyMap(), payload)
                    val responseMap = parseJsonToMap(rawResponse)
                    val dataMap = responseMap["data"] as? Map<String, Any?>
                    val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>>
                        ?: responseMap["data"] as? List<Map<String, Any?>>
                        ?: emptyList()
                    val mapped = list.mapNotNull { mapItem(it) }
                    Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapOf("items" to mapped))))
                }

                path.startsWith("/detail/") -> {
                    val subjectId = path.substringAfter("/detail/").substringBefore("?").substringBefore("/")
                    val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to subjectId))
                    val responseMap = parseJsonToMap(rawResponse)
                    val data = responseMap["data"] as? Map<String, Any?> ?: return Pair(404, "{\"code\": 1, \"msg\": \"Not found\"}")
                    val mapped = mapItem(data) ?: data
                    Pair(200, gson.toJson(mapOf("code" to 0, "data" to mapped)))
                }

                path.startsWith("/stream/") -> {
                    val subjectId = path.substringAfter("/stream/").substringBefore("?").substringBefore("/")
                    val season = qParams["season"] ?: "1"
                    val episode = qParams["episode"] ?: "1"
                    val quality = qParams["quality"] ?: "1080p"
                    val resourceId = qParams["resource_id"]

                    var isMovie = false
                    var subResourceId = subjectId
                    try {
                        val detailResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to subjectId))
                        val detailResponse = parseJsonToMap(detailResponseRaw)
                        val detailData = detailResponse["data"] as? Map<String, Any?>
                        val type = detailData?.get("subjectType") ?: detailData?.get("type") ?: 1
                        isMovie = type.toString().toDoubleOrNull()?.toInt() == 1

                        // Check if dubs contains an original or English audio version subjectId
                        val dubsList = detailData?.get("dubs") as? List<Map<String, Any?>> ?: emptyList()
                        val originalDub = dubsList.find { dub ->
                            dub["original"] == true || dub["original"]?.toString()?.toBoolean() == true
                        } ?: dubsList.find { dub ->
                            val lanName = dub["lanName"]?.toString() ?: dub["name"]?.toString() ?: ""
                            lanName.contains("English", ignoreCase = true) || lanName.equals("en", ignoreCase = true)
                        }
                        if (originalDub != null) {
                            val origSubId = originalDub["subjectId"]?.toString()
                            if (!origSubId.isNullOrEmpty()) {
                                subResourceId = origSubId
                            }
                        }

                        val detectors = detailData?.get("resourceDetectors") as? List<Map<String, Any?>> ?: emptyList()
                        if (detectors.isNotEmpty()) {
                            val englishDetector = detectors.find { det ->
                                val name = (det["name"] ?: det["uploadBy"] ?: "").toString().lowercase()
                                name.contains("english") || name.contains("en") || name.contains("original")
                            } ?: detectors.find { det ->
                                val name = (det["name"] ?: det["uploadBy"] ?: "").toString().lowercase()
                                !name.contains("hindi") && !name.contains("hi") && !name.contains("indian")
                            } ?: detectors[0]
                            if (originalDub == null) {
                                subResourceId = englishDetector["resourceId"]?.toString() ?: subResourceId
                            }
                        }
                    } catch (_: Exception) {}

                    var finalSubjectId = subResourceId
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

                    val params = mutableMapOf("subjectId" to finalSubjectId, "host" to "api6.aoneroom.com")
                    if (!isMovie) {
                        params["se"] = season
                        params["ep"] = episode
                    }
                    if (finalResourceId != null) params["resourceId"] = finalResourceId

                    val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/play-info", params)
                    val responseMap = parseJsonToMap(rawResponse)
                    val data = responseMap["data"] as? Map<String, Any?> ?: emptyMap()
                    val streams = (data["streamList"] ?: data["streams"] ?: emptyList<Any?>()) as? List<Map<String, Any?>> ?: emptyList()
                    val globalCookie = (responseMap["signCookie"] ?: data["signCookie"] ?: "").toString()

                    val streamItems = mutableListOf<Map<String, Any?>>()
                    val subtitlesMapped = mutableListOf<Map<String, Any?>>()

                    for (st in streams) {
                        var url = st["url"]?.toString() ?: continue
                        val qual = st["quality"]?.toString() ?: quality
                        val stCookie = st["signCookie"]?.toString() ?: globalCookie

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
                                url = if (decodedPath.endsWith("/")) "${decodedPath}index.mpd" else "$decodedPath/index.mpd"
                            } catch (e: Exception) {
                                Log.w("MovieBoxServer", "Failed to bypass dummy video URL: ${e.message}")
                            }
                        }

                        streamItems.add(mapOf("quality" to qual, "url" to url, "cookie" to stCookie))
                    }

                    // External subtitles
                    try {
                        val subParams = mapOf("subjectId" to finalSubjectId, "resourceId" to subResourceId, "episode" to episode)
                        val subResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get-ext-captions", subParams)
                        val subResponseMap = parseJsonToMap(subResponseRaw)
                        val subData = subResponseMap["data"] as? Map<String, Any?> ?: emptyMap()
                        val extList = (subData["extCaptions"] ?: subData["list"] ?: emptyList<Any?>()) as? List<Map<String, Any?>> ?: emptyList()
                        for (sub in extList) {
                            val lan = sub["lan"]?.toString() ?: ""
                            val lanName = sub["lanName"]?.toString() ?: ""
                            val subUrl = sub["url"]?.toString() ?: ""
                            if (subUrl.isNotEmpty()) {
                                subtitlesMapped.add(mapOf("language" to lanName.ifEmpty { lan }, "url" to subUrl, "format" to "vtt"))
                            }
                        }
                    } catch (_: Exception) {}

                    val responseObj = mapOf("code" to 0, "streamList" to streamItems, "subTitleList" to subtitlesMapped)
                    Pair(200, gson.toJson(responseObj))
                }

                else -> Pair(404, "{\"code\": 404, \"msg\": \"Not Found\"}")
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

    private fun mapItem(item: Map<String, Any?>): Map<String, Any?>? {
        val itemData = (item["subject"] as? Map<String, Any?>) ?: item
        val sid = (itemData["subjectId"] ?: itemData["id"] ?: "").toString()
        if (sid.isEmpty()) return null

        val title = (itemData["title"] ?: itemData["name"] ?: itemData["subjectName"] ?: "Unknown").toString()
        var posterUrl = ""
        val poster = itemData["poster"] ?: itemData["cover"]
        if (poster is Map<*, *>) posterUrl = poster["url"]?.toString() ?: ""
        else if (poster is String) posterUrl = poster

        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
        if (title == "Unknown" || posterUrl.isEmpty()) return null

        return mapOf(
            "id" to sid,
            "name" to title,
            "poster" to posterUrl,
            "description" to (itemData["description"] ?: ""),
            "rating" to (itemData["imdbRatingValue"] ?: itemData["score"] ?: "8.5").toString(),
            "year" to (itemData["releaseTime"] ?: itemData["year"] ?: "2026").toString().take(4),
            "isTvShow" to ((itemData["subjectType"] ?: itemData["type"] ?: 1).toString().toDoubleOrNull()?.toInt() != 1)
        )
    }

    fun makeOfficialRequest(
        method: String,
        endpoint: String,
        queryParams: Map<String, String> = emptyMap(),
        bodyString: String = ""
    ): String {
        val isCacheable = method == "GET" || endpoint.contains("/subject-api/get") || endpoint.contains("/subject-api/search") || endpoint.contains("/subject-api/play-info")
        val cacheKey = "$method|$endpoint|${queryParams.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}|$bodyString"
        if (isCacheable) {
            val now = System.currentTimeMillis()
            val cached = responseCache[cacheKey]
            if (cached != null && (now - cached.first) < 300000) return cached.second
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

        val clientInfoMap = mapOf(
            "package_name" to "com.movieboxpro.android",
            "version_name" to "16.2.1",
            "version_code" to 16210,
            "os" to "android",
            "os_version" to "12",
            "install_ch" to "googleplay",
            "device_id" to "8c5da15be6ca34e724a27bc102cd8bcf",
            "install_store" to "googleplay",
            "system_language" to "en",
            "net" to "wifi",
            "region" to "IN",
            "timezone" to "Asia/Kolkata",
            "sp_code" to "404"
        )

        val reqBuilder = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
            .header("Accept", accept)
            .header("Content-Type", contentType)
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

        if (guestToken.isNullOrEmpty()) {
            bootstrapGuestToken()
        }
        val tokenToUse = guestToken ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjcwNjU5NDg0MTAyMTM4MTYyMzIsInV0cCI6MSwiZXhwIjoxNzkxNzMyMjMzLCJpYXQiOjE3ODM5NTU5MzN9.7iyEzTj4vWAbOF0oXwNnZ0p3Nc1QaO6K9eMiGFyVfGs"
        reqBuilder.header("Authorization", "Bearer $tokenToUse")
        reqBuilder.header("X-Client-Status", "1")

        reqBuilder.method(method, requestBody)
        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        val resBody = response.body?.string() ?: ""
        if (isCacheable && resBody.isNotEmpty()) {
            responseCache[cacheKey] = Pair(System.currentTimeMillis(), resBody)
        }
        return resBody
    }

    private fun bootstrapGuestToken() {
        try {
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

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
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
                .header("X-Client-Info", gson.toJson(mapOf(
                    "package_name" to "com.movieboxpro.android",
                    "system_language" to "en",
                    "region" to "IN",
                    "timezone" to "Asia/Kolkata",
                    "sp_code" to "404"
                )))
                .header("X-Client-Status", "1")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val rawToken = response.header("x-user") ?: response.header("X-User")
            if (!rawToken.isNullOrEmpty()) {
                if (rawToken.contains("\"token\":\"")) {
                    val start = rawToken.indexOf("\"token\":\"") + "\"token\":\"".length
                    val end = rawToken.indexOf("\"", start)
                    if (end != -1) guestToken = rawToken.substring(start, end)
                } else guestToken = rawToken
            }
        } catch (e: Exception) {
            Log.w("MovieBoxServer", "Failed to bootstrap guest token: ${e.message}")
        }
    }
}
