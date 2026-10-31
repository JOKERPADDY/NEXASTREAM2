package com.nexastream.app.providers.moviebox2

import com.google.gson.annotations.SerializedName

data class MovieBox2Title(
    @SerializedName("id") val id: String,
    @SerializedName("detail_path") val detailPath: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    @SerializedName("genres") val genres: List<String> = emptyList(),
    @SerializedName("poster_url") val posterUrl: String? = null,
    @SerializedName("backdrop_url") val backdropUrl: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("has_resource") val hasResource: Boolean = false
)

data class MovieBox2Page(
    @SerializedName("items") val items: List<MovieBox2Title>,
    @SerializedName("page") val page: Int = 1,
    @SerializedName("next_page") val nextPage: Int? = null,
    @SerializedName("has_more") val hasMore: Boolean = false
)

data class MovieBox2Section(
    @SerializedName("title") val title: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("items") val items: List<MovieBox2Title>
)

data class MovieBox2Home(
    @SerializedName("banners") val banners: List<MovieBox2Title>,
    @SerializedName("sections") val sections: List<MovieBox2Section>
)

data class MovieBox2Season(
    @SerializedName("number") val number: Int,
    @SerializedName("episode_count") val episodeCount: Int,
    @SerializedName("resolutions") val resolutions: List<Int> = emptyList()
)

data class MovieBox2Episode(
    @SerializedName("season") val season: Int,
    @SerializedName("number") val number: Int
)

data class MovieBox2Dub(
    @SerializedName("id") val id: String,
    @SerializedName("detail_path") val detailPath: String,
    @SerializedName("language") val language: String,
    @SerializedName("label") val label: String,
    @SerializedName("original") val original: Boolean
)

data class MovieBox2Detail(
    @SerializedName("title") val title: MovieBox2Title,
    @SerializedName("cast") val cast: List<Map<String, String?>> = emptyList(),
    @SerializedName("seasons") val seasons: List<MovieBox2Season> = emptyList(),
    @SerializedName("dubs") val dubs: List<MovieBox2Dub> = emptyList(),
    @SerializedName("trailer_url") val trailerUrl: String? = null
)

data class MovieBox2Stream(
    @SerializedName("id") val id: String,
    @SerializedName("format") val format: String,
    @SerializedName("resolutions") val resolutions: String,
    @SerializedName("codec") val codec: String? = null,
    @SerializedName("size_bytes") val sizeBytes: Long? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int? = null,
    @SerializedName("url") val url: String,
    @SerializedName("request_headers") val requestHeaders: Map<String, String> = emptyMap(),
    @SerializedName("vip_locked") val vipLocked: Boolean = false
)

data class MovieBox2Playback(
    @SerializedName("title_id") val titleId: String,
    @SerializedName("season") val season: Int,
    @SerializedName("episode") val episode: Int,
    @SerializedName("streams") val streams: List<MovieBox2Stream>,
    @SerializedName("limited") val limited: Boolean = false,
    @SerializedName("vip_locked") val vipLocked: Boolean = false,
    @SerializedName("max_resolution") val maxResolution: Int? = null
)

data class MovieBox2Caption(
    @SerializedName("language") val language: String,
    @SerializedName("label") val label: String,
    @SerializedName("format") val format: String,
    @SerializedName("url") val url: String,
    @SerializedName("size_bytes") val sizeBytes: Long? = null,
    @SerializedName("delay_ms") val delayMs: Int = 0,
    @SerializedName("request_headers") val requestHeaders: Map<String, String> = emptyMap()
)
