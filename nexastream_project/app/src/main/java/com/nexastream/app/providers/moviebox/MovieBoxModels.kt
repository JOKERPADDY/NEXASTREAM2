package com.nexastream.app.providers.moviebox

import com.google.gson.annotations.SerializedName

data class BffResponse<T>(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("msg") val msg: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: T? = null
)

data class BffCover(
    @SerializedName("url") val url: String? = null
)

data class BffStill(
    @SerializedName("url") val url: String? = null
)

data class BffDub(
    @SerializedName("subjectId") val subjectId: String? = null,
    @SerializedName("detailPath") val detailPath: String? = null,
    @SerializedName("lanCode") val lanCode: String? = null,
    @SerializedName("lanName") val lanName: String? = null,
    @SerializedName("original") val original: Boolean? = null
)

data class BffVideoAddress(
    @SerializedName("url") val url: String? = null
)

data class BffTrailer(
    @SerializedName("videoAddress") val videoAddress: BffVideoAddress? = null
)

data class BffSubject(
    @SerializedName("subjectId") val subjectId: String? = null,
    @SerializedName("detailPath") val detailPath: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("subjectType") val subjectType: Int? = null, // 1: movie, 2: series, 7: short_series
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("countryName") val countryName: String? = null,
    @SerializedName("imdbRatingValue") val imdbRatingValue: String? = null,
    @SerializedName("hasResource") val hasResource: Boolean? = null,
    @SerializedName("cover") val cover: BffCover? = null,
    @SerializedName("stills") val stills: BffStill? = null,
    @SerializedName("dubs") val dubs: List<BffDub>? = null,
    @SerializedName("trailer") val trailer: BffTrailer? = null
)

data class BffBannerItem(
    @SerializedName("subject") val subject: BffSubject? = null
)

data class BffBannerData(
    @SerializedName("items") val items: List<BffBannerItem>? = null
)

data class BffHomeItem(
    @SerializedName("type") val type: String? = null, // BANNER or SUBJECT
    @SerializedName("title") val title: String? = null,
    @SerializedName("banner") val banner: BffBannerData? = null,
    @SerializedName("subjects") val subjects: List<BffSubject>? = null
)

data class BffHomeData(
    @SerializedName("operatingList") val operatingList: List<BffHomeItem>? = null
)

data class BffPager(
    @SerializedName("hasMore") val hasMore: Boolean? = false,
    @SerializedName("nextPage") val nextPage: Int? = null
)

data class BffTrendingData(
    @SerializedName("subjectList") val subjectList: List<BffSubject>? = null,
    @SerializedName("pager") val pager: BffPager? = null
)

data class BffFilterData(
    @SerializedName("items") val items: List<BffSubject>? = null,
    @SerializedName("pager") val pager: BffPager? = null
)

data class BffFilterRequest(
    @SerializedName("channelId") val channelId: Int, // 1: movies, 2: series
    @SerializedName("page") val page: Int,
    @SerializedName("perPage") val perPage: Int = 28,
    @SerializedName("sort") val sort: String = "ForYou"
)

data class BffSearchData(
    @SerializedName("items") val items: List<BffSubject>? = null,
    @SerializedName("pager") val pager: BffPager? = null
)

data class BffSearchRequest(
    @SerializedName("keyword") val keyword: String,
    @SerializedName("page") val page: Int,
    @SerializedName("perPage") val perPage: Int = 28,
    @SerializedName("subjectType") val subjectType: Int = 0
)

data class BffResolution(
    @SerializedName("resolution") val resolution: Int? = null
)

data class BffSeasonResource(
    @SerializedName("se") val se: Int? = null,
    @SerializedName("maxEp") val maxEp: Int? = null,
    @SerializedName("resolutions") val resolutions: List<BffResolution>? = null
)

data class BffResource(
    @SerializedName("seasons") val seasons: List<BffSeasonResource>? = null
)

data class BffStar(
    @SerializedName("name") val name: String? = null,
    @SerializedName("character") val character: String? = null,
    @SerializedName("avatarUrl") val avatarUrl: String? = null
)

data class BffDetailData(
    @SerializedName("subject") val subject: BffSubject? = null,
    @SerializedName("resource") val resource: BffResource? = null,
    @SerializedName("stars") val stars: List<BffStar>? = null
)

data class BffStream(
    @SerializedName("id") val id: String? = null,
    @SerializedName("format") val format: String? = null, // "MP4"
    @SerializedName("resolutions") val resolutions: String? = null, // "1080", "720", "480", "360"
    @SerializedName("codecName") val codecName: String? = null,
    @SerializedName("size") val size: Long? = null, // bytes
    @SerializedName("duration") val duration: Long? = null, // seconds
    @SerializedName("url") val url: String? = null,
    @SerializedName("vipLocked") val vipLocked: Boolean? = false
)

data class BffPlayConfig(
    @SerializedName("maxResolution") val maxResolution: Int? = null
)

data class BffPlayData(
    @SerializedName("streams") val streams: List<BffStream>? = null,
    @SerializedName("dash") val dash: List<BffStream>? = null,
    @SerializedName("hls") val hls: List<BffStream>? = null,
    @SerializedName("limited") val limited: Boolean? = false,
    @SerializedName("vipLocked") val vipLocked: Boolean? = false,
    @SerializedName("playConfig") val playConfig: BffPlayConfig? = null
)

data class BffCaption(
    @SerializedName("lan") val lan: String? = null,
    @SerializedName("lanName") val lanName: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("delay") val delay: Long? = null
)

data class BffCaptionData(
    @SerializedName("captions") val captions: List<BffCaption>? = null
)
