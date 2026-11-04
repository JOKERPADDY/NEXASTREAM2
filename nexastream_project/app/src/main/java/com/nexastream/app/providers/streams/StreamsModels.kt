package com.nexastream.app.providers.streams

import com.google.gson.annotations.SerializedName

data class StreamsImage(
    @SerializedName("url") val url: String? = null
)

data class StreamsSubject(
    @SerializedName(value = "subjectId", alternate = ["id"]) val subjectId: String? = null,
    @SerializedName("subjectType") val subjectType: Int? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName(value = "releaseDate", alternate = ["release_date"]) val releaseDate: String? = null,
    @SerializedName(value = "imdbRatingValue", alternate = ["rating"]) val imdbRatingValue: String? = null,
    @SerializedName("cover") val cover: StreamsImage? = null,
    @SerializedName("stills") val stills: StreamsImage? = null,
    @SerializedName(value = "detailPath", alternate = ["detail_path"]) val detailPath: String? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("genres") val genres: List<String>? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("duration_seconds") val durationSeconds: Long? = null,
    @SerializedName("poster_url") val posterUrl: String? = null,
    @SerializedName("backdrop_url") val backdropUrl: String? = null
) {
    val typeCode: Int?
        get() = subjectType ?: when (kind?.lowercase()) {
            "series", "tv", "short_series" -> 2
            "movie" -> 1
            else -> null
        }

    val isTv: Boolean
        get() = typeCode == 2 || typeCode == 7 || subjectType == 2 || subjectType == 7 || kind?.lowercase() in setOf("series", "tv", "short_series")

    val genreText: String?
        get() = genre ?: genres?.joinToString(",")

    val posterImageUrl: String?
        get() = cover?.url ?: posterUrl

    val backdropImageUrl: String?
        get() = stills?.url ?: backdropUrl ?: posterImageUrl
}

data class StreamsBannerItem(
    @SerializedName("subject") val subject: StreamsSubject? = null
)

data class StreamsBanner(
    @SerializedName("items") val items: List<StreamsBannerItem>? = null
)

data class StreamsHomeSection(
    @SerializedName(value = "type", alternate = ["kind"]) val type: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("banner") val banner: StreamsBanner? = null,
    @SerializedName(value = "subjects", alternate = ["items"]) val subjects: List<StreamsSubject>? = null
)

data class StreamsHomeResponse(
    @SerializedName("operatingList") private val legacyOperatingList: List<StreamsHomeSection>? = null,
    @SerializedName("sections") private val sections: List<StreamsHomeSection>? = null,
    @SerializedName("banners") private val banners: List<StreamsSubject>? = null
) {
    val operatingList: List<StreamsHomeSection>
        get() = legacyOperatingList ?: buildList {
            addAll(sections.orEmpty())
            banners?.takeIf { it.isNotEmpty() }?.let {
                add(StreamsHomeSection(type = "BANNER", banner = StreamsBanner(it.map(::StreamsBannerItem))))
            }
        }
}

data class StreamsCatalogResponse(
    @SerializedName("items") val items: List<StreamsSubject>? = null,
    @SerializedName("next_page") val nextPage: Int? = null
)

data class StreamsSearchResponse(
    @SerializedName("items") val items: List<StreamsSubject>? = null,
    @SerializedName("next_page") val nextPage: Int? = null
)

data class StreamsSeasonRes(
    @SerializedName(value = "se", alternate = ["number"]) val se: Int? = null,
    @SerializedName(value = "maxEp", alternate = ["episode_count"]) val maxEp: Int? = null
)

data class StreamsDetailResource(
    @SerializedName("seasons") val seasons: List<StreamsSeasonRes>? = null
)

data class StreamsDetailResponse(
    @SerializedName(value = "subject", alternate = ["title"]) val subject: StreamsSubject? = null,
    @SerializedName("resource") val resource: StreamsDetailResource? = null,
    @SerializedName("seasons") val seasons: List<StreamsSeasonRes>? = null
) {
    val seasonResources: List<StreamsSeasonRes>
        get() = resource?.seasons ?: seasons.orEmpty()
}

data class StreamsStream(
    @SerializedName("id") val id: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName(value = "vipLocked", alternate = ["vip_locked"]) val vipLocked: Boolean? = false,
    @SerializedName("resolutions") val resolutions: String? = null,
    @SerializedName("format") val format: String? = null,
    @SerializedName(value = "request_headers", alternate = ["requestHeaders"])
    val requestHeaders: Map<String, String>? = null
)

data class StreamsPlaybackResponse(
    @SerializedName("streams") val streams: List<StreamsStream>? = null
)

data class StreamsCaption(
    @SerializedName("url") val url: String? = null,
    @SerializedName("language") val lan: String? = null,
    @SerializedName("label") val lanName: String? = null
)
