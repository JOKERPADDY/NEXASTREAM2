package com.nexastream.app.activities.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nexastream.app.R
import com.nexastream.app.utils.AppLanguageManager
import com.nexastream.app.utils.ThemeManager
import com.nexastream.app.utils.UserPreferences

class ResourceSnifferActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_target_url"
        const val EXTRA_DETECTED_STREAM_URL = "extra_detected_stream_url"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var playButton: Button
    private lateinit var cancelButton: Button

    private var detectedStreamUrl: String? = null

    private val targetUrl: String by lazy {
        intent.getStringExtra(EXTRA_URL).orEmpty()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resource_sniffer)

        webView = findViewById(R.id.sniffer_webview)
        progressBar = findViewById(R.id.sniffer_progress)
        statusText = findViewById(R.id.sniffer_status)
        playButton = findViewById(R.id.sniffer_play)
        cancelButton = findViewById(R.id.sniffer_cancel)

        cancelButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        playButton.setOnClickListener {
            detectedStreamUrl?.let { url ->
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_DETECTED_STREAM_URL, url)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        }

        setupWebView()
        if (targetUrl.isNotEmpty()) {
            webView.loadUrl(targetUrl)
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        val snifferBridge = SnifferJsBridge { url, _, _ ->
            runOnUiThread {
                if (detectedStreamUrl == null) {
                    detectedStreamUrl = url
                    statusText.text = getString(R.string.resource_sniffer_streams_found, 1)
                    playButton.isEnabled = true
                }
            }
        }

        webView.addJavascriptInterface(snifferBridge, SnifferJsBridge.BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(SnifferJsBridge.JS_INJECTION_SCRIPT, null)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.toString()?.let { reqUrl ->
                    if (reqUrl.contains(".m3u8") || reqUrl.contains(".mp4") || reqUrl.contains(".mpd")) {
                        runOnUiThread {
                            if (detectedStreamUrl == null) {
                                detectedStreamUrl = reqUrl
                                statusText.text = getString(R.string.resource_sniffer_streams_found, 1)
                                playButton.isEnabled = true
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
