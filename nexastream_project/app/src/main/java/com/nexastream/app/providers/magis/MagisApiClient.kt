package com.nexastream.app.providers.magis

import android.util.Log
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

class MagisDns : Dns {
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Try system DNS first
        try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isNotEmpty()) return addresses
        } catch (e: Exception) {
            Log.w("MagisDns", "System DNS failed for $hostname, trying DoH fallback...")
        }

        // 2. DoH fallback via Google DoH JSON API
        try {
            val url = "https://dns.google/resolve?name=$hostname&type=A"
            val req = Request.Builder().url(url).build()
            val resp = dohClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (body.isNotEmpty()) {
                val json = JSONObject(body)
                val answer = json.optJSONArray("Answer")
                if (answer != null) {
                    val resolved = mutableListOf<InetAddress>()
                    for (i in 0 until answer.length()) {
                        val obj = answer.optJSONObject(i) ?: continue
                        val ip = obj.optString("data")
                        if (ip.isNotEmpty() && ip.contains(".")) {
                            resolved.add(InetAddress.getByName(ip))
                        }
                    }
                    if (resolved.isNotEmpty()) {
                        Log.i("MagisDns", "Google DoH resolved $hostname -> ${resolved.map { it.hostAddress }}")
                        return resolved
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MagisDns", "Google DoH lookup failed for $hostname", e)
        }

        // 3. Cloudflare DoH fallback
        try {
            val url = "https://cloudflare-dns.com/dns-query?name=$hostname&type=A"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            val resp = dohClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (body.isNotEmpty()) {
                val json = JSONObject(body)
                val answer = json.optJSONArray("Answer")
                if (answer != null) {
                    val resolved = mutableListOf<InetAddress>()
                    for (i in 0 until answer.length()) {
                        val obj = answer.optJSONObject(i) ?: continue
                        val ip = obj.optString("data")
                        if (ip.isNotEmpty() && ip.contains(".")) {
                            resolved.add(InetAddress.getByName(ip))
                        }
                    }
                    if (resolved.isNotEmpty()) {
                        Log.i("MagisDns", "Cloudflare DoH resolved $hostname -> ${resolved.map { it.hostAddress }}")
                        return resolved
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MagisDns", "Cloudflare DoH lookup failed for $hostname", e)
        }

        throw UnknownHostException("Unable to resolve $hostname via System DNS or DoH")
    }
}

class MagisApiClient(
    val hosts: List<String> = DEFAULT_HOSTS,
    val key3desHex: String = DEFAULT_3DES_KEY
) {
    companion object {
        private const val TAG = "MagisApiClient"

        const val DEFAULT_3DES_KEY = "323135323432323032333036323131363232303530343132"
        val DEFAULT_HOSTS = listOf(
            "osuhk.m3x8o50te.com",
            "oivp.eubvixzc.com",
            "wrtu.nbvzxvca.com",
            "niguof.vynbszicd.com",
            "aluve.rdgqkfxio.com"
        )

        private val client = OkHttpClient.Builder()
            .dns(MagisDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    var userId: String = ""
    var userToken: String = ""
    private var currentHostIndex = 0
    private val deviceSn = generateDeviceSn()

    private fun generateDeviceSn(): String {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    }

    private fun getActiveHost(): String {
        return hosts.getOrElse(currentHostIndex) { hosts.first() }
    }

    private fun rotateHost() {
        currentHostIndex = (currentHostIndex + 1) % hosts.size
    }

    private fun createBaseDeviceJson(): JSONObject {
        return JSONObject().apply {
            put("loginType", "2")
            put("appLanguage", "es")
            put("apkVersion", "49902")
            put("sysVersion", "2025-08-07 05:40:11_36_16_")
            put("appId", "com.android.msandroid")
            put("hardwareInfo", "ranchu")
            put("model", "sdk_gphone64_arm64")
            put("product", "sdk_gphone64_arm64")
            put("cpu", "arm64-v8a")
            put("B29", "")
            put("reserve1", "")
            put("deviceToken", "")
            put("sn", deviceSn)
            put("drmId", "")
            put("sdkVer", 36)
        }
    }

    fun callApi(path: String, bean: JSONObject = JSONObject(), includeAuth: Boolean = true): JSONObject? {
        val payload = createBaseDeviceJson().apply {
            if (includeAuth) {
                put("portalCode", "masnew")
                put("userId", userId)
                put("userToken", userToken)
            }
            bean.keys().forEach { key ->
                put(key, bean.get(key))
            }
        }

        val wireEncrypted = try {
            Magis3Des.encryptBody(payload.toString(), key3desHex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt payload for path: $path", e)
            return null
        }

        var attempts = 0
        while (attempts < hosts.size) {
            val host = getActiveHost()
            val url = "https://$host/api/portalCore/$path"

            try {
                val request = Request.Builder()
                    .url(url)
                    .post(wireEncrypted.toRequestBody("text/plain".toMediaType()))
                    .header("Content-Type", "application/json;charset=utf-8")
                    .header("apk", "com.android.msandroid")
                    .header("apkVer", "49902")
                    .header("spkgVer", "2025-08-07 05:40:11_36_16_")
                    .header("User-Agent", "okhttp/3.12.12")
                    .build()

                val response = client.newCall(request).execute()
                val responseStr = response.body?.string() ?: ""

                if (responseStr.isEmpty()) {
                    rotateHost()
                    attempts++
                    continue
                }

                val jsonResp = JSONObject(responseStr)
                val returnCode = jsonResp.optString("returnCode")

                if (returnCode != "0" && returnCode.isNotEmpty()) {
                    if ((returnCode == "aaa100027" || returnCode == "aaa100028") && path != "v8/active") {
                        if (activate()) {
                            return callApi(path, bean, includeAuth)
                        }
                    }
                    Log.w(TAG, "API call returned error code: $returnCode on path: $path")
                    rotateHost()
                    attempts++
                    continue
                }

                val dataBlob = jsonResp.optString("data")
                if (dataBlob.isNotEmpty()) {
                    val decryptedStr = Magis3Des.decryptBlob(dataBlob, key3desHex)
                    if (decryptedStr.isNotEmpty()) {
                        return JSONObject(decryptedStr)
                    }
                }

                return jsonResp
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to host $host for $path: ${e.javaClass.simpleName} - ${e.message}", e)
                rotateHost()
                attempts++
            }
        }
        return null
    }

    fun activate(): Boolean {
        val bean = JSONObject().apply {
            put("snToken", "")
            put("authVersion", "")
            put("authCode", "")
            put("preCode", "")
            put("macAddr", "02:00:00:00:00:00")
            put("reserve1", "")
            put("openNum", 4)
            put("channel", "default")
            put("matadata", "")
            put("signdata", "")
        }

        val resp = callApi("v8/active", bean, includeAuth = false) ?: return false
        val newUserId = resp.optString("userId")
        val newUserToken = resp.optString("userToken")

        if (newUserId.isNotEmpty() && newUserToken.isNotEmpty()) {
            userId = newUserId
            userToken = newUserToken
            return true
        }
        return false
    }

    fun ensureActive(): Boolean {
        if (userId.isEmpty() || userToken.isEmpty()) {
            return activate()
        }
        return true
    }

    fun getSlbInfo(channelCode: String): JSONObject? {
        ensureActive()
        val bean = JSONObject().apply {
            put("hasPay", "0")
            put("userIdentity", "1")
            put("type", "merge")
            put("appVer", "49902")
            put("lang", "es")
            put("encMediaSupported", 1)
            put("liveCodeList", JSONArray(listOf(channelCode)))
            put("appParams", "")
            put("reserve1", "02:00:00:00:00:00")
            put("pipFlag", "0")
        }
        return callApi("v14/getSlbInfo", bean)
    }

    fun getVodSlbInfo(): JSONObject? {
        ensureActive()
        val bean = JSONObject().apply {
            put("hasPay", "0")
            put("userIdentity", "1")
            put("type", "merge")
            put("appVer", "49902")
            put("lang", "es")
            put("encMediaSupported", 1)
            put("liveCodeList", JSONArray())
            put("appParams", "")
            put("reserve1", "02:00:00:00:00:00")
            put("pipFlag", "0")
        }
        return callApi("v14/getSlbInfo", bean)
    }

    fun liveCategories(): JSONObject? {
        ensureActive()
        val bean = JSONObject().apply {
            put("columnCode", "masnew_live")
            put("pageNum", 1)
            put("pageSize", 50)
            put("version", "")
        }
        return callApi("getNextColumns", bean)
    }

    fun getLiveData(columnId: String = "76182", page: Int = 1, pageSize: Int = 1000): JSONObject? {
        ensureActive()
        val bean = JSONObject().apply {
            put("columnId", columnId.toIntOrNull() ?: 76182)
            put("pageNum", page)
            put("pageSize", pageSize)
            put("dataVersion", "")
            put("expireTimeStr", "")
        }
        return callApi("v6/getLiveData", bean)
    }

    fun startPlayLive(channelCode: String, columnId: Int = 0): JSONObject? {
        ensureActive()
        val bean = JSONObject().apply {
            put("channelCode", channelCode)
            put("columnId", columnId)
            put("type", "1")
        }
        return callApi("v4/startPlayLive", bean)
    }

    fun getDetail(contentId: String, type: String = "0"): JSONObject? {
        ensureActive()
        val bean = JSONObject().apply {
            put("contentId", contentId)
            put("type", type)
            put("sortType", "0")
            put("language", "es")
            put("macAddr", "02:00:00:00:00:00")
        }
        return callApi("v4/getItemData", bean)
    }

    fun playVod(contentId: String, seriesContentId: String = "", startTime: Int = 0): JSONObject? {
        ensureActive()
        val bean = JSONObject().apply {
            put("contentId", contentId)
            put("seriesContentId", seriesContentId)
            put("startTime", startTime)
            put("type", "1")
            put("columnId", 0)
            put("authType", "")
        }
        return callApi("v10/startPlayVOD", bean)
    }

    fun search(query: String, page: Int = 1, pageSize: Int = 30): JSONObject? {
        ensureActive()
        val bean = JSONObject().apply {
            put("value", query)
            put("type", "0")
            put("columnId", "")
            put("filter", "")
            put("pageNum", page)
            put("pageSize", pageSize)
        }
        return callApi("v3/searchByName", bean)
    }
}
