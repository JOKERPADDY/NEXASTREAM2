package com.nexastream.app.providers.moviebox

import com.google.gson.annotations.SerializedName

data class SeasonInfo(
    @SerializedName("season") val season: Int,
    @SerializedName("episodeList") val episodeList: List<EpisodeInfo> = emptyList()
)

data class EpisodeInfo(
    @SerializedName("episode") val episode: Int,
    @SerializedName("title") val title: String = "",
    @SerializedName("duration") val duration: String = "",
    @SerializedName("stillPath") val stillPath: String = ""
)

data class CastMember(
    @SerializedName("name") val name: String,
    @SerializedName("role") val role: String = "",
    @SerializedName("avatar") val avatar: String = ""
)

data class ResourceDetector(
    @SerializedName("resourceId") val resourceId: String,
    @SerializedName("name") val name: String = ""
)

data class MovieSubject(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("poster") val poster: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("rating") val rating: String = "8.5",
    @SerializedName("year") val year: String = "2026",
    @SerializedName("genres") val genres: List<String> = emptyList(),
    @SerializedName("isTvShow") val isTvShow: Boolean = false,
    @SerializedName("totalSeasons") val totalSeasons: Int = 1,
    @SerializedName("duration") val duration: String = "120 min",
    @SerializedName("cast") val cast: List<CastMember> = emptyList(),
    @SerializedName("dubs") val dubs: List<String> = listOf("English"),
    @SerializedName("seasonList") val seasonList: List<SeasonInfo> = emptyList(),
    @SerializedName("resourceDetectors") val resourceDetectors: List<ResourceDetector> = emptyList()
)

data class StreamItem(
    @SerializedName("quality") val quality: String,
    @SerializedName("url") val url: String,
    @SerializedName("size") val size: String = "",
    @SerializedName("cookie") val cookie: String = ""
)

data class SubtitleItem(
    @SerializedName("language") val language: String,
    @SerializedName("url") val url: String,
    @SerializedName("format") val format: String = "vtt"
)

data class PlayInfo(
    @SerializedName("streamList") val streamList: List<StreamItem> = emptyList(),
    @SerializedName("subTitleList") val subTitleList: List<SubtitleItem> = emptyList(),
    @SerializedName("resourceId") val resourceId: String = ""
)

data class SubjectDetailResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("message") val message: String = "success",
    @SerializedName("data") val data: MovieSubject? = null
)

data class SearchData(
    @SerializedName("list") val list: List<MovieSubject> = emptyList(),
    @SerializedName("items") val items: List<MovieSubject> = emptyList()
)

data class SearchResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("data") val data: SearchData? = null
)

data class HomeListResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("data") val data: SearchData? = null
)

data class GetListRequest(
    @SerializedName("categoryId") val categoryId: Int,
    @SerializedName("page") val page: Int,
    @SerializedName("pageSize") val pageSize: Int
)
