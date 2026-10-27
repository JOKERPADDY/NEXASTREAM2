package com.nexastream.app.utils

import android.util.Log
import android.webkit.CookieManager
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import com.nexastream.app.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import com.nexastream.app.NexastreamApp
import com.nexastream.app.R
import android.os.Build

object NetworkClient {

    private const val TAG = "Cine24hBypass"
    
    // User-Agent Mobile standard per massima compatibilità con Cloudflare
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    private val cookieManager by lazy { CookieManager.getInstance() }

    val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                cookieManager.setCookie(url.toString(), cookie.toString())
            }
            cookieManager.flush()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieString = cookieManager.getCookie(url.toString()) ?: return emptyList()
            return cookieString.split(";").mapNotNull {
                Cookie.parse(url, it.trim())
            }
        }
    }

    private val loggingInterceptor by lazy {
        HttpLoggingInterceptor { message ->
            Log.d(TAG, "[OkHttp] $message")
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
    }

    val sharedConnectionPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    val default: OkHttpClient by lazy { buildClient(DnsResolver.doh) }
    val systemDns: OkHttpClient by lazy { buildClient(Dns.SYSTEM) }
    val noRedirects: OkHttpClient by lazy { buildClient(DnsResolver.doh) { it.followRedirects(false).followSslRedirects(false) } }
    val minimal: OkHttpClient by lazy { buildClient(Dns.SYSTEM, minimal = true) }

    val trustAll: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        buildClient(DnsResolver.doh) {
            it.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
              .hostnameVerifier { _, _ -> true }
        }
    }

    /**
     * Client that uses System DNS and trusts all certificates. 
     * Best for unstable streaming mirrors that fail with DoH or SSL handshakes.
     */
    val compatibleTrustAll: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        buildClient(Dns.SYSTEM) {
            it.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
              .hostnameVerifier { _, _ -> true }
        }
    }

    private fun buildClient(dns: Dns, minimal: Boolean = false, customizer: ((OkHttpClient.Builder) -> Unit)? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                var originalRequest = chain.request()
                val originalUrl = originalRequest.url
                
                // 1. Handle Artwork Request Headers (Fragments to Real Headers)
                val artworkHeaders = ArtworkRequestHeaders.headersFor(originalUrl)
                if (artworkHeaders.isNotEmpty()) {
                    val newUrl = ArtworkRequestHeaders.stripHeaders(originalUrl)
                    val newRequestBuilder = originalRequest.newBuilder().url(newUrl)
                    artworkHeaders.forEach { (key, value) ->
                        newRequestBuilder.header(key, value)
                    }
                    originalRequest = newRequestBuilder.build()
                }

                if (minimal) return@addInterceptor chain.proceed(originalRequest)

                val requestBuilder = originalRequest.newBuilder()
                val isCorsRequest = originalRequest.header("Sec-Fetch-Mode") == "cors" ||
                        originalRequest.header("Sec-Fetch-Dest") == "empty"
                
                // Only set default headers if not already provided by the caller (e.g. an extractor)
                if (originalRequest.header("User-Agent") == null)
                    requestBuilder.header("User-Agent", USER_AGENT)
                if (originalRequest.header("Accept") == null)
                    requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                if (originalRequest.header("Accept-Encoding") == null)
                    requestBuilder.header("Accept-Encoding", "identity")
                if (originalRequest.header("Accept-Language") == null)
                    requestBuilder.header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                if (!isCorsRequest && originalRequest.header("Sec-Fetch-Dest") == null)
                    requestBuilder.header("Sec-Fetch-Dest", "document")
                if (!isCorsRequest && originalRequest.header("Sec-Fetch-Mode") == null)
                    requestBuilder.header("Sec-Fetch-Mode", "navigate")
                if (!isCorsRequest && originalRequest.header("Sec-Fetch-Site") == null)
                    requestBuilder.header("Sec-Fetch-Site", "none")
                if (!isCorsRequest && originalRequest.header("Upgrade-Insecure-Requests") == null)
                    requestBuilder.header("Upgrade-Insecure-Requests", "1")
                chain.proceed(requestBuilder.build())
            }
            .connectionPool(sharedConnectionPool)
            .cookieJar(cookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(dns)

        // Modern and compatible TLS configuration
        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
            .build()
        builder.connectionSpecs(listOf(spec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))

        // SSL compatibility for Android < 9.0 (API 28) and ISRG Root X1 for Let's Encrypt
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            try {
                // On older Android we manually inject the Let's Encrypt ISRG Root X1 certificate
                // and enable older TLS versions just in case.
                
                val cf = CertificateFactory.getInstance("X.509")
                val certInput = runCatching {
                    NexastreamApp.instance.resources.openRawResource(R.raw.isrg_root_x1)
                }.getOrNull()
                if (certInput == null) {
                    Log.w(TAG, "ISRG Root X1 certificate asset is missing; continuing without it")
                } else {
                    val isrgCert = certInput.use { cf.generateCertificate(it) }

                    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                        load(null, null)
                        setCertificateEntry("isrg_root_x1", isrgCert)
                    }

                    // Initialize TMF with our certificate
                    val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
                    val tmf = TrustManagerFactory.getInstance(tmfAlgorithm).apply {
                        init(keyStore)
                    }

                    // Get system TMF for regular certificates
                    val systemTmf = TrustManagerFactory.getInstance(tmfAlgorithm).apply {
                        init(null as KeyStore?)
                    }

                    val systemTrustManager = systemTmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
                    val customTrustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

                    // Custom trust manager that trusts both system and our bundled certificate
                    val combinedTrustManager = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                            systemTrustManager.checkClientTrusted(chain, authType)
                        }

                        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                            try {
                                systemTrustManager.checkServerTrusted(chain, authType)
                            } catch (e: Exception) {
                                try {
                                    customTrustManager.checkServerTrusted(chain, authType)
                                } catch (e2: Exception) {
                                    // Fallback to system check as a last resort, throwing if it fails
                                    systemTrustManager.checkServerTrusted(chain, authType)
                                }
                            }
                        }

                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                            return systemTrustManager.acceptedIssuers + customTrustManager.acceptedIssuers
                        }
                    }

                    val sslContext = SSLContext.getInstance("TLS").apply {
                        init(null, arrayOf(combinedTrustManager), SecureRandom())
                    }

                    builder.sslSocketFactory(sslContext.socketFactory, combinedTrustManager)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up SSL compatibility: ${e.message}")
            }
        }

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(loggingInterceptor)
        }
        customizer?.invoke(builder)
        return builder.build()
    }
}
