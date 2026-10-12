package com.nexastream.app.providers

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexastream.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class CricfyProviderSource(
    val title: String,
    val image: String?,
    val categoryUrl: String,
)

internal data class CricfyChannel(
    val title: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    val referer: String? = null,
    val licenseString: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val providerTitle: String = "",
)

internal object CricfyProviderClient {
    private const val TAG = "CricfyProviderClient"
    private const val USER_AGENT =
        "Dalvik/2.1.0 (Linux; U; Android 10; Build/QP1A.190711.020)"
    private const val PROVIDER_CACHE_MILLIS = 24L * 60 * 60 * 1000
    private const val CHANNEL_CACHE_MILLIS = 60L * 60 * 1000
    private const val FIREBASE_CONFIG_URL =
        "https://firebaseremoteconfig.googleapis.com/v1/projects/%s/namespaces/firebase:fetch"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .hostnameVerifier { _, _ -> true }
        .eventListenerFactory(EventListener.Factory(::RequestTrace))
        .build()
    private val providerMutex = Mutex()
    private val channelMutexes = ConcurrentHashMap<String, Mutex>()
    private val channelCache = ConcurrentHashMap<String, TimedChannels>()
    private val requestSlots = Semaphore(4)
    private var cachedProviders: TimedProviders? = null

    private data class TimedProviders(val value: List<CricfyProviderSource>, val loadedAt: Long)
    private data class TimedChannels(val value: List<CricfyChannel>, val loadedAt: Long)
    private data class ApiEndpoints(val endpoints: List<String>)

    suspend fun getProviders(): List<CricfyProviderSource> = providerMutex.withLock {
        cachedProviders
            ?.takeIf { System.currentTimeMillis() - it.loadedAt < PROVIDER_CACHE_MILLIS }
            ?.let { return@withLock it.value }

        val apiEndpoints = fetchRemoteConfig()
        var lastError: Exception? = null

        for (endpoint in apiEndpoints.endpoints) {
            try {
                val response = fetchText("${endpoint.trimEnd('/')}/cats.txt")
                val decrypted = CricfyCrypto.decryptProviderList(response)
                val providers = parseProviderList(decrypted)
                if (providers.isNotEmpty()) {
                    cachedProviders = TimedProviders(providers, System.currentTimeMillis())
                    return@withLock providers
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                logW(TAG, "Failed fetching provider list from endpoint $endpoint: ${error.message}", error)
                lastError = error
            }
        }

        throw IOException("Cricfy returned no valid providers from any endpoint", lastError)
    }

    suspend fun getChannels(source: CricfyProviderSource): List<CricfyChannel> {
        val cached = channelCache[source.categoryUrl]
        if (cached != null && System.currentTimeMillis() - cached.loadedAt < CHANNEL_CACHE_MILLIS) {
            return cached.value
        }

        val mutex = channelMutexes.getOrPut(source.categoryUrl) { Mutex() }
        return mutex.withLock {
            val latest = channelCache[source.categoryUrl]
            if (latest != null && System.currentTimeMillis() - latest.loadedAt < CHANNEL_CACHE_MILLIS) {
                return@withLock latest.value
            }

            val content = fetchText(source.categoryUrl)
            val playlist = CricfyCrypto.decryptPlaylist(content)
            val channels = CricfyM3UParser.parse(playlist).map { it.copy(providerTitle = source.title) }
            channelCache[source.categoryUrl] = TimedChannels(channels, System.currentTimeMillis())
            channels
        }
    }

    suspend fun getChannelsForAllProviders(): List<Pair<CricfyProviderSource, List<CricfyChannel>>> {
        val sources = getProviders()
        return coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    requestSlots.withPermit {
                        try {
                            source to getChannels(source)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            logW(TAG, "Unable to load Cricfy provider '${source.title}'", error)
                            source to emptyList()
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchRemoteConfig(): ApiEndpoints = withContext(Dispatchers.IO) {
        require(BuildConfig.CRICFY_PACKAGE_NAME.isNotBlank() &&
            BuildConfig.CRICFY_FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.CRICFY_FIREBASE_APP_ID.isNotBlank()
        ) {
            "Cricfy Firebase settings are missing. Configure CRICFY_PACKAGE_NAME, " +
                "CRICFY_FIREBASE_API_KEY, and CRICFY_FIREBASE_APP_ID in local.properties."
        }

        val projectNumber = BuildConfig.CRICFY_FIREBASE_APP_ID.split(':').getOrNull(1)
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("CRICFY_FIREBASE_APP_ID does not contain a Firebase project number")
        val url = FIREBASE_CONFIG_URL.format(projectNumber)
        var lastError: Exception? = null

        repeat(3) {
            try {
                val payload = JsonObject().apply {
                    addProperty("appInstanceId", UUID.randomUUID().toString().replace("-", ""))
                    addProperty("appInstanceIdToken", "")
                    addProperty("appId", BuildConfig.CRICFY_FIREBASE_APP_ID)
                    addProperty("countryCode", "US")
                    addProperty("languageCode", "en-US")
                    addProperty("platformVersion", "30")
                    addProperty("timeZone", "UTC")
                    addProperty("appVersion", "5.0")
                    addProperty("appBuild", "50")
                    addProperty("packageName", BuildConfig.CRICFY_PACKAGE_NAME)
                    addProperty("sdkVersion", "22.1.0")
                    add("analyticsUserProperties", JsonObject())
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("X-Android-Package", BuildConfig.CRICFY_PACKAGE_NAME)
                    .header("X-Goog-Api-Key", BuildConfig.CRICFY_FIREBASE_API_KEY)
                    .header("X-Google-GFE-Can-Retry", "yes")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                val responseJson = execute(request)
                val entries = JsonParser.parseString(responseJson).asJsonObject
                    .getAsJsonObject("entries")
                    ?: throw IOException("Firebase Remote Config response has no entries")
                val api1 = entries.get("cric_api1")?.takeIf { it.isJsonPrimitive }?.asString
                val api2 = entries.get("cric_api2")?.takeIf { it.isJsonPrimitive }?.asString
                val directEndpoints = listOfNotNull(api2, api1).filter(String::isNotBlank)

                val r2Raw = entries.get("r2_config_urls")?.takeIf { it.isJsonPrimitive }?.asString
                val r2Urls = r2Raw?.split(',')?.map(String::trim)?.filter { it.endsWith("/config.txt") }.orEmpty()
                val r2Endpoints = mutableListOf<String>()
                for (r2Url in r2Urls) {
                    r2Endpoints.addAll(resolveR2Endpoints(r2Url))
                }

                val endpointsList = (directEndpoints + r2Endpoints)
                    .map { it.trimEnd('/') }
                    .filter(String::isNotBlank)
                    .distinct()
                if (endpointsList.isEmpty()) {
                    throw IOException("Firebase Remote Config has no Cricfy API endpoints")
                }
                return@withContext ApiEndpoints(endpointsList)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                logW(
                    TAG,
                    "Firebase Remote Config attempt ${it + 1}/3 failed: ${error.javaClass.simpleName}: ${error.message}",
                )
                lastError = error
            }
        }

        val reason = lastError?.let { "${it.javaClass.simpleName}: ${it.message}" }
        throw IOException("Unable to fetch Cricfy Firebase Remote Config after 3 attempts. $reason", lastError)
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-Android-Package", BuildConfig.CRICFY_PACKAGE_NAME)
            .header("Accept", "*/*")
            .get()
            .build()
        execute(request)
    }

    private suspend fun resolveR2Endpoints(r2Url: String): List<String> {
        return try {
            val encryptedText = fetchText(r2Url)
            val decryptedJson = CricfyCrypto.decryptProviderList(encryptedText)
            val rootObj = JsonParser.parseString(decryptedJson).asJsonObject
            val parameters = rootObj.getAsJsonObject("parameters") ?: return emptyList()
            val api1 = parameters.getAsJsonObject("cric_api1")
                ?.getAsJsonObject("defaultValue")
                ?.get("value")?.takeIf { it.isJsonPrimitive }?.asString
            val api2 = parameters.getAsJsonObject("cric_api2")
                ?.getAsJsonObject("defaultValue")
                ?.get("value")?.takeIf { it.isJsonPrimitive }?.asString
            listOfNotNull(api2, api1).filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Cricfy request failed with HTTP ${response.code}")
            }
            return response.body?.string()
                ?: throw IOException("Cricfy returned an empty response")
        }
    }

    internal fun parseProviderList(json: String): List<CricfyProviderSource> {
        val root = JsonParser.parseString(json)
        require(root.isJsonArray) { "Cricfy provider response is not a JSON array" }

        return root.asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val provider = element.asJsonObject
            val title = provider.get("title")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                .orEmpty()
            val categoryUrl = provider.get("catLink")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                .orEmpty()
            if (title.isBlank() || !categoryUrl.startsWith("http://", true) &&
                !categoryUrl.startsWith("https://", true)
            ) {
                return@mapNotNull null
            }
            CricfyProviderSource(
                title = title,
                image = provider.get("image")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.takeIf(String::isNotBlank),
                categoryUrl = categoryUrl,
            )
        }.distinctBy { it.categoryUrl }
    }

    private fun logW(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg")
            tr?.printStackTrace(System.err)
        }
    }

    private fun logD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            System.out.println("[$tag] $msg")
        }
    }

    private class RequestTrace(call: Call) : EventListener() {
        private val callId = Integer.toHexString(System.identityHashCode(call))
        private val startedAt = System.nanoTime()

        private fun trace(event: String) {
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            logD(TAG, "request=$callId elapsed=${elapsedMillis}ms $event")
        }
    }
}
