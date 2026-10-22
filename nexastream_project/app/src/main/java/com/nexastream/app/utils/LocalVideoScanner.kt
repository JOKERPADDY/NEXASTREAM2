package com.nexastream.app.utils

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.nexastream.app.models.LocalVideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LocalVideoScanner {

    private const val TAG = "LocalVideoScanner"

    suspend fun scanLocalVideos(context: Context): List<LocalVideoFile> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<LocalVideoFile>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleColumn = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val displayNameColumn = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val sizeColumn = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val mimeTypeColumn = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val bucketColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val rawTitle = if (titleColumn >= 0) cursor.getString(titleColumn) else null
                    val displayName = if (displayNameColumn >= 0) cursor.getString(displayNameColumn) else null
                    val path = if (dataColumn >= 0) cursor.getString(dataColumn) ?: "" else ""
                    val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L
                    val duration = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
                    val dateModified = if (dateModifiedColumn >= 0) cursor.getString(dateModifiedColumn)?.toLongOrNull() ?: 0L else 0L
                    val mimeType = if (mimeTypeColumn >= 0) cursor.getString(mimeTypeColumn) else null
                    val folderName = if (bucketColumn >= 0) cursor.getString(bucketColumn) else null

                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val title = displayName ?: rawTitle ?: "Video_$id"
                    val fileName = displayName ?: path.substringAfterLast('/')

                    videos.add(
                        LocalVideoFile(
                            id = id,
                            title = title,
                            fileName = fileName,
                            path = path,
                            contentUri = contentUri,
                            size = size,
                            duration = duration,
                            dateModified = dateModified,
                            mimeType = mimeType ?: "video/*",
                            folderName = folderName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning local videos", e)
        }

        videos
    }
}
