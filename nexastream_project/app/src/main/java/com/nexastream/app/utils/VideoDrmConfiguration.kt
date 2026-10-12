package com.nexastream.app.utils

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.nexastream.app.models.Video

fun MediaItem.Builder.setVideoDrmConfiguration(
    drmConfiguration: Video.DrmConfiguration?,
): MediaItem.Builder {
    if (drmConfiguration != null) {
        setDrmConfiguration(
            MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                .setLicenseUri(Uri.parse(drmConfiguration.licenseUri))
                .setLicenseRequestHeaders(drmConfiguration.licenseRequestHeaders)
                .build(),
        )
    }
    return this
}
