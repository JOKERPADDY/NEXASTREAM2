package com.nexastream.app.utils

import android.net.Uri
import android.util.Base64
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.nexastream.app.database.AppDatabase
import kotlinx.coroutines.runBlocking

@UnstableApi
class HeaderInterceptingDataSource(
    private val baseDataSource: HttpDataSource,
    private val context: android.content.Context
) : HttpDataSource by baseDataSource {

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri.toString()

        val headers = try {
            runBlocking {
                findHeadersForRequest(uri)
            }
        } catch (e: Exception) {
            android.util.Log.e("HeaderInterceptingDS", "Error finding headers: ${e.message}")
            null
        }

        val updatedDataSpec = if (!headers.isNullOrEmpty()) {
            val newRequestProperties = dataSpec.httpRequestHeaders.toMutableMap()
            headers.forEach { (key, value) ->
                newRequestProperties[key] = value
            }
            dataSpec.buildUpon()
                .setHttpRequestHeaders(newRequestProperties)
                .build()
        } else {
            dataSpec
        }

        return baseDataSource.open(updatedDataSpec)
    }

    private suspend fun findHeadersForRequest(url: String): Map<String, String>? {
        val database = try {
            AppDatabase.getInstance(context)
        } catch (e: Exception) {
            return null
        }

        val downloads = getHeaderDownloads(database)
        if (downloads.isEmpty()) return null

        // 1. Exact URL match
        downloads.firstOrNull { it.url == url }?.headers?.let { return it }

        val requestUri = Uri.parse(url)

        // 2. Path prefix match (e.g. manifest/segment base path)
        val pathMatches = downloads.filter { requestMatchesDownloadPath(requestUri, it.url) }
        pathMatches.firstOrNull()?.headers?.let { return it }

        // 3. Origin match (e.g. same CDN host)
        val originMatches = downloads.filter { requestMatchesDownloadOrigin(requestUri, it.url) }
        return originMatches.firstOrNull()?.headers
    }

    private suspend fun getHeaderDownloads(database: AppDatabase): List<HeaderDownload> {
        val now = System.currentTimeMillis()
        synchronized(headerCacheLock) {
            if (now - cachedAtMillis < HEADER_CACHE_TTL_MS) {
                return cachedHeaderDownloads
            }
        }

        val freshDownloads = database.downloadDao().getAllSnapshot()
            .mapNotNull { download ->
                val headers = download.headers?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                HeaderDownload(download.url, headers)
            }

        synchronized(headerCacheLock) {
            cachedHeaderDownloads = freshDownloads
            cachedAtMillis = now
        }
        return freshDownloads
    }

    private fun requestMatchesDownloadPath(requestUri: Uri, downloadUrl: String): Boolean {
        return candidateHttpUris(downloadUrl).any { candidateUri ->
            requestUri == candidateUri || requestMatchesManifestBase(requestUri, candidateUri)
        }
    }

    private fun requestMatchesDownloadOrigin(requestUri: Uri, downloadUrl: String): Boolean {
        return candidateHttpUris(downloadUrl).any { candidateUri ->
            requestMatchesOrigin(requestUri, candidateUri)
        }
    }

    private fun candidateHttpUris(downloadUrl: String): List<Uri> {
        val directUri = Uri.parse(downloadUrl)
            .takeIf { it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true) }
        val playlistUris = decodeDataPlaylist(downloadUrl)
            ?.let(::extractHttpUrls)
            .orEmpty()
            .map(Uri::parse)

        return listOfNotNull(directUri) + playlistUris
    }

    private fun requestMatchesManifestBase(requestUri: Uri, manifestUri: Uri): Boolean {
        if (!requestMatchesOrigin(requestUri, manifestUri)) return false

        val manifestPath = manifestUri.path.orEmpty()
        val basePath = manifestPath.substringBeforeLast('/', missingDelimiterValue = "")
        if (basePath.isBlank()) return false

        return requestUri.path.orEmpty().startsWith("$basePath/")
    }

    private fun requestMatchesOrigin(requestUri: Uri, manifestUri: Uri): Boolean {
        return requestUri.scheme.equals(manifestUri.scheme, ignoreCase = true) &&
            requestUri.host.equals(manifestUri.host, ignoreCase = true) &&
            effectivePort(requestUri) == effectivePort(manifestUri)
    }

    private fun effectivePort(uri: Uri): Int {
        if (uri.port != -1) return uri.port
        return when (uri.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    private fun decodeDataPlaylist(url: String): String? {
        if (!url.startsWith("data:", ignoreCase = true)) return null

        val metadata = url.substringBefore(',', missingDelimiterValue = "")
        val payload = url.substringAfter(',', missingDelimiterValue = "")
        if (payload.isBlank()) return null

        return runCatching {
            if (metadata.contains(";base64", ignoreCase = true)) {
                String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            } else {
                Uri.decode(payload)
            }
        }.getOrNull()
    }

    private fun extractHttpUrls(playlist: String): List<String> {
        return Regex("""https?://[^\s"'<>]+""")
            .findAll(playlist)
            .map { it.value.trimEnd(',', ')', ']') }
            .toList()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        baseDataSource.addTransferListener(transferListener)
    }

    class Factory(
        private val baseFactory: HttpDataSource.Factory,
        private val context: android.content.Context
    ) : HttpDataSource.Factory {
        override fun createDataSource(): HttpDataSource {
            return HeaderInterceptingDataSource(baseFactory.createDataSource(), context)
        }

        override fun setDefaultRequestProperties(defaultRequestProperties: MutableMap<String, String>): HttpDataSource.Factory {
            baseFactory.setDefaultRequestProperties(defaultRequestProperties)
            return this
        }
    }

    private data class HeaderDownload(
        val url: String,
        val headers: Map<String, String>
    )

    companion object {
        private const val HEADER_CACHE_TTL_MS = 30_000L
        private val headerCacheLock = Any()

        @Volatile
        private var cachedAtMillis = 0L

        @Volatile
        private var cachedHeaderDownloads: List<HeaderDownload> = emptyList()

        fun invalidateHeaderCache() {
            synchronized(headerCacheLock) {
                cachedAtMillis = 0L
                cachedHeaderDownloads = emptyList()
            }
        }
    }
}
