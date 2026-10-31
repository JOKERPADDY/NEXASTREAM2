package com.nexastream.app.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class DownloadMetadata(
    val length: Long,
    val etag: String? = null
)

class DownloadProbeException(message: String) : Exception(message)

object DownloadMetadataProbe {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val restrictedHeaders = setOf(
        "range", "if-range", "if-match", "known-content-length", "accept-encoding"
    )

    private fun sanitizeHeaders(supplied: Map<String, String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((k, v) in supplied) {
            if (k.lowercase() !in restrictedHeaders) {
                result[k] = v
            }
        }
        if (result.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            result["User-Agent"] = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
        }
        result["Accept-Encoding"] = "identity"
        return result
    }

    fun isHlsUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains("m3u8=") || lower.contains("/hls/")
    }

    fun resolveUrl(baseUrl: String, target: String): String {
        val trimmed = target.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (e: Exception) {
            if (trimmed.startsWith("/")) {
                val origin = baseUrl.substringBefore("/", baseUrl).ifEmpty { baseUrl }
                "$origin$trimmed"
            } else {
                val baseDir = baseUrl.substringBeforeLast("/")
                "$baseDir/$trimmed"
            }
        }
    }

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): DownloadMetadata = withContext(Dispatchers.IO) {
        if (isHlsUrl(url)) {
            return@withContext probeHls(url, headers)
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")

        val sanitized = sanitizeHeaders(headers)
        for ((k, v) in sanitized) {
            requestBuilder.header(k, v)
        }

        val request = requestBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code != 200 && code != 206) {
                    throw DownloadProbeException("Server returned HTTP $code. Choose another quality or try again.")
                }

                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                if (contentType.contains("mpegurl") || contentType.contains("m3u8")) {
                    return@withContext probeHls(url, headers)
                }

                val encoding = response.header("Content-Encoding")?.lowercase()
                if (encoding != null && encoding != "identity") {
                    throw DownloadProbeException("Server cannot provide an exact download size due to '$encoding' encoding. Choose another quality.")
                }

                var length: Long? = null
                if (code == 206) {
                    val contentRange = response.header("Content-Range")?.trim() ?: ""
                    val matcher = Pattern.compile("^bytes 0-0/([1-9][0-9]*)$").matcher(contentRange)
                    if (matcher.find()) {
                        length = matcher.group(1)?.toLongOrNull()
                    }
                } else {
                    val contentLengthHeader = response.header("Content-Length")
                    length = contentLengthHeader?.toLongOrNull() ?: response.body?.contentLength()
                }

                if (length == null || length <= 0) {
                    throw DownloadProbeException("This quality has no verifiable download size. Choose another quality.")
                }

                val rawEtag = response.header("ETag")?.trim()
                val strongEtag = if (rawEtag != null && rawEtag.matches(Regex("^\"[^\r\n\"]+\"\$"))) rawEtag else null

                DownloadMetadata(length, strongEtag)
            }
        } catch (e: DownloadProbeException) {
            throw e
        } catch (e: IOException) {
            throw DownloadProbeException("Could not verify download size: ${e.message}")
        } catch (e: Exception) {
            throw DownloadProbeException("Unexpected error during download probe: ${e.message}")
        }
    }

    private fun probeHls(url: String, headers: Map<String, String>): DownloadMetadata {
        val sanitized = sanitizeHeaders(headers)
        val requestBuilder = Request.Builder().url(url)
        for ((k, v) in sanitized) {
            requestBuilder.header(k, v)
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DownloadProbeException("Server returned HTTP ${response.code} for playlist.")
                }

                val bodyString = response.body?.string() ?: ""
                if (!bodyString.contains("#EXTM3U")) {
                    throw DownloadProbeException("Invalid HLS playlist.")
                }

                var mediaPlaylistUrl = url
                var mediaPlaylistBody = bodyString

                if (bodyString.contains("#EXT-X-STREAM-INF")) {
                    val variantUrl = parseMasterPlaylistHighestVariant(bodyString, url)
                    if (variantUrl != null) {
                        mediaPlaylistUrl = variantUrl
                        val variantReq = Request.Builder().url(variantUrl)
                        for ((k, v) in sanitized) variantReq.header(k, v)
                        client.newCall(variantReq.build()).execute().use { variantResp ->
                            if (variantResp.isSuccessful) {
                                mediaPlaylistBody = variantResp.body?.string() ?: ""
                            }
                        }
                    }
                }

                val segmentUrls = parseMediaPlaylistSegments(mediaPlaylistBody, mediaPlaylistUrl)
                if (segmentUrls.isEmpty()) {
                    throw DownloadProbeException("No video segments found in HLS playlist.")
                }

                var sampledTotalBytes = 0L
                var sampledCount = 0
                val maxSamples = minOf(3, segmentUrls.size)

                for (i in 0 until maxSamples) {
                    val segUrl = segmentUrls[i]
                    val segReqBuilder = Request.Builder().url(segUrl)
                    for ((k, v) in sanitized) segReqBuilder.header(k, v)
                    try {
                        client.newCall(segReqBuilder.build()).execute().use { segResp ->
                            if (segResp.isSuccessful) {
                                val len = segResp.body?.contentLength() ?: 0L
                                if (len > 0) {
                                    sampledTotalBytes += len
                                    sampledCount++
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                val estimatedTotalSize = if (sampledCount > 0) {
                    (sampledTotalBytes / sampledCount) * segmentUrls.size
                } else {
                    segmentUrls.size * 1_200_000L
                }

                return DownloadMetadata(length = estimatedTotalSize, etag = null)
            }
        } catch (e: DownloadProbeException) {
            throw e
        } catch (e: Exception) {
            throw DownloadProbeException("Failed to probe HLS stream: ${e.message}")
        }
    }

    private fun parseMasterPlaylistHighestVariant(body: String, baseUrl: String): String? {
        val lines = body.lines()
        var maxBandwidth = -1L
        var bestUrl: String? = null
        var currentBandwidth = 0L

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val bwMatch = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(line)
                currentBandwidth = bwMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                if (currentBandwidth >= maxBandwidth) {
                    maxBandwidth = currentBandwidth
                    bestUrl = resolveUrl(baseUrl, line)
                }
            }
        }
        return bestUrl
    }

    private fun parseMediaPlaylistSegments(body: String, baseUrl: String): List<String> {
        val lines = body.lines()
        val segments = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                segments.add(resolveUrl(baseUrl, trimmed))
            }
        }
        return segments
    }
}
