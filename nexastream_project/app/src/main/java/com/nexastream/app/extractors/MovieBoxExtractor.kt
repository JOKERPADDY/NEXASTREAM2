package com.nexastream.app.extractors

import android.util.Log
import com.nexastream.app.models.Video
import com.nexastream.app.providers.moviebox.MovieBoxApi

class MovieBoxExtractor : Extractor() {

    override val name = "MovieBox"
    override val mainUrl = "http://127.0.0.1:3000"

    private val api by lazy { MovieBoxApi.create(baseUrl = "$mainUrl/") }

    fun server(videoType: Video.Type): Video.Server {
        val src = when (videoType) {
            is Video.Type.Movie -> "movie|${videoType.id}|${videoType.title}"
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
        Log.i("MovieBoxExtractor", "Extracting link: $link")

        val parts = if (link.contains("|")) link.split("|") else link.split(":")
        val type = parts.getOrNull(0) ?: "movie"

        val searchTitle = parts.getOrNull(2) ?: parts.getOrNull(1) ?: ""
        val season = if (type == "tv") parts.getOrNull(3)?.toIntOrNull() ?: 1 else 1
        val episode = if (type == "tv") parts.getOrNull(4)?.toIntOrNull() ?: 1 else 1

        Log.i("MovieBoxExtractor", "Searching MovieBox for: $searchTitle (Type: $type, S${season}E${episode})")

        var searchResult = try {
            api.search(searchTitle, page = 1)
        } catch (e: Exception) {
            Log.e("MovieBoxExtractor", "MovieBox initial search failed: ${e.message}")
            null
        }

        var subjects = searchResult?.data?.items ?: searchResult?.data?.list ?: emptyList()

        if (subjects.isEmpty() && searchTitle.contains(":")) {
            val cleanTitle = searchTitle.substringBefore(":").trim()
            Log.i("MovieBoxExtractor", "Retrying MovieBox search with stripped title: $cleanTitle")
            searchResult = try { api.search(cleanTitle, page = 1) } catch (_: Exception) { null }
            subjects = searchResult?.data?.items ?: searchResult?.data?.list ?: emptyList()
        }

        if (subjects.isEmpty()) {
            val strippedTitle = searchTitle.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
            if (strippedTitle != searchTitle) {
                Log.i("MovieBoxExtractor", "Retrying MovieBox search with alphanumeric title: $strippedTitle")
                searchResult = try { api.search(strippedTitle, page = 1) } catch (_: Exception) { null }
                subjects = searchResult?.data?.items ?: searchResult?.data?.list ?: emptyList()
            }
        }

        if (subjects.isEmpty()) {
            throw IllegalStateException("No subjects found on MovieBox for '$searchTitle'")
        }

        val bestSubject = subjects.firstOrNull {
            val titleMatches = it.name.contains(searchTitle, ignoreCase = true) || searchTitle.contains(it.name, ignoreCase = true)
            val isTv = type == "tv"
            titleMatches && (it.isTvShow == isTv)
        } ?: subjects.first()

        Log.i("MovieBoxExtractor", "Found MovieBox Subject: ${bestSubject.name} (ID: ${bestSubject.id})")

        val playInfo = try {
            api.playInfo(
                subjectId = bestSubject.id,
                season = season,
                episode = episode,
                quality = "1080p"
            )
        } catch (e: Exception) {
            Log.e("MovieBoxExtractor", "MovieBox playInfo failed: ${e.message}")
            throw e
        }

        val streamList = playInfo.streamList
        if (streamList.isEmpty()) {
            throw IllegalStateException("No stream available on MovieBox for subject ${bestSubject.id}")
        }

        val primaryStream = streamList.first()
        val headers = mutableMapOf<String, String>()
        if (primaryStream.cookie.isNotEmpty()) {
            headers["Cookie"] = primaryStream.cookie
        }

        val streams = streamList.map { stream ->
            val format = when {
                stream.url.contains(".m3u8", ignoreCase = true) -> Video.StreamFormat.M3U8
                stream.url.contains(".mpd", ignoreCase = true) -> Video.StreamFormat.DASH
                else -> Video.StreamFormat.MP4
            }
            Video.Stream(
                url = stream.url,
                resolution = stream.quality,
                format = format
            )
        }

        val subtitles = playInfo.subTitleList.map { sub ->
            Video.Subtitle(
                label = sub.language,
                file = sub.url
            )
        }

        return Video(
            source = primaryStream.url,
            subtitles = subtitles,
            headers = headers.ifEmpty { null },
            streams = streams
        )
    }
}
