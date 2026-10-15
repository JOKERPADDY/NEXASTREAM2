package com.nexastream.app.providers.moviebox

import com.google.gson.annotations.SerializedName

data class SeasonInfo(
    @SerializedName("seasonNumber") val seasonNumber: Int = 1,
    @SerializedName("episodes") val episodesRaw: List<EpisodeInfo> = emptyList(),
    @SerializedName("episodeList") val episodeListRaw: List<EpisodeInfo> = emptyList()
) {
    val episodes: List<EpisodeInfo>
        get() = episodesRaw.ifEmpty { episodeListRaw }
}

data class EpisodeInfo(
    @SerializedName("episodeNumber") val episodeNumber: String = "1",
    @SerializedName("title") val title: String = "",
    @SerializedName("id") val id: String = ""
)

data class CastMember(
    @SerializedName("name") val name: String,
    @SerializedName("role") val role: String = "",
    @SerializedName("avatar") val avatar: String = ""
)

data class ResourceDetector(
    @SerializedName("resourceId") val resourceId: String,
    @SerializedName("name") val name: String = "",
    @SerializedName("totalEpisode") val totalEpisode: Int = 0
)

data class LanguageItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("subjectId") val subjectId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = ""
)

data class MovieSubject(
    @SerializedName("id") val id: String = "",
    @SerializedName("subjectId") val subjectId: String = id,
    @SerializedName("name") val name: String = "",
    @SerializedName("title") val title: String = name,
    @SerializedName("poster") val poster: String = "",
    @SerializedName("cover") val cover: String = poster,
    @SerializedName("description") val description: String = "",
    @SerializedName("rating") val rating: String = "8.5",
    @SerializedName("score") val score: String = rating,
    @SerializedName("year") val year: String = "2026",
    @SerializedName("releaseTime") val releaseTime: String = year,
    @SerializedName("genres") val genres: List<String> = emptyList(),
    @SerializedName("isTvShow") val isTvShow: Boolean = false,
    @SerializedName("subjectType") val subjectType: Int = if (isTvShow) 2 else 1,
    @SerializedName("totalSeasons") val totalSeasons: Int = 1,
    @SerializedName("duration") val duration: String = "120 min",
    @SerializedName("runtime") val runtime: String = duration,
    @SerializedName("cast") val cast: List<CastMember> = emptyList(),
    @SerializedName("dubs") val dubs: List<String> = listOf("English"),
    @SerializedName("languages") val languages: List<LanguageItem> = emptyList(),
    @SerializedName("seasonList") val seasonList: List<SeasonInfo> = emptyList(),
    @SerializedName("resourceDetectors") val resourceDetectors: List<ResourceDetector> = emptyList()
)

data class StreamItem(
    @SerializedName("quality") val quality: String = "1080p",
    @SerializedName("url") val url: String = "",
    @SerializedName("size") val size: String = "",
    @SerializedName("cookie") val cookie: String = ""
)

data class SubtitleItem(
    @SerializedName("language") val language: String = "",
    @SerializedName("lan") val lan: String = language,
    @SerializedName("lanName") val lanName: String = language,
    @SerializedName("url") val url: String = "",
    @SerializedName("format") val format: String = "vtt"
)

data class PlayInfo(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("url") val url: String = "",
    @SerializedName("quality") val quality: String = "1080p",
    @SerializedName("cookie") val cookie: String = "",
    @SerializedName("streamList") val streamList: List<StreamItem> = emptyList(),
    @SerializedName("subTitleList") val subTitleList: List<SubtitleItem> = emptyList(),
    @SerializedName("subtitles") val subtitles: List<SubtitleItem> = emptyList(),
    @SerializedName("resourceId") val resourceId: String = ""
)

data class SubjectDetailResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("message") val message: String = "success",
    @SerializedName("data") val data: MovieSubject? = null
)

data class EpisodesData(
    @SerializedName("seasons") val seasons: List<SeasonInfo> = emptyList()
)

data class EpisodesResponse(
    @SerializedName("code") val code: Int = 200,
    @SerializedName("message") val message: String = "success",
    @SerializedName("data") val data: EpisodesData? = null
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
