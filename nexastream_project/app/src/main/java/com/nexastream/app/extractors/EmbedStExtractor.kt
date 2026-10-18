package com.nexastream.app.extractors

import com.nexastream.app.models.Video
import okhttp3.Request
import org.jsoup.Jsoup
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class EmbedStExtractor : Extractor() {
    override val name: String = "EmbedSt"
    override val mainUrl: String = "https://embed.st"
    override val aliasUrls: List<String> = listOf(
        "top-embed.com",
        "streamed.st",
        "streamed.pk",
        "streamed.is",
        "v3.streamed.su",
        "streamed.su",
        "strmd.link",
        "streampk.org",
        "stream.pk"
    )

    private val client = com.nexastream.app.utils.NetworkClient.compatibleTrustAll.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        android.util.Log.d("EmbedStExtractor", "Extracting from: $link")
        
        val uri = try { java.net.URI(link) } catch (_: Exception) { null }
        val baseHost = if (uri != null) "${uri.scheme}://${uri.host}" else "https://streamed.pk"
        val referer = "$baseHost/"
        
        // 1. Fetch HTML page with fast timeout
        var html = ""
        try {
            val request = Request.Builder()
                .url(link)
                .header("Referer", referer)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            
            val response = withTimeoutOrNull(3000) {
                client.newCall(request).execute()
            }
            html = response?.body?.string().orEmpty()
        } catch (e: Exception) {
            android.util.Log.w("EmbedStExtractor", "Failed to fetch HTML from $link: ${e.message}")
        }

        if (html.isNotBlank()) {
            val doc = Jsoup.parse(html)
            
            // Pattern 1: Direct source in scripts
            val scripts = doc.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                
                val m3u8Match = findM3u8InText(scriptContent, baseHost)
                if (m3u8Match != null) {
                    android.util.Log.d("EmbedStExtractor", "Found m3u8 source in script: $m3u8Match")
                    return@withContext createVideoResult(m3u8Match, referer)
                }
                
                // Check for atob pattern (often used for obfuscation)
                val atobRegex = Regex("""atob\s*\(\s*["']([^"']+)["']\s*\)""")
                atobRegex.findAll(scriptContent).forEach { match ->
                    try {
                        val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                        val m = findM3u8InText(decoded, baseHost)
                        if (m != null) {
                            android.util.Log.d("EmbedStExtractor", "Found decoded m3u8: $m")
                            return@withContext createVideoResult(m, referer)
                        }
                    } catch (_: Exception) {}
                }
            }
            
            // Pattern 2: iframe redirection
            val iframes = doc.select("iframe")
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src")
                if (!iframeSrc.isNullOrBlank() && iframeSrc.startsWith("http")) {
                     val isAlias = aliasUrls.any { iframeSrc.contains(it.substringAfter("://")) }
                     if (!iframeSrc.contains("embed.st") && !isAlias) {
                         android.util.Log.d("EmbedStExtractor", "Found sub-iframe: $iframeSrc")
                         return@withContext Extractor.extract(iframeSrc)
                     }
                }
            }

            // Pattern 3: Packed JS
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
                    .find(html)?.groupValues?.get(1)
                
                if (packedJS != null) {
                    val unpacked = com.nexastream.app.utils.JsUnpacker(packedJS).unpack()
                    if (unpacked != null) {
                        val m = findM3u8InText(unpacked, baseHost)
                        if (m != null) {
                            android.util.Log.d("EmbedStExtractor", "Found m3u8 in unpacked JS: $m")
                            return@withContext createVideoResult(m, referer)
                        }
                    }
                }
            }

            // Pattern 4: window.__SVELTEKIT_DATA__ or sveltekit
            if (html.contains("sveltekit")) {
                val m = findM3u8InText(html, baseHost)
                if (m != null) {
                    android.util.Log.d("EmbedStExtractor", "Found m3u8 in SvelteKit page HTML: $m")
                    return@withContext createVideoResult(m, referer)
                }
            }
        }

        // Pattern 5: AkSportsLiveProvider getStreams API resolution fallback
        val pathSegments = uri?.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
        if (pathSegments.size >= 3 && pathSegments[0] == "embed") {
            val source = pathSegments[1]
            val id = pathSegments.drop(2).firstOrNull { it != "1" && it != "2" } ?: pathSegments[2]
            android.util.Log.d("EmbedStExtractor", "Attempting AkSportsLiveProvider.resolveEmbedStream for source=$source, id=$id")
            try {
                val resolvedM3u8 = withTimeoutOrNull(5000) {
                    com.nexastream.app.providers.AkSportsLiveProvider.resolveEmbedStream(source, id)
                }
                if (!resolvedM3u8.isNullOrBlank()) {
                    android.util.Log.d("EmbedStExtractor", "Resolved m3u8 stream via AkSportsLiveProvider.resolveEmbedStream: $resolvedM3u8")
                    return@withContext createVideoResult(resolvedM3u8, referer)
                }
            } catch (e: Exception) {
                android.util.Log.w("EmbedStExtractor", "AkSportsLiveProvider.resolveEmbedStream failed: ${e.message}")
            }
            
            // Pattern 6: Direct API stream fetch on active hosts
            val apiHosts = listOf("https://streamed.pk", "https://embed.st")
            for (apiHost in apiHosts) {
                try {
                    val apiUrl = "$apiHost/api/stream/$source/$id"
                    val apiReq = Request.Builder()
                        .url(apiUrl)
                        .header("Referer", "$apiHost/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .build()
                    val apiResp = withTimeoutOrNull(3000) {
                        client.newCall(apiReq).execute()
                    } ?: continue
                    val apiBody = apiResp.body?.string().orEmpty()
                    
                    if (apiBody.isNotBlank()) {
                        val m = findM3u8InText(apiBody, apiHost)
                        if (m != null) {
                            android.util.Log.d("EmbedStExtractor", "Resolved m3u8 via API $apiUrl: $m")
                            return@withContext createVideoResult(m, referer)
                        }
                        
                        val decoded = com.nexastream.app.utils.SportsSecurityUtils.decodeObfuscatedString(apiBody)
                        val mDecoded = findM3u8InText(decoded, apiHost)
                        if (mDecoded != null) {
                            android.util.Log.d("EmbedStExtractor", "Resolved decoded m3u8 via API $apiUrl: $mDecoded")
                            return@withContext createVideoResult(mDecoded, referer)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("EmbedStExtractor", "API stream resolution failed for host $apiHost: ${e.message}")
                }
            }
        }

        throw Exception("Could not find video source in $link")
    }

    private fun findM3u8InText(text: String, baseHost: String): String? {
        if (text.isBlank()) return null
        
        val sanitized = text.replace("\\/", "/").replace("\\u002F", "/")
        
        val absoluteRegex = Regex("""(https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*)""")
        absoluteRegex.find(sanitized)?.let {
            return it.groupValues[1]
        }
        
        val relativeRegex = Regex("""(["'])(/[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*)\1""")
        relativeRegex.find(sanitized)?.let {
            val path = it.groupValues[2]
            return "$baseHost$path"
        }
        
        return null
    }

    private fun createVideoResult(rawM3u8Url: String, referer: String): Video {
        var finalUrl = rawM3u8Url
        if (!finalUrl.contains("token=")) {
            try {
                val domainPart = com.nexastream.app.utils.SportsSecurityUtils.getDomainSegment(finalUrl)
                val token = com.nexastream.app.utils.SportsSecurityUtils.generateToken(domainPart)
                finalUrl = if (finalUrl.contains("?")) "$finalUrl&${token.removePrefix("?")}" else "$finalUrl$token"
            } catch (_: Exception) {}
        }
        
        return Video(
            source = finalUrl,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to referer.trimEnd('/')
            ),
            type = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            maintainToken = true
        )
    }
}
