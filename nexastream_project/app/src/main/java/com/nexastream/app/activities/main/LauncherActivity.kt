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

        val intent = when {
            UserPreferences.forceTvUi -> {
                Intent(this, MainTvActivity::class.java)
            }
            BuildConfig.APP_LAYOUT == "tv" -> {
                Intent(this, MainTvActivity::class.java)
            }
            BuildConfig.APP_LAYOUT == "mobile" -> {
                Intent(this, MainMobileActivity::class.java)
            }
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> {
                Intent(this, MainTvActivity::class.java)
            }
            else -> {
                Intent(this, MainMobileActivity::class.java)
            }
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(intent)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }
}
