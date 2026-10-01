package com.nexastream.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.WatchItem
import com.nexastream.app.utils.UserDataCache.toEpisode
import com.nexastream.app.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("RestrictedApi")
object WatchNextUtils {

    private const val TAG = "WatchNextUtils"

    fun updateWatchNext(context: Context, watchItem: WatchItem) {
        val providerName = UserPreferences.currentProvider?.name ?: "default"
        val contentId = when (watchItem) {
            is Movie -> watchItem.id
            is Episode -> watchItem.id
        }

        val existing = getProgram(context, contentId, providerName)

        if (watchItem.isWatched) {
            existing?.let { deleteProgramById(context, it.id) }
            return
        }

        val playbackPos = watchItem.watchHistory?.lastPlaybackPositionMillis ?: 0L
        val duration = watchItem.watchHistory?.durationMillis ?: 0L
        val watchNextType = if (playbackPos > 0L) {
            TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
        } else {
            TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
        }

        val builder = (existing?.let { WatchNextProgram.Builder(it) } ?: WatchNextProgram.Builder())
            .setInternalProviderId(providerName)
            .setContentId(contentId)
            .setWatchNextType(watchNextType)
            .setLastEngagementTimeUtcMillis(watchItem.watchHistory?.lastEngagementTimeUtcMillis ?: System.currentTimeMillis())
            .setLastPlaybackPositionMillis(playbackPos.toInt())
            .setDurationMillis(duration.toInt())

        when (watchItem) {
            is Movie -> {
                builder.setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setTitle(watchItem.title)
                    .setDescription(watchItem.overview)
                    .setPosterArtUri(watchItem.poster?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .setIntentUri(Uri.parse("nexastream://resolve?id=${watchItem.id}&type=movie"))
            }
            is Episode -> {
                val tvShowId = watchItem.tvShow?.id ?: watchItem.id
                val posterUri = (watchItem.poster ?: watchItem.tvShow?.poster)?.takeIf { it.isNotBlank() }
                val titleStr = watchItem.tvShow?.title ?: watchItem.title ?: ""
                val epDesc = "S${watchItem.season?.number ?: 0} E${watchItem.number}${if (!watchItem.title.isNull_or_empty()) " • ${watchItem.title}" else ""}"

                builder.setType(TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
                    .setTitle(titleStr)
                    .setSeasonNumber(watchItem.season?.number ?: 0)
                    .setEpisodeNumber(watchItem.number)
                    .setSeasonTitle(watchItem.season?.title)
                    .setEpisodeTitle(watchItem.title)
                    .setDescription(epDesc)
                    .setPosterArtUri(posterUri?.let { Uri.parse(it) })
                    .setIntentUri(Uri.parse("nexastream://resolve?id=${tvShowId}&type=tv_show&episodeId=${watchItem.id}"))
            }
        }

        val program = builder.build()

        try {
            if (existing != null) {
                updateProgram(context, existing.id, program)
            } else {
                insert(context, program)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update watch next program: ${e.message}", e)
        }
    }

    private fun String?.isNull_or_empty(): Boolean = this.isNullOrEmpty()

    fun programs(context: Context, providerName: String? = UserPreferences.currentProvider?.name): List<WatchNextProgram> {
        return try {
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                WatchNextProgram.PROJECTION,
                null,
                null,
                null
            )?.use { cursor ->
                val list = mutableListOf<WatchNextProgram>()
                while (cursor.moveToNext()) {
                    val program = WatchNextProgram.fromCursor(cursor)
                    if (providerName == null || program.internalProviderId == providerName) {
                        list.add(program)
                    }
                }
                list
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error querying programs: ${e.message}", e)
            emptyList()
        }
    }

    fun insert(context: Context, program: WatchNextProgram) {
        try {
            context.contentResolver.insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                program.toContentValues(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting program: ${e.message}", e)
        }
    }

    fun getProgram(context: Context, contentId: String, providerName: String? = UserPreferences.currentProvider?.name): WatchNextProgram? {
        return programs(context, providerName).find { it.contentId == contentId }
    }

    fun updateProgram(context: Context, id: Long, program: WatchNextProgram) {
        try {
            context.contentResolver.update(
                TvContractCompat.buildWatchNextProgramUri(id),
                program.toContentValues(),
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating program: ${e.message}", e)
        }
    }

    fun deleteProgramById(context: Context, id: Long) {
        try {
            context.contentResolver.delete(
                TvContractCompat.buildWatchNextProgramUri(id),
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting program by ID: ${e.message}", e)
        }
    }

    fun deleteProgramByContentId(context: Context, contentId: String) {
        val existing = getProgram(context, contentId)
        if (existing != null) {
            deleteProgramById(context, existing.id)
        }
    }

    suspend fun syncWatchNext(context: Context) = withContext(Dispatchers.IO) {
        val provider = UserPreferences.currentProvider ?: return@withContext
        try {
            val userData = UserDataCache.read(context, provider) ?: return@withContext
            val continueMovies = userData.continueWatchingMovies.map { it.toMovie() }.filter { !it.isWatched }
            val continueEpisodes = userData.continueWatchingEpisodes.map { it.toEpisode() }.filter { !it.isWatched }

            val allItems: List<WatchItem> = continueMovies + continueEpisodes

            allItems.forEach { item ->
                updateWatchNext(context, item)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncWatchNext: ${e.message}", e)
        }
    }
}
