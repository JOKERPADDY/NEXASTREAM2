package com.nexastream.app.services

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.nexastream.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class NexaStreamDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
    R.string.download_notification_channel_name,
    R.string.download_notification_channel_description
) {

    @Inject
    lateinit var downloadManagerInject: DownloadManager

    @Inject
    lateinit var downloadNotificationHelperInject: DownloadNotificationHelper

    @Inject
    lateinit var schedulerInject: Scheduler

    override fun getDownloadManager(): DownloadManager {
        return downloadManagerInject
    }

    override fun getScheduler(): Scheduler {
        return schedulerInject
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return downloadNotificationHelperInject.buildProgressNotification(
            this,
            R.drawable.ic_download,
            null,
            null,
            downloads,
            notMetRequirements
        )
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_channel"
    }
}
