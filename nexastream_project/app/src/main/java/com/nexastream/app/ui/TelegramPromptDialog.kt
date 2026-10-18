package com.nexastream.app.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Window
import android.view.WindowManager
import com.nexastream.app.databinding.DialogTelegramPromptBinding

class TelegramPromptDialog(context: Context) : Dialog(context) {

    private val binding = DialogTelegramPromptBinding.inflate(LayoutInflater.from(context))

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val width = (context.resources.displayMetrics.widthPixels * 0.88).toInt()
        window?.setLayout(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val titleText = "Join the NexaStream Community"
        val spannable = SpannableString(titleText)
        val startIndex = titleText.indexOf("NexaStream")
        if (startIndex != -1) {
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#E50914")),
                startIndex,
                startIndex + "NexaStream".length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.tvTelegramDialogTitle.text = spannable

        binding.btnJoinTelegram.setOnClickListener {
            try {
                val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=NEXASTREAM2"))
                context.startActivity(tgIntent)
            } catch (_: Exception) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/NEXASTREAM2"))
                context.startActivity(intent)
            }
            dismiss()
        }

        binding.tvMaybeLater.setOnClickListener {
            dismiss()
        }

        binding.btnTelegramClose.setOnClickListener {
            dismiss()
        }
    }
}
