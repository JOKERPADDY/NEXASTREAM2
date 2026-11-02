package com.nexastream.app.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexastream.app.R
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Download
import com.nexastream.app.utils.DnsResolver
import com.nexastream.app.utils.DownloadMetadataProbe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MovieBoxDownloadService : Service() {

    @Inject
    lateinit var database: AppDatabase

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)

        when (action) {
            ACTION_START, ACTION_RESUME -> {
                if (downloadId != null) {
                    startDownloadTask(downloadId)
                }
            }
            ACTION_PAUSE -> {
                if (downloadId != null) {
                    pauseDownloadTask(downloadId)
                }
            }
            ACTION_CANCEL -> {
                if (downloadId != null) {
                    cancelDownloadTask(downloadId)
                }
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundService()
            }
        }

        return START_STICKY
    }

    private fun startDownloadTask(downloadId: String) {
        activeJobs[downloadId]?.cancel()

        val job = serviceScope.launch {
            val download = database.downloadDao().getById(downloadId) ?: return@launch

            if (download.status == Download.Status.COMPLETED) {
                Log.d(TAG, "Download $downloadId is already COMPLETED. Skipping start request.")
                checkStopService()
                return@launch
            }

            val downloadsDir = File(download.filePath).parentFile
            if (downloadsDir != null && !downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val targetFile = File(download.filePath)
            var currentBytes = if (targetFile.exists()) targetFile.length() else 0L

            if (download.totalSize > 0 && currentBytes >= download.totalSize) {
                database.downloadDao().updateProgress(
                    id = downloadId,
                    status = Download.Status.COMPLETED,
                    progress = 100,
                    downloadedSize = currentBytes,
                    totalSize = download.totalSize,
                    downloadSpeed = 0,
                    etaSeconds = 0
                )
                extractThumbnailLocally(downloadId, targetFile)
                downloadSubtitleIfAvailable(download)
                updateNotification(downloadId, download.title, "Download complete", 100, false)
                checkStopService()
                return@launch
            }

            database.downloadDao().updateStatus(downloadId, Download.Status.DOWNLOADING)
            showForegroundNotification(download.title, 0, "Starting download...")

            if (DownloadMetadataProbe.isHlsUrl(download.url) || download.mimeType?.lowercase()?.contains("mpegurl") == true) {
                downloadHlsTask(downloadId, download)
                return@launch
            }

            try {
                val requestBuilder = Request.Builder()
                    .url(download.url)

                if (currentBytes > 0) {
                    requestBuilder.header("Range", "bytes=$currentBytes-")
                }

                if (!download.headersJson.isNullOrEmpty()) {
                    try {
                        val map = org.json.JSONObject(download.headersJson!!)
                        val keys = map.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            requestBuilder.header(key, map.getString(key))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing custom headers: ${e.message}")
                    }
                }

                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                if (response.code == 416) {
                    if (currentBytes > 0) {
                        database.downloadDao().updateProgress(
                            id = downloadId,
                            status = Download.Status.COMPLETED,
                            progress = 100,
                            downloadedSize = currentBytes,
                            totalSize = if (download.totalSize > 0) download.totalSize else currentBytes,
                            downloadSpeed = 0,
                            etaSeconds = 0
                        )
                        extractThumbnailLocally(downloadId, targetFile)
                        downloadSubtitleIfAvailable(download)
                        updateNotification(downloadId, download.title, "Download complete", 100, false)
                        checkStopService()
                        return@launch
                    }
                }

                if (!response.isSuccessful && response.code != 206) {
                    database.downloadDao().updateFailure(downloadId, "HTTP Error ${response.code}")
                    updateNotification(downloadId, download.title, "Download failed (HTTP ${response.code})", 0, false)
                    checkStopService()
                    return@launch
                }

                val responseBody = response.body
                if (responseBody == null) {
                    database.downloadDao().updateFailure(downloadId, "Empty response body")
                    checkStopService()
                    return@launch
                }

                val totalSize = if (response.code == 206) {
                    val contentRange = response.header("Content-Range")
                    val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                    if (totalFromRange != null && totalFromRange > 0) totalFromRange else download.totalSize
                } else {
                    responseBody.contentLength().let { if (it > 0) it else download.totalSize }
                }

                var raf: RandomAccessFile? = null
                try {
                    raf = RandomAccessFile(targetFile, "rw")
                    if (response.code == 206) {
                        raf.seek(currentBytes)
                    } else {
                        raf.setLength(0)
                        currentBytes = 0
                    }

                    val inputStream = responseBody.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead = 0

                    var lastUpdateTime = System.currentTimeMillis()
                    var bytesSinceLastUpdate = 0L

                    while (isActive && inputStream.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        currentBytes += bytesRead
                        bytesSinceLastUpdate += bytesRead

                        val now = System.currentTimeMillis()
                        val timeDiff = now - lastUpdateTime
                        if (timeDiff >= 500) {
                            val speed = (bytesSinceLastUpdate * 1000) / timeDiff
                            val remainingBytes = if (totalSize > currentBytes) totalSize - currentBytes else 0L
                            val eta = if (speed > 0) remainingBytes / speed else 0L
                            val progress = if (totalSize > 0) ((currentBytes * 100) / totalSize).toInt() else 0

                            database.downloadDao().updateProgress(
                                id = downloadId,
                                status = Download.Status.DOWNLOADING,
                                progress = progress,
                                downloadedSize = currentBytes,
                                totalSize = totalSize,
                                downloadSpeed = speed,
                                etaSeconds = eta
                            )

                            val speedFormatted = formatBytesPerSec(speed)
                            val etaFormatted = formatEta(eta)
                            updateNotification(
                                downloadId = downloadId,
                                title = download.title,
                                contentText = "$progress% • $speedFormatted • $etaFormatted remaining",
                                progress = progress,
                                ongoing = true
                            )

                            lastUpdateTime = now
                            bytesSinceLastUpdate = 0L
                        }
                    }

                    if (isActive) {
                        // Download completed successfully
                        database.downloadDao().updateProgress(
                            id = downloadId,
                            status = Download.Status.COMPLETED,
                            progress = 100,
                            downloadedSize = currentBytes,
                            totalSize = if (totalSize > 0) totalSize else currentBytes,
                            downloadSpeed = 0,
                            etaSeconds = 0
                        )

                        // Extract local thumbnail
                        extractThumbnailLocally(downloadId, targetFile)

                        // Download subtitle if present
                        downloadSubtitleIfAvailable(download)

                        updateNotification(
                            downloadId = downloadId,
                            title = download.title,
                            contentText = "Download complete",
                            progress = 100,
                            ongoing = false
                        )
                    } else {
                        // Job was cancelled/paused
                        database.downloadDao().updateProgress(
                            id = downloadId,
                            status = Download.Status.PAUSED,
                            progress = if (totalSize > 0) ((currentBytes * 100) / totalSize).toInt() else 0,
                            downloadedSize = currentBytes,
                            totalSize = totalSize,
                            downloadSpeed = 0,
                            etaSeconds = 0
                        )
                    }

                } finally {
                    raf?.close()
                    responseBody.close()
                }

            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Download failed for $downloadId: ${e.message}", e)
                    database.downloadDao().updateFailure(downloadId, e.message ?: "Download failed")
                    updateNotification(downloadId, download.title, "Download failed. Tap to retry.", 0, false)
                }
            } finally {
                activeJobs.remove(downloadId)
                checkStopService()
            }
        }

        activeJobs[downloadId] = job
    }

    private suspend fun downloadHlsTask(downloadId: String, download: Download) = withContext(Dispatchers.IO) {
        if (download.status == Download.Status.COMPLETED) {
            Log.d(TAG, "Download $downloadId is already COMPLETED. Skipping HLS download.")
            checkStopService()
            return@withContext
        }

        val downloadsDir = File(download.filePath).parentFile
        if (downloadsDir != null && !downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val targetFile = File(download.filePath)
        val hlsMetaFile = File("${download.filePath}.hls_meta")

        database.downloadDao().updateStatus(downloadId, Download.Status.DOWNLOADING)
        showForegroundNotification(download.title, 0, "Starting HLS download...")

        val customHeaders = mutableMapOf<String, String>()
        if (!download.headersJson.isNullOrEmpty()) {
            try {
                val map = org.json.JSONObject(download.headersJson!!)
                val keys = map.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    customHeaders[key] = map.getString(key)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing headers for HLS: ${e.message}")
            }
        }

        try {
            val reqBuilder = Request.Builder().url(download.url)
            for ((k, v) in customHeaders) reqBuilder.header(k, v)

            val response = okHttpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                database.downloadDao().updateFailure(downloadId, "HTTP Error ${response.code} fetching playlist")
                updateNotification(downloadId, download.title, "HLS download failed (HTTP ${response.code})", 0, false)
                checkStopService()
                return@withContext
            }

            val bodyString = response.body?.string() ?: ""
            if (!bodyString.contains("#EXTM3U")) {
                database.downloadDao().updateFailure(downloadId, "Invalid HLS playlist")
                updateNotification(downloadId, download.title, "Invalid HLS playlist", 0, false)
                checkStopService()
                return@withContext
            }

            var mediaPlaylistUrl = download.url
            var mediaPlaylistBody = bodyString

            if (bodyString.contains("#EXT-X-STREAM-INF")) {
                val variantUrl = parseMasterPlaylistHighestVariant(bodyString, download.url)
                if (variantUrl != null) {
                    mediaPlaylistUrl = variantUrl
                    val variantReq = Request.Builder().url(variantUrl)
                    for ((k, v) in customHeaders) variantReq.header(k, v)
                    val variantResp = okHttpClient.newCall(variantReq.build()).execute()
                    if (variantResp.isSuccessful) {
                        mediaPlaylistBody = variantResp.body?.string() ?: ""
                    }
                }
            }

            val segments = parseMediaPlaylistSegments(mediaPlaylistBody, mediaPlaylistUrl)
            if (segments.isEmpty()) {
                database.downloadDao().updateFailure(downloadId, "No segments found in HLS playlist")
                updateNotification(downloadId, download.title, "No segments found in playlist", 0, false)
                checkStopService()
                return@withContext
            }

            val startSegmentIndex = if (hlsMetaFile.exists()) {
                hlsMetaFile.readText().toIntOrNull() ?: 0
            } else 0

            var currentBytes = if (targetFile.exists()) targetFile.length() else 0L

            var lastUpdateTime = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L

            FileOutputStream(targetFile, true).use { fos ->
                for (i in startSegmentIndex until segments.size) {
                    if (!coroutineContext.isActive) break

                    val segmentUrl = segments[i]
                    val segReqBuilder = Request.Builder().url(segmentUrl)
                    for ((k, v) in customHeaders) segReqBuilder.header(k, v)

                    val segResponse = okHttpClient.newCall(segReqBuilder.build()).execute()
                    if (!segResponse.isSuccessful) {
                        Log.w(TAG, "Segment $i failed HTTP ${segResponse.code}")
                        continue
                    }

                    val segBody = segResponse.body ?: continue
                    val inputStream = segBody.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead = 0

                    while (coroutineContext.isActive && inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        currentBytes += bytesRead
                        bytesSinceLastUpdate += bytesRead
                    }

                    hlsMetaFile.writeText(i.toString())

                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastUpdateTime
                    if (timeDiff >= 500 || i == segments.size - 1) {
                        val speed = if (timeDiff > 0) (bytesSinceLastUpdate * 1000) / timeDiff else 0L
                        val progress = ((i + 1) * 100) / segments.size
                        val remainingSegments = segments.size - (i + 1)
                        val avgSegBytes = if (i + 1 > 0) currentBytes / (i + 1) else 1_200_000L
                        val remainingBytes = remainingSegments * avgSegBytes
                        val eta = if (speed > 0) remainingBytes / speed else 0L

                        database.downloadDao().updateProgress(
                            id = downloadId,
                            status = Download.Status.DOWNLOADING,
                            progress = progress,
                            downloadedSize = currentBytes,
                            totalSize = maxOf(download.totalSize, currentBytes + remainingBytes),
                            downloadSpeed = speed,
                            etaSeconds = eta
                        )

                        val speedFormatted = formatBytesPerSec(speed)
                        val etaFormatted = formatEta(eta)
                        updateNotification(
                            downloadId = downloadId,
                            title = download.title,
                            contentText = "$progress% • $speedFormatted • $etaFormatted remaining",
                            progress = progress,
                            ongoing = true
                        )

                        lastUpdateTime = now
                        bytesSinceLastUpdate = 0L
                    }
                }
                fos.flush()
            }

            if (coroutineContext.isActive) {
                hlsMetaFile.delete()

                database.downloadDao().updateProgress(
                    id = downloadId,
                    status = Download.Status.COMPLETED,
                    progress = 100,
                    downloadedSize = currentBytes,
                    totalSize = currentBytes,
                    downloadSpeed = 0,
                    etaSeconds = 0
                )

                extractThumbnailLocally(downloadId, targetFile)
                downloadSubtitleIfAvailable(download)

                updateNotification(
                    downloadId = downloadId,
                    title = download.title,
                    contentText = "Download complete",
                    progress = 100,
                    ongoing = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "HLS download failed for $downloadId: ${e.message}", e)
            database.downloadDao().updateFailure(downloadId, e.message ?: "Unknown error")
            updateNotification(downloadId, download.title, "HLS download failed: ${e.message}", 0, false)
        } finally {
            checkStopService()
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
                    bestUrl = DownloadMetadataProbe.resolveUrl(baseUrl, line)
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
                segments.add(DownloadMetadataProbe.resolveUrl(baseUrl, trimmed))
            }
        }
        return segments
    }

    private fun pauseDownloadTask(downloadId: String) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        serviceScope.launch {
            database.downloadDao().updateStatus(downloadId, Download.Status.PAUSED)
            val download = database.downloadDao().getById(downloadId)
            if (download != null) {
                updateNotification(downloadId, download.title, "Download paused", download.progress, false)
            }
            checkStopService()
        }
    }

    private fun cancelDownloadTask(downloadId: String) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        serviceScope.launch {
            val download = database.downloadDao().getById(downloadId)
            if (download != null) {
                val file = File(download.filePath)
                if (file.exists()) file.delete()
                database.downloadDao().deleteById(downloadId)
            }
            cancelNotification(downloadId)
            checkStopService()
        }
    }

    private fun extractThumbnailLocally(downloadId: String, videoFile: File) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(10000000) // 10s frame
            retriever.release()

            if (bitmap != null) {
                val thumbFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}_thumb.jpg")
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                runBlocking {
                    val current = database.downloadDao().getById(downloadId)
                    if (current != null) {
                        database.downloadDao().insert(current.copy(poster = thumbFile.absolutePath))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract local thumbnail: ${e.message}")
        }
    }

    private fun downloadSubtitleIfAvailable(download: Download) {
        val subUrl = download.subtitleUrl ?: return
        try {
            val subFile = File(File(download.filePath).parentFile, "${File(download.filePath).nameWithoutExtension}.vtt")
            val req = Request.Builder().url(subUrl).build()
            okHttpClient.newCall(req).execute().use { response ->
                val body = response.body
                if (response.isSuccessful && body != null) {
                    body.byteStream().use { input ->
                        FileOutputStream(subFile).use { out ->
                            input.copyTo(out)
                        }
                    }
                    runBlocking {
                        val current = database.downloadDao().getById(download.id)
                        if (current != null) {
                            database.downloadDao().insert(current.copy(subtitlePath = subFile.absolutePath))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not download subtitle: ${e.message}")
        }
    }

    private fun checkStopService() {
        if (activeJobs.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        }
    }

    private fun stopForegroundService() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MovieBox Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of active MovieBox background downloads"
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification(title: String, progress: Int, text: String) {
        val notification = buildNotification(title, text, progress, true, null)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(
        downloadId: String,
        title: String,
        contentText: String,
        progress: Int,
        ongoing: Boolean
    ) {
        val notification = buildNotification(title, contentText, progress, ongoing, downloadId)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(downloadId.hashCode(), notification)
    }

    private fun cancelNotification(downloadId: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(downloadId.hashCode())
    }

    private fun buildNotification(
        title: String,
        contentText: String,
        progress: Int,
        ongoing: Boolean,
        downloadId: String?
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress in 0..100 && ongoing) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        if (downloadId != null && ongoing) {
            val pauseIntent = Intent(this, DownloadNotificationActionReceiver::class.java).apply {
                action = DownloadNotificationActionReceiver.ACTION_PAUSE
                putExtra(DownloadNotificationActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
            }
            val pausePendingIntent = PendingIntent.getBroadcast(
                this,
                downloadId.hashCode() + 1,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_pause, "Pause", pausePendingIntent)

            val cancelIntent = Intent(this, DownloadNotificationActionReceiver::class.java).apply {
                action = DownloadNotificationActionReceiver.ACTION_CANCEL
                putExtra(DownloadNotificationActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
            }
            val cancelPendingIntent = PendingIntent.getBroadcast(
                this,
                downloadId.hashCode() + 2,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_close, "Cancel", cancelPendingIntent)
        }

        return builder.build()
    }

    private fun formatBytesPerSec(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatEta(etaSeconds: Long): String {
        val minutes = etaSeconds / 60
        val seconds = etaSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "MovieBoxDownloadService"
        const val CHANNEL_ID = "moviebox_download_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.nexastream.app.moviebox.ACTION_START"
        const val ACTION_PAUSE = "com.nexastream.app.moviebox.ACTION_PAUSE"
        const val ACTION_RESUME = "com.nexastream.app.moviebox.ACTION_RESUME"
        const val ACTION_CANCEL = "com.nexastream.app.moviebox.ACTION_CANCEL"
        const val ACTION_STOP_SERVICE = "com.nexastream.app.moviebox.ACTION_STOP_SERVICE"

        const val EXTRA_DOWNLOAD_ID = "extra_download_id"

        fun startDownload(context: Context, downloadId: String) {
            val intent = Intent(context, MovieBoxDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseDownload(context: Context, downloadId: String) {
            val intent = Intent(context, MovieBoxDownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }

        fun resumeDownload(context: Context, downloadId: String) {
            val intent = Intent(context, MovieBoxDownloadService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, downloadId: String) {
            val intent = Intent(context, MovieBoxDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }
    }
}
