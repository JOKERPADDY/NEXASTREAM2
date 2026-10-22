package com.nexastream.app.activities.tools

import android.webkit.JavascriptInterface
import android.util.Log

class SnifferJsBridge(
    private val onMediaDetectedListener: (url: String, mimeType: String, headersJson: String) -> Unit
) {

    @JavascriptInterface
    fun onMediaFound(url: String?, mimeType: String?, headersJson: String?) {
        if (!url.isNullOrEmpty()) {
            Log.d(TAG, "SnifferJsBridge detected media: $url")
            onMediaDetectedListener(url, mimeType.orEmpty(), headersJson.orEmpty())
        }
    }

    companion object {
        private const val TAG = "SnifferJsBridge"
        const val BRIDGE_NAME = "NexaStreamSnifferBridge"

        val JS_INJECTION_SCRIPT = """
            (function() {
                if (window.__nexaSnifferInjected) return;
                window.__nexaSnifferInjected = true;

                function reportMedia(url, mimeType) {
                    if (!url || typeof url !== 'string') return;
                    if (url.indexOf('.m3u8') !== -1 || url.indexOf('.mp4') !== -1 || 
                        url.indexOf('.mpd') !== -1 || (mimeType && mimeType.indexOf('video') !== -1)) {
                        try {
                            window.${BRIDGE_NAME}.onMediaFound(url, mimeType || '', '');
                        } catch(e) {}
                    }
                }

                // Override fetch
                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        var args = arguments;
                        if (args && args[0]) {
                            var url = (typeof args[0] === 'string') ? args[0] : args[0].url;
                            reportMedia(url, '');
                        }
                        return origFetch.apply(this, args);
                    };
                }

                // Override XMLHttpRequest
                var origOpen = XMLHttpRequest.prototype.open;
                if (origOpen) {
                    XMLHttpRequest.prototype.open = function(method, url) {
                        reportMedia(url, '');
                        return origOpen.apply(this, arguments);
                    };
                }

                // Observe video tag additions
                document.addEventListener('DOMNodeInserted', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO' && e.target.src) {
                        reportMedia(e.target.src, 'video/mp4');
                    }
                }, false);
            })();
        """.trimIndent()
    }
}
