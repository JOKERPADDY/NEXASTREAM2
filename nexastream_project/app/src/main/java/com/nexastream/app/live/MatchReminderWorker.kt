package com.nexastream.app.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexastream.app.BuildConfig
import com.nexastream.app.R
import com.nexastream.app.activities.main.MainMobileActivity
import com.nexastream.app.activities.main.MainTvActivity
import com.nexastream.app.models.SportMatch
import java.util.concurrent.TimeUnit

class MatchReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val matchId = inputData.getString(KEY_MATCH_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Match starting"
        val league = inputData.getString(KEY_LEAGUE) ?: "Live Sports"
        createChannel()

        val activityClass = if (BuildConfig.APP_LAYOUT == "tv") {
            MainTvActivity::class.java
        } else {
            MainMobileActivity::class.java
        }
        val intent = Intent(applicationContext, activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            matchId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bell)
            .setContentTitle("$title is starting!")
            .setContentText(league)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(matchId.hashCode(), notification)
        return Result.success()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Sport Match Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    companion object {
        const val KEY_MATCH_ID = "match_id"
        const val KEY_TITLE = "title"
        const val KEY_LEAGUE = "league"
        private const val CHANNEL_ID = "sport_match_reminders"
    }
}

object MatchReminderScheduler {
    private const val REMINDER_LEAD_MILLIS = 5 * 60 * 1000L // 5 minutes before

    fun scheduleOrCancel(
        context: Context,
        match: SportMatch,
        isSubscribed: Boolean,
    ) {
        val workName = "match_reminder_${match.id}"
        if (!isSubscribed) {
            WorkManager.getInstance(context).cancelUniqueWork(workName)
        } else {
            val targetTime = match.date ?: (System.currentTimeMillis() + 10 * 60 * 1000L) // Default 10 mins if no date
            val delayMillis = (targetTime - REMINDER_LEAD_MILLIS - System.currentTimeMillis()).coerceAtLeast(0L)
            
            val data = Data.Builder()
                .putString(MatchReminderWorker.KEY_MATCH_ID, match.id)
                .putString(MatchReminderWorker.KEY_TITLE, match.title)
                .putString(MatchReminderWorker.KEY_LEAGUE, match.league)
                .build()

            val request = OneTimeWorkRequestBuilder<MatchReminderWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
