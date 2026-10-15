package com.nexastream.app.providers.genga

import com.google.gson.annotations.SerializedName

data class GengaSubject(
    @SerializedName("id", alternate = ["subjectId"])
    val id: String = "",
    @SerializedName("name", alternate = ["title", "subjectName"])
    val name: String = "",
    @SerializedName("poster", alternate = ["cover", "img", "posterUrl"])
    val poster: String? = null,
    @SerializedName("description", alternate = ["overview", "summary", "intro"])
    val description: String? = null,
    @SerializedName("rating", alternate = ["score", "rate", "imdbRating"])
    val rating: String? = null,
    @SerializedName("year", alternate = ["releaseYear", "releaseDate", "releaseTime"])
    val year: String? = null,
    @SerializedName("isTvShow", alternate = ["is_tv", "tv"])
    val isTvShow: Boolean = false,
)

data class GengaDataWrapper(
    @SerializedName("items", alternate = ["results", "subjects"])
    val items: List<GengaSubject>? = null,
    @SerializedName("list")
    val list: List<GengaSubject>? = null,
)

data class GengaHomeResponse(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("data")
    val data: GengaDataWrapper? = null,
)

data class GengaSearchResponse(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("data")
    val data: GengaDataWrapper? = null,
)

data class GengaSubjectDetailResponse(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("data")
    val data: GengaSubject? = null,
    @SerializedName("seasonList", alternate = ["seasons"])
    val seasonList: List<GengaSeason>? = null,
)

data class GengaSeason(
    @SerializedName("seasonNumber", alternate = ["season", "se"])
    val seasonNumber: Int = 1,
    @SerializedName("poster", alternate = ["cover", "img"])
    val poster: String? = null,
    @SerializedName("episodeList", alternate = ["episodes", "eps", "allEp", "epList"])
    val episodeList: List<GengaEpisode>? = null,
)

data class GengaEpisode(
    @SerializedName("episodeNumber", alternate = ["episode", "ep"])
    val episodeNumberStr: String? = "1",
    @SerializedName("title")
    val title: String? = null,
    @SerializedName("poster", alternate = ["cover", "img"])
    val poster: String? = null,
    @SerializedName("id", alternate = ["episodeId"])
    val id: String? = null,
) {
    val episodeNumberInt: Int
        get() = episodeNumberStr?.toIntOrNull() ?: 1
}

data class GengaEpisodesResponse(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("data")
    val data: GengaseasonData? = null,
)

data class GengaseasonData(
    @SerializedName("seasons", alternate = ["seasonList", "list"])
    val seasons: List<GengaSeason>? = null,
)

data class GengaPlayInfo(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("data")
    val data: GengaPlayData? = null,
)

data class GengaPlayData(
    @SerializedName("streamUrl", alternate = ["url", "link", "playUrl"])
    val streamUrl: String? = null,
    @SerializedName("subtitles", alternate = ["subTitleList"])
    val subtitles: List<GengaSubtitle>? = null,
    @SerializedName("servers", alternate = ["streamList"])
    val servers: List<GengaServer>? = null,
)

data class GengaSubtitle(
    @SerializedName("language", alternate = ["lanName", "lan"])
    val language: String? = null,
    @SerializedName("url")
    val url: String? = null,
)

data class GengaServer(
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("url", alternate = ["src"])
    val url: String? = null,
    @SerializedName("id")
    val id: String? = null,
)
