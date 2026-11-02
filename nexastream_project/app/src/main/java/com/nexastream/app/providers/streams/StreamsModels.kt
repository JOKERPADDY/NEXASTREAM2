package com.nexastream.app.providers.streams

import com.google.gson.annotations.SerializedName

data class StreamsImage(
    @SerializedName("url") val url: String? = null
)

data class StreamsSubject(
    @SerializedName("subjectId") val subjectId: String? = null,
    @SerializedName("subjectType") val subjectType: Int? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("imdbRatingValue") val imdbRatingValue: String? = null,
    @SerializedName("cover") val cover: StreamsImage? = null,
    @SerializedName("stills") val stills: StreamsImage? = null,
    @SerializedName("detailPath") val detailPath: String? = null,
    @SerializedName("genre") val genre: String? = null
)

data class StreamsBannerItem(
    @SerializedName("subject") val subject: StreamsSubject? = null
)

data class StreamsBanner(
    @SerializedName("items") val items: List<StreamsBannerItem>? = null
)

data class StreamsHomeSection(
    @SerializedName("type") val type: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("banner") val banner: StreamsBanner? = null,
    @SerializedName("subjects") val subjects: List<StreamsSubject>? = null
)

data class StreamsHomeResponse(
    @SerializedName("operatingList") val operatingList: List<StreamsHomeSection>? = null
)

data class StreamsCatalogResponse(
    @SerializedName("items") val items: List<StreamsSubject>? = null,
    @SerializedName("next_page") val nextPage: Int? = null
)

data class StreamsSearchResponse(
    @SerializedName("items") val items: List<StreamsSubject>? = null,
    @SerializedName("next_page") val nextPage: Int? = null
)

data class StreamsSeasonRes(
    @SerializedName("se") val se: Int? = null,
    @SerializedName("maxEp") val maxEp: Int? = null
)

data class StreamsDetailResource(
    @SerializedName("seasons") val seasons: List<StreamsSeasonRes>? = null
)

data class StreamsDetailResponse(
    @SerializedName("subject") val subject: StreamsSubject? = null,
    @SerializedName("resource") val resource: StreamsDetailResource? = null
)

data class StreamsStream(
    @SerializedName("id") val id: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("vipLocked") val vipLocked: Boolean? = false,
    @SerializedName("resolutions") val resolutions: String? = null,
    @SerializedName("format") val format: String? = null
)

data class StreamsPlaybackResponse(
    @SerializedName("streams") val streams: List<StreamsStream>? = null
)

data class StreamsCaption(
    @SerializedName("url") val url: String? = null,
    @SerializedName("language") val lan: String? = null,
    @SerializedName("label") val lanName: String? = null
)
