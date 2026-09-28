package com.nexastream.app.utils

import androidx.media3.common.MimeTypes
import com.nexastream.app.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

object DownloadQualityFormatter {

    data class StreamMetadata(
        val resolutionLabel: String,
        val dimensions: String?,
        val bitrate: Long?,
        val exactSizeBytes: Long?,
        val formatLabel: String
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun inspectStreamMetadata(video: Video, targetQuality: String = ""): StreamMetadata {
        val url = video.source
        val lowerUrl = url.lowercase(Locale.US)
        val lowerMime = video.type?.lowercase(Locale.US).orEmpty()
        val formatStr = format(video.type, url)

        // 1. Data URL Base64 HLS Manifest
        if (url.startsWith("data:application/vnd.apple.mpegurl", ignoreCase = true) ||
            url.startsWith("data:application/x-mpegurl", ignoreCase = true) ||
            url.startsWith("data:text/vtt", ignoreCase = true)
        ) {
            val manifestContent = decodeDataUrl(url)
            if (manifestContent != null) {
                val parsed = parseHlsManifest(manifestContent, targetQuality)
                if (parsed != null) return parsed.copy(formatLabel = formatStr)
            }
        }

        // 2. Remote HLS .m3u8 Playlist
        if (lowerMime.contains("mpegurl") || lowerUrl.contains(".m3u8")) {
            val manifestContent = fetchTextUrl(url, video.headers)
            if (!manifestContent.isNullOrBlank()) {
                val parsed = parseHlsManifest(manifestContent, targetQuality)
                if (parsed != null) return parsed.copy(formatLabel = formatStr)
            }
        }

        // 3. Direct MP4 / MKV file HEAD Request for Content-Length
        if (lowerMime.startsWith("video/") || lowerUrl.contains(".mp4") || lowerUrl.contains(".mkv")) {
            val contentLength = fetchContentLength(url, video.headers)
            if (contentLength != null && contentLength > 0L) {
                val resLabel = if (targetQuality.isNotBlank()) targetQuality else "1080P"
                return StreamMetadata(
                    resolutionLabel = resLabel,
                    dimensions = null,
                    bitrate = null,
                    exactSizeBytes = contentLength,
                    formatLabel = formatStr
                )
            }
        }

        val resLabel = if (targetQuality.isNotBlank()) targetQuality else "720P"
        return StreamMetadata(
            resolutionLabel = resLabel,
            dimensions = null,
            bitrate = null,
            exactSizeBytes = null,
            formatLabel = formatStr
        )
    }

    private fun decodeDataUrl(dataUrl: String): String? {
        return runCatching {
            val payload = dataUrl.substringAfter(',', missingDelimiterValue = "")
            if (dataUrl.contains(";base64", ignoreCase = true)) {
                String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else {
                android.net.Uri.decode(payload)
            }
        }.getOrNull()
    }

    private suspend fun fetchTextUrl(url: String, headers: Map<String, String>?): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url)
                headers?.forEach { (k, v) -> builder.header(k, v) }
                if (headers == null || !headers.containsKey("User-Agent")) {
                    builder.header("User-Agent", NetworkClient.USER_AGENT)
                }
                httpClient.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrNull()
        }
    }

    private suspend fun fetchContentLength(url: String, headers: Map<String, String>?): Long? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).head()
                headers?.forEach { (k, v) -> builder.header(k, v) }
                if (headers == null || !headers.containsKey("User-Agent")) {
                    builder.header("User-Agent", NetworkClient.USER_AGENT)
                }
                httpClient.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        response.header("Content-Length")?.toLongOrNull()
                    } else null
                }
            }.getOrNull()
        }
    }

    private fun parseHlsManifest(manifestContent: String, targetQuality: String): StreamMetadata? {
        val lines = manifestContent.lines()
        var bestMatch: StreamMetadata? = null
        var maxBandwidth = 0L

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val bandwidthMatch = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(line)
                val resolutionMatch = Regex("""RESOLUTION=(\d+x\d+)""", RegexOption.IGNORE_CASE).find(line)

                val bw = bandwidthMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val dim = resolutionMatch?.groupValues?.getOrNull(1)
                val height = dim?.substringAfter('x')?.toIntOrNull()
                val resLabel = if (height != null) "${height}P" else "HD"

                val estimatedBytes = if (bw > 0L) (bw * 2700L) / 8L else null

                val candidate = StreamMetadata(
                    resolutionLabel = resLabel,
                    dimensions = dim,
                    bitrate = bw,
                    exactSizeBytes = estimatedBytes,
                    formatLabel = "HLS"
                )

                if (targetQuality.isNotBlank() && resLabel.equals(targetQuality, ignoreCase = true)) {
                    return candidate
                }

                if (bw > maxBandwidth) {
                    maxBandwidth = bw
                    bestMatch = candidate
                }
            }
        }
        return bestMatch
    }

    fun formatBitrate(bitrateBps: Long?): String? {
        if (bitrateBps == null || bitrateBps <= 0L) return null
        val mbps = bitrateBps.toDouble() / 1_000_000.0
        return if (mbps >= 1.0) {
            "%.1f Mbps".format(Locale.US, mbps)
        } else {
            "%.0f kbps".format(Locale.US, bitrateBps.toDouble() / 1_000.0)
        }
    }

    fun formatSizeBytes(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
        val megabytes = bytes.toDouble() / (1024.0 * 1024.0)
        return if (megabytes >= 1000.0) {
            "%.1f GB".format(Locale.US, megabytes / 1024.0)
        } else {
            "%.1f MB".format(Locale.US, megabytes)
        }
    }

    fun title(server: Video.Server): String {
        return listOfNotNull(
            resolution(server, server.video).takeIf { it != UNKNOWN },
            sourceName(server).takeIf { it.isNotBlank() }
        ).ifEmpty {
            listOf(server.name.ifBlank { "Source" })
        }.joinToString(" - ")
    }

    fun details(server: Video.Server, quality: String = ""): String {
        val size = estimatedSizeString(server, quality)
        val fmt = format(server.video?.type, server.video?.source ?: server.src)
        return "$size | $fmt"
    }

    fun estimatedSizeString(server: Video.Server, quality: String = ""): String {
        val explicitSize = fileSize(server)
        if (explicitSize != null) return explicitSize

        val res = if (quality.isNotBlank()) quality else resolution(server, server.video)
        return when {
            res.contains("2160") || res.contains("4k") -> "~2.0 - 3.5 GB"
            res.contains("1080") -> "~800 MB - 1.2 GB"
            res.contains("720") -> "~400 - 650 MB"
            res.contains("480") -> "~200 - 350 MB"
            res.contains("360") -> "~100 - 180 MB"
            else -> "~350 MB"
        }
    }

    fun qualityLabel(server: Video.Server, video: Video? = server.video): String {
        return listOfNotNull(
            resolution(server, video).takeIf { it != UNKNOWN },
            sourceName(server).takeIf { it.isNotBlank() },
            format(video?.type, video?.source ?: server.src)
        ).ifEmpty {
            listOf(server.name.ifBlank { "Unknown quality" })
        }.joinToString(" - ")
    }

    fun resolution(server: Video.Server, video: Video? = server.video): String {
        val targetVideo = video ?: server.video
        val sourceUrl = targetVideo?.source.orEmpty()
        if (sourceUrl.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
            try {
                val base64Data = sourceUrl.substringAfter("base64,")
                val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                val manifestContent = String(decodedBytes, Charsets.UTF_8)
                
                val resolutions = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                    .findAll(manifestContent)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .toList()
                
                if (resolutions.isNotEmpty()) {
                    val maxResolution = resolutions.maxOrNull()
                    if (maxResolution != null) {
                        return "${maxResolution}p"
                    }
                }
            } catch (e: Exception) {
                // Fallback
            }
        }

        val safeSourceText = if (sourceUrl.startsWith("data:")) "" else sourceUrl
        val text = "${server.name} ${server.src} $safeSourceText".lowercase(Locale.US)
        val numericResolution = Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240)p?(?!\d)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

        return when {
            numericResolution != null -> "${numericResolution}p"
            Regex("""\b(4k|uhd)\b""").containsMatchIn(text) -> "2160p"
            Regex("""\b(full hd|fhd)\b""").containsMatchIn(text) -> "1080p"
            Regex("""\bhd\b""").containsMatchIn(text) -> "720p"
            Regex("""\bsd\b""").containsMatchIn(text) -> "480p"
            Regex("""\bcam\b""").containsMatchIn(text) -> "CAM"
            else -> UNKNOWN
        }
    }

    private fun sourceName(server: Video.Server): String {
        val name = server.name
            .replace(Regex("""(?i)\b(2160p|1440p|1080p|720p|576p|540p|480p|360p|240p|4k|uhd|fhd|hd|sd|cam)\b"""), "")
            .replace(Regex("""(?i)\b(\d+(?:\.\d+)?\s*(gb|mb|kb))\b"""), "")
            .replace(Regex("""\s*[-|/]\s*"""), " ")
            .trim()

        return name.ifBlank { server.id.ifBlank { "Source" } }
    }

    fun fileSize(server: Video.Server): String? {
        val text = server.name
        return Regex("""(?i)\b\d+(?:\.\d+)?\s*(gb|mb|kb)\b""")
            .find(text)
            ?.value
            ?.uppercase(Locale.US)
    }

    fun format(mimeType: String?, url: String): String {
        val lowerMime = mimeType?.lowercase(Locale.US).orEmpty()
        val lowerUrl = url.lowercase(Locale.US)

        return when {
            lowerMime == MimeTypes.APPLICATION_M3U8 || lowerMime.contains("mpegurl") || lowerUrl.contains(".m3u8") -> "HLS"
            lowerMime == MimeTypes.APPLICATION_MPD || lowerUrl.contains(".mpd") -> "DASH"
            lowerMime == MimeTypes.VIDEO_MP4 || lowerUrl.substringBefore('?').endsWith(".mp4") -> "MP4"
            lowerUrl.substringBefore('?').endsWith(".mkv") -> "MKV"
            lowerUrl.substringBefore('?').endsWith(".webm") -> "WEBM"
            lowerMime.startsWith("video/") -> lowerMime.removePrefix("video/").uppercase(Locale.US)
            else -> "HLS"
        }
    }

    private const val UNKNOWN = "Unknown"
}
