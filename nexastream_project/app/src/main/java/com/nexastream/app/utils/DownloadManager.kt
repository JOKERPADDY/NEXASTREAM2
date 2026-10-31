package com.nexastream.app.utils

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Download
import com.nexastream.app.services.MovieBoxDownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startDownload(
        id: String,
        title: String,
        poster: String?,
        url: String,
        quality: String?,
        headers: Map<String, String>? = null,
        mimeType: String? = null,
        subtitleUrl: String? = null,
        serverUrl: String? = null,
        etag: String? = null,
        expectedSize: Long? = null
    ) {
        scope.launch {
            if (!hasEnoughDiskSpace()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Not enough disk space to start download", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val downloadsDir = getDownloadsDirectory()
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val extension = if (url.contains(".mkv", ignoreCase = true)) "mkv" else "mp4"
            val targetFile = File(downloadsDir, "${sanitizedTitle}_${id.takeLast(6)}.$extension")

            val download = Download(
                id = id,
                title = title,
                poster = poster,
                cacheKey = targetFile.absolutePath,
                url = url,
                totalSize = expectedSize ?: 0L,
                etag = etag,
                status = Download.Status.QUEUED,
                quality = quality,
                headers = headers,
                mimeType = mimeType,
                subtitleUrl = subtitleUrl,
                serverUrl = serverUrl
            )

            try {
                database.downloadDao().insert(download)
                MovieBoxDownloadService.startDownload(context, id)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting download for $id: ${e.message}", e)
            }
        }
    }

    fun pauseDownload(id: String) {
        scope.launch {
            try {
                MovieBoxDownloadService.pauseDownload(context, id)
                database.downloadDao().updateStatus(id, Download.Status.PAUSED)
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing download $id: ${e.message}", e)
            }
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            try {
                MovieBoxDownloadService.resumeDownload(context, id)
                database.downloadDao().updateStatus(id, Download.Status.QUEUED)
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming download $id: ${e.message}", e)
            }
        }
    }

    fun retryDownload(id: String) {
        scope.launch {
            try {
                if (database.downloadDao().getById(id) == null) return@launch
                database.downloadDao().updateStatus(id, Download.Status.QUEUED)
                MovieBoxDownloadService.startDownload(context, id)
            } catch (e: Exception) {
                Log.e(TAG, "Error retrying download $id: ${e.message}", e)
            }
        }
    }

    fun deleteDownload(id: String) {
        scope.launch {
            try {
                val download = database.downloadDao().getById(id)
                MovieBoxDownloadService.cancelDownload(context, id)
                if (download != null) {
                    val file = File(download.filePath)
                    if (file.exists()) file.delete()

                    val thumbFile = File(file.parentFile, "${file.nameWithoutExtension}_thumb.jpg")
                    if (thumbFile.exists()) thumbFile.delete()

                    val subFile = File(file.parentFile, "${file.nameWithoutExtension}.vtt")
                    if (subFile.exists()) subFile.delete()

                    database.downloadDao().deleteById(id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting download $id: ${e.message}", e)
            }
        }
    }

    fun onSettingsChanged() {
        // Handle settings adjustments if needed
    }

    fun recoverDownloads() {
        scope.launch {
            try {
                val activeDownloads = database.downloadDao().getAllSnapshot().filter {
                    it.status == Download.Status.DOWNLOADING || it.status == Download.Status.QUEUED
                }
                for (download in activeDownloads) {
                    MovieBoxDownloadService.startDownload(context, download.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering downloads: ${e.message}", e)
            }
        }
    }

    private fun getDownloadsDirectory(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val subDir = File(dir, "MovieBoxDownloads")
        if (!subDir.exists()) subDir.mkdirs()
        return subDir
    }

    private fun hasEnoughDiskSpace(): Boolean {
        return try {
            val downloadRoot = getDownloadsDirectory()
            val stat = StatFs(downloadRoot.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes > 500 * 1024 * 1024 // 500 MB minimum
        } catch (e: Exception) {
            true
        }
    }

    companion object {
        private const val TAG = "DownloadManager"
    }
}
