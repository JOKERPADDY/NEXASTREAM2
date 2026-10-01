package com.nexastream.app.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.nexastream.app.BuildConfig
import com.nexastream.app.utils.UserPreferences

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetClass = when {
            UserPreferences.forceTvUi -> MainTvActivity::class.java
            BuildConfig.APP_LAYOUT == "tv" -> MainTvActivity::class.java
            BuildConfig.APP_LAYOUT == "mobile" -> MainMobileActivity::class.java
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> MainTvActivity::class.java
            else -> MainMobileActivity::class.java
        }

        val targetIntent = Intent(this, targetClass).apply {
            action = this@LauncherActivity.intent?.action
            data = this@LauncherActivity.intent?.data
            this@LauncherActivity.intent?.extras?.let { putExtras(it) }
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        startActivity(targetIntent)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }
}
