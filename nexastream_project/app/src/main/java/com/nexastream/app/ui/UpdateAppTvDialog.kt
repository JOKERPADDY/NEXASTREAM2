package com.nexastream.app.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import com.nexastream.app.BuildConfig
import com.nexastream.app.databinding.DialogUpdateAppTvBinding
import com.nexastream.app.models.UpdateInfo

class UpdateAppTvDialog(
    context: Context,
    updateInfo: UpdateInfo
) : Dialog(context) {

    private val binding = DialogUpdateAppTvBinding.inflate(LayoutInflater.from(context))

    var isLoading: Boolean
        get() = binding.pbUpdateIsLoading.isVisible
        set(value) {
            binding.pbUpdateIsLoading.visibility = when {
                value -> View.VISIBLE
                else -> View.GONE
            }
        }

    init {
        setContentView(binding.root)

        if (updateInfo.forceUpdate) {
            binding.btnUpdateCancel.visibility = View.GONE
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        } else {
            binding.btnUpdateCancel.setOnClickListener {
                hide()
            }
        }

        binding.tvUpdateCurrentVersion.text = BuildConfig.VERSION_NAME
        binding.tvUpdateNewVersion.text = updateInfo.versionName

        var notes = if (updateInfo.changelog.isNotBlank()) {
            updateInfo.changelog
        } else {
            "New release available: v${updateInfo.versionName}"
        }
        if (!notes.contains("nexastream.app") && updateInfo.websiteUrl.isNotBlank()) {
            notes += "\n\n🌐 Or download through our website: ${updateInfo.websiteUrl}"
        }
        if (!notes.contains("t.me") && updateInfo.supportTelegramUrl.isNotBlank()) {
            notes += "\n💬 Join our Telegram: ${updateInfo.supportTelegramUrl}"
        }
        binding.tvUpdateReleaseNotes.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        binding.tvUpdateReleaseNotes.text = notes

        // Ensure update button gets default focus for TV remote usage
        binding.btnUpdate.requestFocus()

        window?.setLayout(
            context.resources.displayMetrics.widthPixels,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    fun setProgress(progress: Int) {
        binding.pbUpdateIsLoading.visibility = View.VISIBLE
        if (progress > 0) {
            binding.pbUpdateIsLoading.isIndeterminate = false
            binding.pbUpdateIsLoading.progress = progress
        } else {
            binding.pbUpdateIsLoading.isIndeterminate = true
        }
    }

    fun setOnUpdateClickListener(listener: (view: View) -> Unit) {
        binding.btnUpdate.setOnClickListener(listener)
    }
}
