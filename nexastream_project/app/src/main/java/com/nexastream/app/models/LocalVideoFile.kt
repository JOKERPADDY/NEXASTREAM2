package com.nexastream.app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class LocalVideoFile(
    val id: Long,
    val title: String,
    val fileName: String,
    val path: String,
    val contentUri: String,
    val size: Long = 0L,
    val duration: Long = 0L,
    val dateModified: Long = 0L,
    val mimeType: String? = null,
    val folderName: String? = null
) : Parcelable, Serializable
