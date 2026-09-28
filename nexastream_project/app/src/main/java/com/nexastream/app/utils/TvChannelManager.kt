package com.nexastream.app.utils

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nexastream.app.R
import com.nexastream.app.models.Category
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@android.annotation.SuppressLint("RestrictedApi")
object TvChannelManager {

    private const val TAG = "TvChannelManager"

    suspend fun updateDefaultChannel(context: Context) = withContext(Dispatchers.IO) {
        try {
            val provider = UserPreferences.currentProvider ?: return@withContext
            val categories = HomeCacheStore.read(context, provider) ?: return@withContext
            val featured = categories.find { it.name == Category.FEATURED } ?: categories.firstOrNull() ?: return@withContext

            val channelId = getOrCreateChannel(context, "Featured", featured.name)
            if (channelId != -1L) {
                publishPrograms(context, channelId, featured)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update default channel: ${e.message}", e)
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private fun getOrCreateChannel(context: Context, internalId: String, displayName: String): Long {
        return try {
            val existingId = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(TvContractCompat.Channels._ID),
                "${TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID} = ?",
                arrayOf(internalId),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }

            if (existingId != null) return existingId

            val builder = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(displayName)
                .setInternalProviderId(internalId)
                .setAppLinkIntentUri(Uri.parse("nexastream://home"))

            val channelUri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                builder.build().toContentValues()
            )

            if (channelUri != null) {
                val id = ContentUris.parseId(channelUri)
                storeChannelLogo(context, id)
                TvContractCompat.requestChannelBrowsable(context, id)
                return id
            }

            -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get or create channel: ${e.message}", e)
            -1L
        }
    }

    private fun storeChannelLogo(context: Context, channelId: Long) {
        try {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
            ChannelLogoUtils.storeChannelLogo(context, channelId, bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store channel logo", e)
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private fun publishPrograms(context: Context, channelId: Long, category: Category) {
        try {
            // Clear existing programs
            context.contentResolver.delete(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                "${TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID} = ?",
                arrayOf(channelId.toString())
            )

            category.list.take(20).forEach { item ->
                val builder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                
                when (item) {
                    is Movie -> {
                        builder.setTitle(item.title)
                            .setDescription(item.overview)
                            .setPosterArtUri(Uri.parse(item.poster ?: item.banner ?: ""))
                            .setIntentUri(Uri.parse("nexastream://resolve?id=${item.id}&type=movie"))
                    }
                    is TvShow -> {
                        builder.setTitle(item.title)
                            .setDescription(item.overview)
                            .setPosterArtUri(Uri.parse(item.poster ?: item.banner ?: ""))
                            .setIntentUri(Uri.parse("nexastream://resolve?id=${item.id}&type=tv_show"))
                    }
                }

                context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    builder.build().toContentValues()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish programs: ${e.message}", e)
        }
    }
}
