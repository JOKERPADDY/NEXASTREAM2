package com.nexastream.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.HiltAndroidApp
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.utils.AppLanguageManager
import com.nexastream.app.utils.ArtworkRepairScheduler
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.DnsResolver
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.DownloadManager as AppDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NexastreamApp : Application() {

    @OptIn(UnstableApi::class)
    @Inject
    lateinit var appDownloadManagerProvider: javax.inject.Provider<AppDownloadManager>

    companion object {
        lateinit var instance: NexastreamApp
            private set

        @Volatile
        var currentActivity: Activity? = null
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        UserPreferences.setup(base)
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }
        })

        DnsResolver.setDnsUrl(UserPreferences.dohProviderUrl)

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L

        applicationScope.launch(Dispatchers.IO) {
            AppDatabase.setup(appContext)
            val downloadManager = appDownloadManagerProvider.get()
            @OptIn(UnstableApi::class)
            Log.d("NexastreamApp", "DownloadManager initialized: ${downloadManager.hashCode()}")
            downloadManager.recoverDownloads()
            ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider)
            CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold)
            if (isTv) {
                com.nexastream.app.utils.TvChannelManager.updateDefaultChannel(appContext)
                com.nexastream.app.utils.WatchNextUtils.syncWatchNext(appContext)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            CacheUtils.clearAppCache(this)
        }
    }
}
