package com.nexastream.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nexastream.app.NexastreamApp
import com.nexastream.app.models.Video
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Universal WebView Stream Sniffer.
 * Intercepts network requests performed by embedded web players to catch video streams (.m3u8, .mp4, .mpd)
 * and subtitle tracks (.vtt, .srt, .ass, .ttml) along with necessary HTTP request headers (Referer, Origin, User-Agent, Cookie, Sec-Fetch-*).
 * Supports concurrent pooling, fetch/XHR/postMessage JS monkey-patching, exact Referer headers,
 * and Cloudflare interactive challenge fallbacks.
 */
class WebSniffer(private val context: Context = NexastreamApp.instance) {

    data class SniffResult(
        val videoUrl: String,
        val headers: Map<String, String>,
        val contentType: String? = null,
        val subtitles: List<Video.Subtitle> = emptyList()
    )

    inner class SnifferBridge(
        private val userAgent: String,
        private val targetUrl: String,
        private val customMediaFilter: ((String) -> Boolean)?,
        private val interceptedSubtitles: CopyOnWriteArrayList<Video.Subtitle>,
        private val onMediaFound: (SniffResult) -> Unit
    ) {
        @JavascriptInterface
        fun onMediaDetected(url: String?) {
            if (url.isNullOrBlank() || isIgnoredUrl(url)) return
            val matchesMedia = customMediaFilter?.invoke(url) ?: isMediaUrl(url)
            if (matchesMedia) {
                Log.i(TAG, "🟢 [Sniffer Bridge] INTERCEPTED MEDIA VIA JS HOOK: $url")
                val headers = buildHeaders(url, targetUrl, userAgent, emptyMap())
                onMediaFound(
                    SniffResult(
                        videoUrl = url,
                        headers = headers,
                        subtitles = interceptedSubtitles.toList()
                    )
                )
            }
        }

        @JavascriptInterface
        fun onSubtitleDetected(url: String?, label: String?, language: String?) {
            if (url.isNullOrBlank() || isIgnoredUrl(url)) return
            if (isSubtitleUrl(url)) {
                val subLabel = label?.takeIf { it.isNotBlank() } ?: inferSubtitleLabel(url)
                val sub = Video.Subtitle(
                    label = subLabel,
                    file = url,
                    language = language?.takeIf { it.isNotBlank() } ?: subLabel.lowercase()
                )
                if (interceptedSubtitles.none { it.file == url }) {
                    interceptedSubtitles.add(sub)
                    Log.i(TAG, "🟢 [Sniffer Bridge] INTERCEPTED SUBTITLE VIA JS HOOK: $url ($subLabel)")
                }
            }
        }
    }

    companion object {
        private const val TAG = "WebSniffer"
        private const val DEFAULT_TIMEOUT_MS = 25000L
        private val DEFAULT_USER_AGENT = NetworkClient.USER_AGENT

        // Pooled concurrency limit allowing up to 3 parallel sniff operations
        private val poolSemaphore = Semaphore(3)

        private val MEDIA_REGEX = Regex(
            """(?i)\.(m3u8|mp4|mpd|m3u|m4s|webm|mkv|flv)(\?.*)?$""",
            RegexOption.IGNORE_CASE
        )

        private val SUBTITLE_REGEX = Regex(
            """(?i)\.(vtt|srt|ass|ttml)(\?.*)?$""",
            RegexOption.IGNORE_CASE
        )

        private val IGNORED_DOMAINS = listOf(
            "google-analytics", "doubleclick", "googlesyndication", "popads", "popcash",
            "adsterra", "histats", "disqus", "facebook.com", "twitter.com", "admob", "analytics",
            "exoclick", "hilltopads", "clksite", "propellerads", "clickadu", "juicyads",
            "monetag", "outbrain", "taboola", "trafficjunky", "adity", "adcovery",
            "bet365", "1xbet", "yandex", "mgid", "zeroredirect"
        )

        private val FETCH_XHR_HOOK_SCRIPT = """
            (function() {
                if (window.__snifferHooksInjected) return;
                window.__snifferHooksInjected = true;

                function checkUrl(url) {
                    if (!url || typeof url !== 'string') return;

                    // 1. Check Subtitle Formats
                    if (url.match(/\.(vtt|srt|ass|ttml)(\?.*)?$/i)) {
                        try {
                            if (window.NexaSnifferBridge && window.NexaSnifferBridge.onSubtitleDetected) {
                                window.NexaSnifferBridge.onSubtitleDetected(url, "", "");
                            }
                        } catch(e){}
                        return;
                    }

                    // 2. Check Media Stream Formats & Query Patterns
                    if (url.match(/\.(m3u8|mp4|mpd|m3u|m4s|webm|mkv|flv)(\?.*)?$/i) || 
                        url.indexOf('/hls/') !== -1 || url.indexOf('/dash/') !== -1 || 
                        url.indexOf('master.m3u8') !== -1 || url.indexOf('index.m3u8') !== -1 || 
                        url.indexOf('playlist.m3u8') !== -1 || url.indexOf('chunklist') !== -1 ||
                        url.indexOf('file=') !== -1 || url.indexOf('source=') !== -1 || url.indexOf('stream=') !== -1) {
                        try {
                            if (window.NexaSnifferBridge && window.NexaSnifferBridge.onMediaDetected) {
                                window.NexaSnifferBridge.onMediaDetected(url);
                            }
                        } catch(e){}
                    }
                }

                // 1. Hook XMLHttpRequest
                try {
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        checkUrl(url);
                        return origOpen.apply(this, arguments);
                    };
                } catch(e){}

                // 2. Hook window.fetch
                try {
                    if (window.fetch) {
                        var origFetch = window.fetch;
                        window.fetch = function(input, init) {
                            var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                            checkUrl(url);
                            return origFetch.apply(this, arguments);
                        };
                    }
                } catch(e){}

                // 3. Hook HTMLMediaElement src
                try {
                    var origSrcDescriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                    if (origSrcDescriptor && origSrcDescriptor.set) {
                        Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                            get: origSrcDescriptor.get,
                            set: function(val) {
                                checkUrl(val);
                                return origSrcDescriptor.set.call(this, val);
                            },
                            configurable: true
                        });
                    }
                } catch(e){}

                // 4. Hook window.postMessage for player event broadcasts
                try {
                    window.addEventListener('message', function(event) {
                        if (event && event.data) {
                            var dataStr = typeof event.data === 'string' ? event.data : JSON.stringify(event.data);
                            checkUrl(dataStr);
                        }
                    });
                } catch(e){}

                // 5. Hook console.log for embedded player output
                try {
                    var origLog = console.log;
                    console.log = function() {
                        for (var i = 0; i < arguments.length; i++) {
                            if (typeof arguments[i] === 'string') checkUrl(arguments[i]);
                        }
                        return origLog.apply(this, arguments);
                    };
                } catch(e){}
            })();
        """.trimIndent()

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
            subtitles = sniffResult.subtitles,
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
    ): SniffResult? = poolSemaphore.withPermit {
        Log.d(TAG, "[Sniffer] Starting sniff for target URL: $targetUrl")

        val resultDeferred = CompletableDeferred<SniffResult?>()
        val interceptedSubtitles = CopyOnWriteArrayList<Video.Subtitle>()
        var webView: WebView? = null
        var isChallengeDetected = false

        withContext(Dispatchers.Main) {
            try {
                webView = WebView(context).apply {
                    configureSettings(customUserAgent)
                    
                    val userAgent = customUserAgent ?: settings.userAgentString

                    // Register Javascript interface for fetch/XHR hook sniffing and subtitle detection
                    addJavascriptInterface(
                        SnifferBridge(userAgent, targetUrl, customMediaFilter, interceptedSubtitles) { sniffResult ->
                            if (!resultDeferred.isCompleted) {
                                resultDeferred.complete(sniffResult)
                            }
                        },
                        "NexaSnifferBridge"
                    )

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
                            view?.evaluateJavascript(FETCH_XHR_HOOK_SCRIPT, null)
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

                                if (url.contains("cf-mitigated") || url.contains("challenge-running") || url.contains("challenges.cloudflare.com")) {
                                    isChallengeDetected = true
                                    Log.w(TAG, "⚠️ [Sniffer] Cloudflare challenge URL intercepted: $url")
                                }

                                // Check Subtitle Interception
                                if (isSubtitleUrl(url)) {
                                    val subLabel = inferSubtitleLabel(url)
                                    val sub = Video.Subtitle(
                                        label = subLabel,
                                        file = url,
                                        language = subLabel.lowercase()
                                    )
                                    if (interceptedSubtitles.none { it.file == url }) {
                                        interceptedSubtitles.add(sub)
                                        Log.i(TAG, "🟢 [Sniffer] INTERCEPTED SUBTITLE URL: $url ($subLabel)")
                                    }
                                }

                                val matchesMedia = customMediaFilter?.invoke(url)
                                    ?: isMediaUrl(url)

                                if (matchesMedia) {
                                    Log.i(TAG, "🟢 [Sniffer] INTERCEPTED MEDIA STREAM URL: $url")

                                    val requestHeaders = buildHeaders(
                                        mediaUrl = url,
                                        targetUrl = targetUrl,
                                        userAgent = userAgent,
                                        requestHeadersFromWebView = request.requestHeaders ?: emptyMap()
                                    )

                                    val sniffResult = SniffResult(
                                        videoUrl = url,
                                        headers = requestHeaders,
                                        subtitles = interceptedSubtitles.toList()
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
                                isChallengeDetected = true
                                Log.w(TAG, "⚠️ [Sniffer] Cloudflare challenge detected on URL: $url")
                            }

                            view?.evaluateJavascript(FETCH_XHR_HOOK_SCRIPT, null)
                            view?.evaluateJavascript(PLAY_TRIGGER_SCRIPT, null)

                            view?.evaluateJavascript("(function(){ return document.body ? document.body.innerText : ''; })();") { bodyText ->
                                if (bodyText != null && (bodyText.contains("Just a moment...") || bodyText.contains("Checking your browser") || bodyText.contains("cf-browser-verification"))) {
                                    isChallengeDetected = true
                                    Log.w(TAG, "⚠️ [Sniffer] Cloudflare challenge DOM text detected")
                                }
                            }
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

        var result = withTimeoutOrNull(timeoutMs) {
            resultDeferred.await()
        }

        withContext(Dispatchers.Main) {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }

        // Fallback to interactive WebViewResolver if a Cloudflare challenge blocked the background sniff
        if (result == null && isChallengeDetected) {
            Log.w(TAG, "⚠️ [Sniffer] Background sniff failed due to Cloudflare challenge. Triggering interactive WebViewResolver fallback...")
            runCatching {
                val resolverResult = WebViewResolver(context).getResult(
                    url = targetUrl,
                    headers = customHeaders,
                    showImmediately = true
                )
                if (resolverResult.html.length > 500 && !resolverResult.html.contains("User cancelled")) {
                    Log.i(TAG, "🟢 [Sniffer] Interactive challenge completed via WebViewResolver! Retrying background sniff...")
                    // Retry quick background sniff with freshly acquired clearance cookies
                    result = retryBackgroundSniff(targetUrl, customHeaders, customUserAgent, customMediaFilter)
                }
            }.onFailure { e ->
                Log.e(TAG, "[Sniffer] Interactive WebViewResolver fallback failed", e)
            }
        }

        if (result == null) {
            Log.w(TAG, "[Sniffer] Sniff timed out or found no media stream for: $targetUrl")
        } else {
            Log.i(TAG, "SUCCESS: [Sniffer] Captured video source: ${result.videoUrl} (Subtitles captured: ${result.subtitles.size})")
        }

        return@withPermit result
    }

    private suspend fun retryBackgroundSniff(
        targetUrl: String,
        customHeaders: Map<String, String>,
        customUserAgent: String?,
        customMediaFilter: ((url: String) -> Boolean)?
    ): SniffResult? {
        val retryDeferred = CompletableDeferred<SniffResult?>()
        val interceptedSubtitles = CopyOnWriteArrayList<Video.Subtitle>()
        var retryWebView: WebView? = null

        withContext(Dispatchers.Main) {
            try {
                retryWebView = WebView(context).apply {
                    configureSettings(customUserAgent)
                    val userAgent = customUserAgent ?: settings.userAgentString

                    addJavascriptInterface(
                        SnifferBridge(userAgent, targetUrl, customMediaFilter, interceptedSubtitles) { sniffResult ->
                            if (!retryDeferred.isCompleted) retryDeferred.complete(sniffResult)
                        },
                        "NexaSnifferBridge"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            view?.evaluateJavascript(FETCH_XHR_HOOK_SCRIPT, null)
                            view?.evaluateJavascript(PLAY_TRIGGER_SCRIPT, null)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request != null) {
                                val url = request.url.toString()
                                if (!isIgnoredUrl(url)) {
                                    if (isSubtitleUrl(url)) {
                                        val subLabel = inferSubtitleLabel(url)
                                        val sub = Video.Subtitle(
                                            label = subLabel,
                                            file = url,
                                            language = subLabel.lowercase()
                                        )
                                        if (interceptedSubtitles.none { it.file == url }) {
                                            interceptedSubtitles.add(sub)
                                        }
                                    }

                                    val matchesMedia = customMediaFilter?.invoke(url) ?: isMediaUrl(url)
                                    if (matchesMedia) {
                                        val headers = buildHeaders(url, targetUrl, userAgent, request.requestHeaders ?: emptyMap())
                                        if (!retryDeferred.isCompleted) {
                                            retryDeferred.complete(
                                                SniffResult(
                                                    videoUrl = url,
                                                    headers = headers,
                                                    subtitles = interceptedSubtitles.toList()
                                                )
                                            )
                                        }
                                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(FETCH_XHR_HOOK_SCRIPT, null)
                            view?.evaluateJavascript(PLAY_TRIGGER_SCRIPT, null)
                        }
                    }

                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(targetUrl, customHeaders)
                }
            } catch (e: Exception) {
                if (!retryDeferred.isCompleted) retryDeferred.complete(null)
            }
        }

        val retryResult = withTimeoutOrNull(15000L) { retryDeferred.await() }

        withContext(Dispatchers.Main) {
            runCatching {
                retryWebView?.stopLoading()
                retryWebView?.destroy()
                retryWebView = null
            }
        }

        return retryResult
    }

    private fun buildHeaders(
        mediaUrl: String,
        targetUrl: String,
        userAgent: String,
        requestHeadersFromWebView: Map<String, String>
    ): Map<String, String> {
        val requestHeaders = mutableMapOf<String, String>()
        requestHeadersFromWebView.forEach { (key, value) ->
            requestHeaders[key] = value
        }

        if (!requestHeaders.containsKey("User-Agent")) {
            requestHeaders["User-Agent"] = userAgent
        }

        // Prefer exact targetUrl as Referer
        if (!requestHeaders.containsKey("Referer")) {
            requestHeaders["Referer"] = targetUrl
        }

        val mainUri = runCatching { Uri.parse(targetUrl) }.getOrNull()
        if (mainUri != null && !requestHeaders.containsKey("Origin")) {
            requestHeaders["Origin"] = "${mainUri.scheme}://${mainUri.host}"
        }

        // Synthesize standard browser fetch headers for CDN compliance
        if (!requestHeaders.containsKey("Accept")) {
            requestHeaders["Accept"] = "*/*"
        }
        if (!requestHeaders.containsKey("Sec-Fetch-Dest")) {
            requestHeaders["Sec-Fetch-Dest"] = "empty"
        }
        if (!requestHeaders.containsKey("Sec-Fetch-Mode")) {
            requestHeaders["Sec-Fetch-Mode"] = "cors"
        }
        if (!requestHeaders.containsKey("Sec-Fetch-Site")) {
            requestHeaders["Sec-Fetch-Site"] = "cross-site"
        }

        // Add cookies if available
        runCatching {
            val cookies = CookieManager.getInstance().getCookie(mediaUrl)
                ?: CookieManager.getInstance().getCookie(targetUrl)
            if (!cookies.isNullOrBlank()) {
                requestHeaders["Cookie"] = cookies
            }
        }

        return requestHeaders
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
            cleanUrl.contains(".woff") || cleanUrl.contains(".ttf") || cleanUrl.contains(".ico") ||
            cleanUrl.contains(".vtt") || cleanUrl.contains(".srt")
        ) {
            return false
        }
        return cleanUrl.contains(".m3u8") ||
                cleanUrl.contains(".mp4") ||
                cleanUrl.contains(".mpd") ||
                cleanUrl.contains(".m3u") ||
                cleanUrl.contains(".m4s") ||
                cleanUrl.contains(".webm") ||
                cleanUrl.contains(".mkv") ||
                cleanUrl.contains(".flv") ||
                cleanUrl.contains("/hls/") ||
                cleanUrl.contains("/dash/") ||
                cleanUrl.contains("master.m3u8") ||
                cleanUrl.contains("index.m3u8") ||
                cleanUrl.contains("playlist.m3u8") ||
                cleanUrl.contains("chunklist") ||
                cleanUrl.contains("tracks-v") ||
                cleanUrl.contains("file=") ||
                cleanUrl.contains("source=") ||
                cleanUrl.contains("stream=") ||
                MEDIA_REGEX.containsMatchIn(url)
    }

    private fun isSubtitleUrl(url: String): Boolean {
        val cleanUrl = url.lowercase()
        return cleanUrl.contains(".vtt") ||
                cleanUrl.contains(".srt") ||
                cleanUrl.contains(".ass") ||
                cleanUrl.contains(".ttml") ||
                SUBTITLE_REGEX.containsMatchIn(url)
    }

    private fun inferSubtitleLabel(url: String): String {
        val fileName = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }.getOrDefault("")
        return when {
            fileName.contains("eng", ignoreCase = true) || fileName.contains("en", ignoreCase = true) -> "English"
            fileName.contains("spa", ignoreCase = true) || fileName.contains("es", ignoreCase = true) -> "Spanish"
            fileName.contains("fre", ignoreCase = true) || fileName.contains("fr", ignoreCase = true) -> "French"
            fileName.contains("ger", ignoreCase = true) || fileName.contains("de", ignoreCase = true) -> "German"
            fileName.contains("ita", ignoreCase = true) || fileName.contains("it", ignoreCase = true) -> "Italian"
            fileName.isNotBlank() -> fileName.substringBeforeLast(".")
            else -> "Subtitle"
        }
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
