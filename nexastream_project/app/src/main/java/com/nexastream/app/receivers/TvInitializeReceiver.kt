package com.nexastream.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nexastream.app.utils.TvChannelManager
import com.nexastream.app.utils.WatchNextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TvInitializeReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.media.tv.action.INITIALIZE_PROGRAMS") {
            scope.launch {
                TvChannelManager.updateDefaultChannel(context)
                WatchNextUtils.syncWatchNext(context)
            }
        }
    }
}
