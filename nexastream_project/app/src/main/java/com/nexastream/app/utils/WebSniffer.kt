package com.nexastream.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nexastream.app.NexastreamApp
import com.nexastream.app.models.Video
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Universal WebView Stream Sniffer.
 * Intercepts network requests performed by embedded web players to catch video streams (.m3u8, .mp4, .mpd)
 * along with necessary HTTP request headers (Referer, Origin, User-Agent, Cookie, Sec-Fetch-*).
 */
class WebSniffer(private val context: Context = NexastreamApp.instance) {

    data class SniffResult(
        val videoUrl: String,
        val headers: Map<String, String>,
        val contentType: String? = null
    )

    companion object {
        private const val TAG = "WebSniffer"
        private const val DEFAULT_TIMEOUT_MS = 25000L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val MEDIA_REGEX = Regex(
            """(?i)\.(m3u8|mp4|mpd|m3u|m4s)(\?.*)?$""",
            RegexOption.IGNORE_CASE
        )

        private val IGNORED_DOMAINS = listOf(
            "google-analytics", "doubleclick", "googlesyndication", "popads", "popcash",
            "adsterra", "histats", "disqus", "facebook.com", "twitter.com", "admob", "analytics",
            "exoclick", "hilltopads", "clksite", "propellerads", "clickadu", "juicyads",
            "monetag", "outbrain", "taboola", "trafficjunky", "adity", "adcovery",
            "bet365", "1xbet", "yandex", "mgid", "zeroredirect"
        )

        private val PLAY_TRIGGER_SCRIPT = """
            (function() {
                if (window.__snifferInterval) return;

                function triggerPlayInContext(doc) {
                    try {
                        // 1. Play & unmute all video tags
                        var videos = doc.querySelectorAll('video');
                        videos.forEach(function(v) {
                            v.muted = true;
                            var p = v.play();
                            if (p && p.catch) p.catch(function(){});
                        });

                        // 2. Remove transparent clickjack overlay elements
                        var overlays = doc.querySelectorAll('div, a, span');
                        overlays.forEach(function(el) {
                            try {
                                var style = window.getComputedStyle(el);
                                if (style && (parseInt(style.zIndex) > 100 || style.position === 'fixed' || style.position === 'absolute')) {
                                    if (el.offsetWidth >= window.innerWidth * 0.8 && el.offsetHeight >= window.innerHeight * 0.8 && !el.querySelector('video')) {
                                        el.remove();
                                    }
                                }
                            } catch(e) {}
                        });

                        // 3. Click known video player play buttons
                        var selectors = [
                            '.play', '.play-btn', '.vjs-big-play-button',
                            'button[aria-label="Play"]', '#play', '#playbtn',
                            '.jw-display-icon', '.plyr__control--overlaid',
                            '.clickable', 'div[class*="play"]', 'button[class*="play"]',
                            'a[class*="play"]', '.play_button', '.btn-play', '#player',
                            '.clappr-play-wrapper', '.fp-play', '.vjs-play-control'
                        ];
                        selectors.forEach(function(sel) {
                            var elems = doc.querySelectorAll(sel);
                            elems.forEach(function(el) { try { el.click(); } catch(e){} });
                        });

                        // 4. Traverse Shadow DOM roots
                        var allElems = doc.querySelectorAll('*');
                        allElems.forEach(function(el) {
                            if (el.shadowRoot) {
                                triggerPlayInContext(el.shadowRoot);
                            }
                        });
                    } catch(e) {}
                }

                function scanFramesAndPlay() {
                    triggerPlayInContext(document);
                    try {
                        for (var i = 0; i < window.frames.length; i++) {
                            try {
                                if (window.frames[i].document) {
                                    triggerPlayInContext(window.frames[i].document);
                                }
                            } catch(e) {}
                        }
                    } catch(e) {}
                }

                window.__snifferInterval = setInterval(scanFramesAndPlay, 300);
                scanFramesAndPlay();
            })();
        """.trimIndent()
    }

    private val mutex = Mutex()

    /**
     * Sniffs a web page URL and returns a [Video] model if a media stream is intercepted.
     */
    suspend fun sniffToVideo(
        targetUrl: String,
        customHeaders: Map<String, String> = emptyMap(),
        customUserAgent: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        customMediaFilter: ((url: String) -> Boolean)? = null
    ): Video? {
        val sniffResult = sniff(targetUrl, customHeaders, customUserAgent, timeoutMs, customMediaFilter)
            ?: return null

        return Video(
            source = sniffResult.videoUrl,
            headers = sniffResult.headers,
            subtitles = emptyList(),
            maintainToken = true
        )
    }

    /**
     * Sniffs a target web page and returns the intercepted [SniffResult].
     */
    suspend fun sniff(
        targetUrl: String,
        customHeaders: Map<String, String> = emptyMap(),
        customUserAgent: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        customMediaFilter: ((url: String) -> Boolean)? = null
    ): SniffResult? = mutex.withLock {
        Log.d(TAG, "[Sniffer] Starting sniff for target URL: $targetUrl")

        val resultDeferred = CompletableDeferred<SniffResult?>()
        var webView: WebView? = null

        withContext(Dispatchers.Main) {
            try {
                webView = WebView(context).apply {
                    configureSettings(customUserAgent)
                    
                    val userAgent = customUserAgent ?: settings.userAgentString

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            Log.d(TAG, "[Sniffer] Blocked popup window creation attempt")
                            return false
                        }
                    }

                    webViewClient = object : WebViewClient() {

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            Log.d(TAG, "[Sniffer] Page loading started: $url")
                            view?.evaluateJavascript(PLAY_TRIGGER_SCRIPT, null)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request != null) {
                                val url = request.url.toString()
                                if (isIgnoredUrl(url)) {
                                    return super.shouldInterceptRequest(view, request)
                                }

                                val matchesMedia = customMediaFilter?.invoke(url)
                                    ?: isMediaUrl(url)

                                if (matchesMedia) {
                                    Log.i(TAG, "🟢 [Sniffer] INTERCEPTED MEDIA STREAM URL: $url")

                                    val requestHeaders = mutableMapOf<String, String>()
                                    
                                    // Copy headers supplied by the WebView request
                                    request.requestHeaders?.forEach { (key, value) ->
                                        requestHeaders[key] = value
                                    }

                                    // Ensure essential headers exist
                                    if (!requestHeaders.containsKey("User-Agent")) {
                                        requestHeaders["User-Agent"] = userAgent
                                    }

                                    val mainUri = Uri.parse(targetUrl)
                                    val domainReferer = "${mainUri.scheme}://${mainUri.host}/"
                                    if (!requestHeaders.containsKey("Referer")) {
                                        requestHeaders["Referer"] = domainReferer
                                    }
                                    if (!requestHeaders.containsKey("Origin")) {
                                        requestHeaders["Origin"] = "${mainUri.scheme}://${mainUri.host}"
                                    }

                                    // Add cookies if available
                                    runCatching {
                                        val cookies = CookieManager.getInstance().getCookie(url)
                                            ?: CookieManager.getInstance().getCookie(targetUrl)
                                        if (!cookies.isNullOrBlank()) {
                                            requestHeaders["Cookie"] = cookies
                                        }
                                    }

                                    val sniffResult = SniffResult(
                                        videoUrl = url,
                                        headers = requestHeaders
                                    )

                                    if (!resultDeferred.isCompleted) {
                                        resultDeferred.complete(sniffResult)
                                    }

                                    // Return empty response to abort downloading heavy video data in WebView
                                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                } else if (isStaticResourceUrl(url)) {
                                    // Abort non-essential static asset downloads to save bandwidth and accelerate sniffing
                                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                } else {
                                    Log.d(TAG, "[Sniffer] Request: $url")
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(TAG, "[Sniffer] Page finished loading: $url")
                            if (url != null && (url.contains("cf-mitigated") || url.contains("challenge"))) {
                                Log.w(TAG, "⚠️ [Sniffer] Cloudflare challenge detected on URL: $url")
                            }
                            view?.evaluateJavascript(PLAY_TRIGGER_SCRIPT, null)
                        }
                    }

                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(targetUrl, customHeaders)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Sniffer] Failed to create or load WebView", e)
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(null)
                }
            }
        }

        val result = withTimeoutOrNull(timeoutMs) {
            resultDeferred.await()
        }

        withContext(Dispatchers.Main) {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }

        if (result == null) {
            Log.w(TAG, "[Sniffer] Sniff timed out or found no media stream for: $targetUrl")
        } else {
            Log.i(TAG, "SUCCESS: [Sniffer] Captured video source: ${result.videoUrl}")
        }

        return@withLock result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configureSettings(customUserAgent: String?) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = customUserAgent ?: DEFAULT_USER_AGENT
        }
    }

    private fun isMediaUrl(url: String): Boolean {
        val cleanUrl = url.lowercase()
        if (cleanUrl.contains(".png") || cleanUrl.contains(".jpg") || cleanUrl.contains(".jpeg") ||
            cleanUrl.contains(".gif") || cleanUrl.contains(".css") || cleanUrl.contains(".svg") ||
            cleanUrl.contains(".woff") || cleanUrl.contains(".ttf") || cleanUrl.contains(".ico")
        ) {
            return false
        }
        return cleanUrl.contains(".m3u8") ||
                cleanUrl.contains(".mp4") ||
                cleanUrl.contains(".mpd") ||
                cleanUrl.contains(".m3u") ||
                cleanUrl.contains(".m4s") ||
                cleanUrl.contains("/hls/") ||
                cleanUrl.contains("/dash/") ||
                cleanUrl.contains("master.m3u8") ||
                cleanUrl.contains("index.m3u8") ||
                cleanUrl.contains("playlist.m3u8") ||
                cleanUrl.contains("chunklist") ||
                cleanUrl.contains("tracks-v") ||
                MEDIA_REGEX.containsMatchIn(url)
    }

    private fun isStaticResourceUrl(url: String): Boolean {
        val cleanUrl = url.lowercase()
        return cleanUrl.endsWith(".png") || cleanUrl.endsWith(".jpg") || cleanUrl.endsWith(".jpeg") ||
                cleanUrl.endsWith(".gif") || cleanUrl.endsWith(".svg") || cleanUrl.endsWith(".ico") ||
                cleanUrl.endsWith(".css") || cleanUrl.endsWith(".woff") || cleanUrl.endsWith(".woff2") ||
                cleanUrl.endsWith(".ttf") || cleanUrl.endsWith(".eot") || cleanUrl.contains("analytics.js") ||
                cleanUrl.contains("gtag/js")
    }

    private fun isIgnoredUrl(url: String): Boolean {
        return IGNORED_DOMAINS.any { url.contains(it, ignoreCase = true) }
    }
}
