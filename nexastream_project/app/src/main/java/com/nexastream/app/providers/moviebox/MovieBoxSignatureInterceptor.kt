package com.nexastream.app.providers.moviebox

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody

class MovieBoxSignatureInterceptor(private val context: Context) : Interceptor {

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

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        val urlBuilder = originalUrl.newBuilder()
        if (!originalUrl.queryParameterNames.contains("host")) {
            urlBuilder.addQueryParameter("host", "api.inmoviebox.com")
        }
        val httpUrl = urlBuilder.build()

        val method = originalRequest.method
        val timestamp = System.currentTimeMillis().toString()
        val reversed = timestamp.reversed()
        val clientToken = "$timestamp,${md5(reversed)}"

        val sortedQueryString = httpUrl.queryParameterNames.sorted().joinToString("&") { name ->
            val value = httpUrl.queryParameter(name) ?: ""
            "$name=$value"
        }

        var bodyString = ""
        val reqBody = originalRequest.body
        if (reqBody != null) {
            val buffer = okio.Buffer()
            reqBody.writeTo(buffer)
            bodyString = buffer.readString(StandardCharsets.UTF_8)
        }

        var actualBodyLength = ""
        var bodyHash = ""
        if (bodyString.isNotEmpty()) {
            val bytes = bodyString.toByteArray(Charsets.UTF_8)
            actualBodyLength = bytes.size.toString()
            bodyHash = md5Bytes(bytes)
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
            Log.e("MovieBoxInterceptor", "Error generating signature", e)
        }

        val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)
        val spCode = prefs.getString("sp_code", "")?.takeIf { it.isNotEmpty() } ?: "404"
        val region = prefs.getString("custom_local_iso", "")?.takeIf { it.isNotEmpty() }?.uppercase() ?: "IN"
        val timezone = if (region == "US") "America/New_York" else if (region == "BR") "America/Sao_Paulo" else if (region == "RU") "Europe/Moscow" else "Asia/Kolkata"

        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("device_id", deviceId).apply()
        }

        val clientInfoMap = mapOf(
            "package_name" to "com.movieboxpro.android",
            "version_name" to "16.2.1",
            "version_code" to 16210,
            "os" to "android",
            "os_version" to "12",
            "install_ch" to "googleplay",
            "device_id" to deviceId,
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
        // Simple JSON serialization for client info
        val clientInfoJson = com.google.gson.Gson().toJson(clientInfoMap)

        val reqBuilder = originalRequest.newBuilder()
            .url(httpUrl)
            .header("User-Agent", "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
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
            .header("X-Client-Info", clientInfoJson)
            .header("X-Client-Status", "1")

        var savedToken = prefs.getString("auth_token", null)
        if (savedToken.isNullOrEmpty()) {
            if (guestToken.isNullOrEmpty()) {
                bootstrapGuestToken(chain, httpUrl, signatureHeader, timestamp, clientToken, clientInfoJson, region, spCode, timezone)
            }
            savedToken = guestToken
        }
        if (!savedToken.isNullOrEmpty()) {
            reqBuilder.header("Authorization", "Bearer $savedToken")
        }

        val response = chain.proceed(reqBuilder.build())
        val rawToken = response.header("x-user") ?: response.header("X-User")
        if (!rawToken.isNullOrEmpty() && guestToken == null) {
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
            prefs.edit().putString("auth_token", guestToken).apply()
        }
        return response
    }

    private fun bootstrapGuestToken(
        chain: Interceptor.Chain,
        originalUrl: okhttp3.HttpUrl,
        sig: String,
        ts: String,
        token: String,
        clientInfo: String,
        region: String,
        spCode: String,
        timezone: String
    ) {
        try {
            val tabUrl = originalUrl.newBuilder()
                .encodedPath("/trending")
                .removeAllQueryParameters("host")
                .addQueryParameter("host", "api.inmoviebox.com")
                .addQueryParameter("tabId", "1")
                .addQueryParameter("page", "1")
                .addQueryParameter("pageSize", "24")
                .build()

            val req = Request.Builder()
                .url(tabUrl)
                .header("User-Agent", "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-Sign-Version", "2.0")
                .header("appid", "4U01pxRu278GqCZKY9")
                .header("region", region)
                .header("lang", "en")
                .header("os", "android")
                .header("X-Timestamp", ts)
                .header("Referer", "https://api.inmoviebox.com/")
                .header("X-Client-Token", token)
                .header("x-tr-signature", sig)
                .header("X-Play-Mode", "2")
                .header("X-Client-Info", clientInfo)
                .header("X-Client-Status", "1")
                .get()
                .build()

            val res = chain.proceed(req)
            val rawToken = res.header("x-user") ?: res.header("X-User")
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
                Log.i("MovieBoxInterceptor", "Bootstrapped guest token successfully")
            }
            res.close()
        } catch (e: Exception) {
            Log.e("MovieBoxInterceptor", "Bootstrap guest token failed", e)
        }
    }
}
