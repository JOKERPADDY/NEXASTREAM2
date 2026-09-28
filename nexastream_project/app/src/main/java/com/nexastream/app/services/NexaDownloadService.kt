package com.nexastream.app.services

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.scheduler.Scheduler
import com.nexastream.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class NexaDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0
) {

    @Inject
    lateinit var injectedDownloadManager: DownloadManager

    @Inject
    lateinit var notificationHelper: DownloadNotificationHelper

    @Inject
    lateinit var injectedScheduler: Scheduler

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "download_channel"
    }

    override fun getDownloadManager(): DownloadManager {
        android.util.Log.d("NexaDownloadService", "getDownloadManager called")
        return injectedDownloadManager
    }

    override fun getScheduler(): Scheduler? = injectedScheduler

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return try {
            notificationHelper.buildProgressNotification(
                this,
                R.drawable.ic_download,
                null,
                null,
                downloads,
                notMetRequirements
            )
        } catch (e: Exception) {
            android.util.Log.e("NexaDownloadService", "Error building download notification: ${e.message}", e)
            notificationHelper.buildProgressNotification(
                this,
                R.drawable.ic_download,
                null,
                null,
                emptyList(),
                0
            )
        }
    }
}
