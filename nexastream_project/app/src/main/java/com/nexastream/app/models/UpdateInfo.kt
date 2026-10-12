package com.nexastream.app.models

import com.google.gson.annotations.SerializedName

data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int = 0,
    @SerializedName("versionName") val versionName: String = "",
    @SerializedName("apkUrl") val apkUrl: String = "",
    @SerializedName("changelog") val changelog: String = "",
    @SerializedName("forceUpdate") val forceUpdate: Boolean = false,
    @SerializedName("supportTelegramUrl") val supportTelegramUrl: String = "https://t.me/NEXASTREAM2"
)
