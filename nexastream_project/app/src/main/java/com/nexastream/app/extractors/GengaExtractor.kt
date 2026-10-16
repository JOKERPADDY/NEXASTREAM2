package com.nexastream.app.extractors

import android.util.Log
import com.nexastream.app.models.Video
import com.nexastream.app.providers.genga.GengaApi
import com.nexastream.app.providers.genga.GengaPlayInfo

class GengaExtractor : Extractor() {

    override val name = "Genga"
    override val mainUrl = "http://127.0.0.1:3001"
    override val aliasUrls = listOf("movie|", "tv|", "movie:", "tv:")

    private val api by lazy { GengaApi.create("http://127.0.0.1:3001/") }

    fun server(videoType: Video.Type): Video.Server {
        val src = when (videoType) {
            is Video.Type.Movie -> "movie|${videoType.id}|${videoType.title}|0|0"
            is Video.Type.Episode -> "tv|${videoType.tvShow.id}|${videoType.tvShow.title}|${videoType.season.number}|${videoType.number}"
        }
        return Video.Server(
            id = name,
            name = name,
            src = src
        )
    }

    fun servers(videoType: Video.Type): List<Video.Server> {
        return listOf(server(videoType))
    }

    override suspend fun extract(link: String): Video {
        Log.i("GengaExtractor", "Extracting link: $link")

        val parts = if (link.contains("|")) link.split("|") else link.split(":")
        val type = parts.getOrNull(0) ?: "movie"
        val directSubjectId = if (parts.size >= 2 && parts[1].isNotEmpty() && parts[1] != "movie" && parts[1] != "tv") parts[1] else null
        val searchTitle = parts.getOrNull(2) ?: parts.getOrNull(1) ?: ""
        val season = if (type == "tv") parts.getOrNull(3)?.toIntOrNull() ?: 1 else 1
        val episode = if (type == "tv") parts.getOrNull(4)?.toIntOrNull() ?: 1 else 1

        // 1. Try direct subject ID first
        if (!directSubjectId.isNullOrEmpty()) {
            try {
                Log.i("GengaExtractor", "Trying direct playInfo for ID: $directSubjectId (S${season}E${episode})")
                val playInfo = api.playInfo(
                    subjectId = directSubjectId,
                    season = season,
                    episode = episode
                )
                val streamUrl = playInfo.data?.streamUrl.orEmpty()
                if (streamUrl.isNotEmpty()) {
                    Log.i("GengaExtractor", "Successfully extracted stream via direct subject ID: $directSubjectId")
                    return buildVideoResult(playInfo)
                }
            } catch (e: Exception) {
                Log.w("GengaExtractor", "Direct playInfo failed for ID $directSubjectId: ${e.message}")
            }
        }

        // 2. Search Genga for title if direct ID failed or absent
        Log.i("GengaExtractor", "Searching Genga for title: $searchTitle (Type: $type, S${season}E${episode})")
        val searchResult = try {
            api.search(searchTitle, page = 1)
        } catch (e: Exception) {
            Log.e("GengaExtractor", "Genga search failed: ${e.message}")
            null
        }

        val items = searchResult?.data?.items ?: searchResult?.data?.list ?: emptyList()
        if (items.isEmpty()) {
            throw IllegalStateException("No subjects found on Genga for '$searchTitle' (direct ID: $directSubjectId)")
        }

        val isTv = (type == "tv")
        val candidate = items.firstOrNull { 
            it.isTvShow == isTv && !it.name.contains("[Hindi]", ignoreCase = true) && !it.name.contains("(Hindi)", ignoreCase = true) 
        } ?: items.firstOrNull { it.isTvShow == isTv } ?: items.first()

        Log.i("GengaExtractor", "Trying candidate subject: ${candidate.name} (ID: ${candidate.id})")
        val playInfo = api.playInfo(
            subjectId = candidate.id,
            season = season,
            episode = episode
        )

        val streamUrl = playInfo.data?.streamUrl.orEmpty()
        if (streamUrl.isEmpty()) {
            throw IllegalStateException("No stream URL returned for candidate ${candidate.id} ('$searchTitle')")
        }

        return buildVideoResult(playInfo)
    }

    private fun buildVideoResult(playInfo: GengaPlayInfo): Video {
        val playData = playInfo.data ?: throw IllegalStateException("Empty playInfo data")
        val mainUrl = playData.streamUrl ?: throw IllegalStateException("Empty streamUrl")

        val subtitles = playData.subtitles.orEmpty().mapNotNull { sub ->
            val u = sub.url
            if (!u.isNullOrEmpty()) {
                Video.Subtitle(
                    label = sub.language ?: "English",
                    file = u
                )
            } else null
        }

        val headers = mapOf(
            "User-Agent" to "MovieBoxPro/16.2.1 (Android 12; Pixel 6)"
        )

        val format = when {
            mainUrl.contains(".m3u8", ignoreCase = true) -> Video.StreamFormat.M3U8
            mainUrl.contains(".mpd", ignoreCase = true) -> Video.StreamFormat.DASH
            else -> Video.StreamFormat.MP4
        }

        val mimeType = when (format) {
            Video.StreamFormat.M3U8 -> "application/x-mpegURL"
            Video.StreamFormat.DASH -> "application/dash+xml"
            Video.StreamFormat.MP4 -> "video/mp4"
        }

        val streams = listOf(
            Video.Stream(
                url = mainUrl,
                resolution = "1080p",
                format = format
            )
        )

        return Video(
            source = mainUrl,
            subtitles = subtitles,
            headers = headers,
            type = mimeType,
            streams = streams
        )
    }
}
