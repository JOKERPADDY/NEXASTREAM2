package com.nexastream.app.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    movieTitle: String,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val videoId = remember(trailerUrl) { 
        if (!trailerUrl.isNullOrBlank()) extractYoutubeVideoId(trailerUrl) else null 
    }

    val searchQuery = remember(movieTitle) {
        Uri.encode("$movieTitle official trailer")
    }

    val htmlContent = remember(videoId, searchQuery, isMuted) {
        val muteVal = if (isMuted) 1 else 0
        val iframeSrc = if (!videoId.isNullOrBlank()) {
            "https://www.youtube.com/embed/$videoId?autoplay=1&mute=$muteVal&controls=0&loop=1&playlist=$videoId&enablejsapi=1&rel=0&showinfo=0&modestbranding=1&playsinline=1&vq=small"
        } else {
            "https://www.youtube.com/embed?listType=search&list=$searchQuery&autoplay=1&mute=$muteVal&controls=0&enablejsapi=1&rel=0&showinfo=0&modestbranding=1&playsinline=1&vq=small"
        }

        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                * { margin: 0; padding: 0; background-color: #000000; overflow: hidden; }
                html, body { width: 100%; height: 100%; }
                .iframe-container { position: relative; width: 100%; height: 100%; overflow: hidden; }
                iframe { position: absolute; top: 50%; left: 50%; width: 140%; height: 140%; transform: translate(-50%, -50%); border: none; pointer-events: none; }
            </style>
        </head>
        <body>
            <div class="iframe-container">
                <iframe src="$iframeSrc" allow="autoplay; encrypted-media">
                </iframe>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://www.youtube.com", htmlContent, "text/html", "UTF-8", null)
            }
        },
        update = { _ -> }
    )
}

fun extractYoutubeVideoId(url: String): String? {
    if (url.isBlank()) return null
    
    // Direct 11-char ID
    if (url.length == 11 && !url.contains("/") && !url.contains(".")) {
        return url
    }

    val patterns = listOf(
        "(?<=v=)[a-zA-Z0-9_-]{11}",
        "(?<=youtu.be/)[a-zA-Z0-9_-]{11}",
        "(?<=embed/)[a-zA-Z0-9_-]{11}",
        "[a-zA-Z0-9_-]{11}"
    )

    for (patternStr in patterns) {
        val pattern = java.util.regex.Pattern.compile(patternStr)
        val matcher = pattern.matcher(url)
        if (matcher.find()) {
            return matcher.group()
        }
    }

    return null
}
