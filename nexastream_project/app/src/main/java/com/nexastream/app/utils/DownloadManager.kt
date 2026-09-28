package com.nexastream.app.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Download as DownloadModel
import com.nexastream.app.NexastreamApp
import com.nexastream.app.services.NexaDownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@UnstableApi
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media3DownloadManager: DownloadManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressPollingJob: Job? = null
    private val progressSamples = ConcurrentHashMap<String, ProgressSample>()

    private fun updateRequirements() {
        val wifiOnly = UserPreferences.downloadWifiOnly
        val requirements = if (wifiOnly) {
            Requirements(Requirements.NETWORK_UNMETERED)
        } else {
            Requirements(Requirements.NETWORK)
        }
        media3DownloadManager.requirements = requirements
        media3DownloadManager.maxParallelDownloads = UserPreferences.maxParallelDownloads
    }

    private fun getDatabase(): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    private fun inferMimeType(url: String, mimeType: String?): String? {
        val normalizedType = mimeType?.trim()?.lowercase()
        if (!normalizedType.isNullOrBlank()) {
            when {
                normalizedType.contains("m3u8") || normalizedType.contains("hls") -> return MimeTypes.APPLICATION_M3U8
                normalizedType.contains("mpd") || normalizedType.contains("dash") -> return MimeTypes.APPLICATION_MPD
                normalizedType.contains("mp4") -> return MimeTypes.VIDEO_MP4
                normalizedType.startsWith("video/") || normalizedType.startsWith("application/") -> return mimeType
            }
        }

        return when {
            url.startsWith("data:application/vnd.apple.mpegurl", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            url.startsWith("data:application/x-mpegURL", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            url.startsWith("data:application/dash+xml", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            url.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }

    private fun statusFor(download: Download): DownloadModel.Status {
        return when (download.state) {
            Download.STATE_COMPLETED -> DownloadModel.Status.COMPLETED
            Download.STATE_FAILED -> DownloadModel.Status.FAILED
            Download.STATE_DOWNLOADING -> DownloadModel.Status.DOWNLOADING
            Download.STATE_STOPPED -> DownloadModel.Status.PAUSED
            Download.STATE_QUEUED -> DownloadModel.Status.QUEUED
            else -> DownloadModel.Status.QUEUED
        }
    }

    init {
        Log.i("AAA", "Initializing AppDownloadManager")
        updateRequirements()
        media3DownloadManager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                Log.d("DownloadManager", "Media3 DownloadManager initialized")
                recoverDownloads()
            }

            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                Log.i("AAA", "Download changed: ${download.request.id}, state: ${download.state}")
                scope.launch {
                    if (download.state == Download.STATE_REMOVING) {
                        removeStoredDownload(download.request.id)
                        return@launch
                    }

                    val status = statusFor(download)

                    try {
                        val database = getDatabase()
                        if (download.state == Download.STATE_FAILED) {
                            progressSamples.remove(download.request.id)
                            database.downloadDao().updateFailure(
                                download.request.id,
                                finalException?.message ?: "Unknown error"
                            )
                        } else {
                            updateStoredProgress(download, status)
                        }
                    } catch (e: Exception) {
                        Log.e("AAA", "Error updating download change in DB: ${e.message}")
                    }

                    if (download.state == Download.STATE_DOWNLOADING) {
                        startProgressPolling()
                    }
                }
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download
            ) {
                scope.launch {
                    removeStoredDownload(download.request.id)
                }
            }
        })

        if (media3DownloadManager.isInitialized) {
            recoverDownloads()
        }
    }

    fun recoverDownloads() {
        scope.launch {
            try {
                if (!media3DownloadManager.isInitialized) {
                    Log.d("DownloadManager", "Skipping download recovery until Media3 initializes")
                    return@launch
                }

                val database = getDatabase()
                val media3Downloads = media3DownloadManager.currentDownloads
                val media3DownloadIds = media3Downloads
                    .filter { it.state != Download.STATE_REMOVING }
                    .map { it.request.id }
                    .toSet()

                Log.d("DownloadManager", "Recovering ${media3Downloads.size} Media3 downloads")

                media3Downloads.forEach { download ->
                    if (download.state == Download.STATE_REMOVING) {
                        removeStoredDownload(download.request.id)
                        return@forEach
                    }
                    ensureStoredDownload(database, download)
                    updateStoredProgress(download, statusFor(download))
                }

                database.downloadDao().getAllSnapshot().forEach { storedDownload ->
                    val isActiveInRoom = storedDownload.status == DownloadModel.Status.DOWNLOADING ||
                        storedDownload.status == DownloadModel.Status.QUEUED

                    if (isActiveInRoom && storedDownload.id !in media3DownloadIds) {
                        database.downloadDao().updateFailure(
                            storedDownload.id,
                            "Download was not found after app restart"
                        )
                    }
                }

                startProgressPolling()
            } catch (e: Exception) {
                Log.e("DownloadManager", "Error recovering downloads: ${e.message}", e)
            }
        }
    }

    private suspend fun ensureStoredDownload(database: AppDatabase, download: Download) {
        if (database.downloadDao().getById(download.request.id) != null) return

        val title = download.request.data
            .takeIf { it.isNotEmpty() }
            ?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
            ?: download.request.id
        database.downloadDao().insert(
            DownloadModel(
                id = download.request.id,
                title = title,
                poster = null,
                cacheKey = download.request.id,
                url = download.request.uri.toString(),
                status = statusFor(download),
                progress = -1,
                downloadedSize = download.bytesDownloaded,
                totalSize = download.contentLength.coerceAtLeast(0L),
                mimeType = download.request.mimeType
            )
        )
    }

    @Synchronized
    private fun startProgressPolling() {
        if (progressPollingJob?.isActive == true) {
            Log.d("DownloadManager", "Polling job already active")
            return
        }

        Log.d("DownloadManager", "Starting progress polling job")
        progressPollingJob = scope.launch {
            while (isActive) {
                val currentDownloads = media3DownloadManager.currentDownloads
                Log.d("DownloadManager", "Polling... Found ${currentDownloads.size} downloads in Media3")

                if (currentDownloads.isEmpty()) {
                    Log.d("DownloadManager", "No downloads found, stopping polling")
                    break
                }

                var hasActiveDownloads = false
                currentDownloads.forEach { download ->
                    Log.d("DownloadManager", "Download ${download.request.id}: state=${download.state}, progress=${download.percentDownloaded}")
                    if (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_RESTARTING || download.state == Download.STATE_QUEUED) {
                        hasActiveDownloads = true
                        updateStoredProgress(download, statusFor(download))
                    }
                }

                if (!hasActiveDownloads) {
                    Log.d("DownloadManager", "No active downloads, stopping polling")
                    break
                }

                delay(1000) // Poll every second
            }
        }
    }

    private suspend fun updateStoredProgress(download: Download, status: DownloadModel.Status) {
        try {
            val database = getDatabase()
            val existingDownload = database.downloadDao().getById(download.request.id)
            val totalSize = when {
                download.contentLength > 0L -> download.contentLength
                existingDownload?.totalSize != null && existingDownload.totalSize > 0L -> existingDownload.totalSize
                else -> 0L
            }
            val downloadedSize = download.bytesDownloaded.coerceAtLeast(existingDownload?.downloadedSize ?: 0L)
            val progress = calculateProgress(download, status, downloadedSize, totalSize, existingDownload?.progress ?: 0)
            val speed = calculateSpeed(download.request.id, downloadedSize, status)
            val etaSeconds = calculateEtaSeconds(downloadedSize, totalSize, speed, status)
            val waitingReason = if (status == DownloadModel.Status.QUEUED || status == DownloadModel.Status.DOWNLOADING) {
                media3DownloadManager.notMetRequirements
            } else {
                0
            }

            database.downloadDao().updateProgress(
                download.request.id,
                status,
                progress,
                downloadedSize,
                totalSize,
                speed,
                etaSeconds,
                waitingReason
            )
        } catch (e: Exception) {
            Log.e("AAA", "Error updating progress in DB: ${e.message}")
        }
    }

    private fun calculateProgress(
        download: Download,
        status: DownloadModel.Status,
        downloadedSize: Long,
        totalSize: Long,
        previousProgress: Int
    ): Int {
        if (status == DownloadModel.Status.COMPLETED) return 100
        if (status == DownloadModel.Status.FAILED) return previousProgress

        val percentDownloaded = download.percentDownloaded
        val media3Progress = if (percentDownloaded >= 0f) {
            percentDownloaded.roundToInt()
        } else {
            null
        }

        val byteProgress = if (totalSize > 0L && downloadedSize > 0L) {
            ((downloadedSize * 100) / totalSize).toInt()
        } else {
            null
        }

        val previousKnownProgress = previousProgress.takeIf { it > 0 }

        val result = listOfNotNull(media3Progress, byteProgress, previousKnownProgress)
            .maxOrNull()
            ?.coerceIn(0, 100)
            ?: -1

        return result
    }

    private fun calculateSpeed(
        id: String,
        downloadedSize: Long,
        status: DownloadModel.Status
    ): Long {
        if (status != DownloadModel.Status.DOWNLOADING) {
            progressSamples.remove(id)
            return 0L
        }

        val now = System.currentTimeMillis()
        val previousSample = progressSamples.put(id, ProgressSample(downloadedSize, now))
        if (previousSample == null) return 0L

        val elapsedMillis = now - previousSample.timestampMillis
        if (elapsedMillis <= 0L || downloadedSize <= previousSample.downloadedSize) return 0L

        return ((downloadedSize - previousSample.downloadedSize) * 1000L) / elapsedMillis
    }

    private fun calculateEtaSeconds(
        downloadedSize: Long,
        totalSize: Long,
        speed: Long,
        status: DownloadModel.Status
    ): Long? {
        if (status != DownloadModel.Status.DOWNLOADING || totalSize <= 0L || speed <= 0L) return null
        return ((totalSize - downloadedSize).coerceAtLeast(0L)) / speed
    }

    fun startDownload(
        id: String,
        title: String,
        poster: String?,
        url: String,
        quality: String?,
        headers: Map<String, String>? = null,
        mimeType: String? = null
    ) {
        Log.i("AAA", "startDownload called: id=$id, title=$title, url=$url")
        scope.launch {
            if (!hasEnoughDiskSpace()) {
                Log.e("DownloadManager", "Not enough disk space to start download")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Not enough disk space to start download", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val resolvedMimeType = inferMimeType(url, mimeType)

            val downloadModel = DownloadModel(
                id = id,
                title = title,
                poster = poster,
                cacheKey = id,
                url = url,
                status = DownloadModel.Status.QUEUED,
                quality = quality,
                headers = headers,
                mimeType = resolvedMimeType
            )
            try {
                getDatabase().downloadDao().insert(downloadModel)
                Log.i("AAA", "Successfully inserted download in DB for id: $id")
                HeaderInterceptingDataSource.invalidateHeaderCache()
            } catch (e: Exception) {
                Log.e("AAA", "Error inserting download in DB: ${e.message}")
            }

            Log.i("AAA", "Building DownloadRequest for id=$id, url=$url, mimeType=$resolvedMimeType")
            val displayTitle = if (!quality.isNullOrBlank()) "$title ($quality)" else title
            val downloadRequest = try {
                val builder = DownloadRequest.Builder(id, Uri.parse(url))
                builder.setMimeType(resolvedMimeType)
                builder.setData(displayTitle.toByteArray(Charsets.UTF_8)).build()
            } catch (e: Exception) {
                Log.e("AAA", "Failed to build DownloadRequest: ${e.message}", e)
                DownloadRequest.Builder(id, Uri.parse(url))
                    .setData(displayTitle.toByteArray(Charsets.UTF_8))
                    .build()
            }

            Log.i("AAA", "DownloadRequest built: $downloadRequest")

            // Media3 stores downloads in SimpleCache under the request id. Per-download
            // headers are resolved by HeaderInterceptingDataSource during segment fetches.

            enqueueDownload(downloadRequest)
        }
    }

    fun pauseDownload(id: String) {
        scope.launch {
            DownloadService.sendSetStopReason(
                context,
                NexaDownloadService::class.java,
                id,
                STOP_REASON_PAUSED,
                shouldUseForegroundService()
            )
            try {
                getDatabase().downloadDao().updateStatus(id, DownloadModel.Status.PAUSED)
            } catch (e: Exception) {
                Log.e("AAA", "Error pausing download in DB: ${e.message}")
            }
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            DownloadService.sendSetStopReason(
                context,
                NexaDownloadService::class.java,
                id,
                Download.STOP_REASON_NONE,
                shouldUseForegroundService()
            )
            try {
                getDatabase().downloadDao().updateStatus(id, DownloadModel.Status.QUEUED)
            } catch (e: Exception) {
                Log.e("AAA", "Error resuming download in DB: ${e.message}")
            }
            startProgressPolling()
        }
    }

    fun retryDownload(id: String) {
        scope.launch {
            try {
                val database = getDatabase()
                val download = database.downloadDao().getById(id) ?: return@launch
                database.downloadDao().updateProgress(
                    id,
                    DownloadModel.Status.QUEUED,
                    -1,
                    download.downloadedSize,
                    download.totalSize,
                    0L,
                    null
                )

                val downloadRequest = DownloadRequest.Builder(id, Uri.parse(download.url))
                    .apply {
                        setMimeType(download.mimeType)
                        setData(download.title.toByteArray(Charsets.UTF_8))
                    }
                    .build()

                enqueueDownload(downloadRequest)
                startProgressPolling()
            } catch (e: Exception) {
                Log.e("AAA", "Error retrying download in DB: ${e.message}")
            }
        }
    }

    private fun enqueueDownload(downloadRequest: DownloadRequest) {
        DownloadService.sendAddDownload(
            context,
            NexaDownloadService::class.java,
            downloadRequest,
            shouldUseForegroundService()
        )
        startProgressPolling()
    }

    fun deleteDownload(id: String) {
        scope.launch {
            try {
                val database = getDatabase()
                val download = database.downloadDao().getById(id)
                DownloadService.sendRemoveDownload(
                    context,
                    NexaDownloadService::class.java,
                    id,
                    shouldUseForegroundService()
                )
                download?.let { deleteLegacySingleFile(it.cacheKey) }
                progressSamples.remove(id)
                database.downloadDao().deleteById(id)
                HeaderInterceptingDataSource.invalidateHeaderCache()
            } catch (e: Exception) {
                Log.e("AAA", "Error deleting download in DB: ${e.message}")
            }
        }
    }

    private fun deleteLegacySingleFile(cacheKey: String) {
        val file = File(cacheKey)
        if (file.isAbsolute && file.exists()) {
            file.delete()
        }
    }

    private fun hasEnoughDiskSpace(): Boolean {
        return try {
            val downloadRoot = context.getExternalFilesDir(null) ?: context.filesDir
            val stat = StatFs(downloadRoot.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            // Require at least 500MB free
            availableBytes > 500 * 1024 * 1024
        } catch (e: Exception) {
            true // Default to true if check fails
        }
    }

    fun onSettingsChanged() {
        updateRequirements()
    }

    private suspend fun removeStoredDownload(id: String) {
        runCatching {
            getDatabase().downloadDao().deleteById(id)
            progressSamples.remove(id)
            HeaderInterceptingDataSource.invalidateHeaderCache()
        }.onFailure { error ->
            Log.e("DownloadManager", "Error removing download $id from the database", error)
        }
    }

    private fun shouldUseForegroundService(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    private data class ProgressSample(
        val downloadedSize: Long,
        val timestampMillis: Long
    )

    private companion object {
        private const val STOP_REASON_PAUSED = 1
    }
}
