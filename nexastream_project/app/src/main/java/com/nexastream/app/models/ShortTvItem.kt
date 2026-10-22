package com.nexastream.app.models

data class ShortTvItem(
    val id: String,
    val subjectId: String,
    val title: String,
    val description: String,
    val videoUrl: String,
    val posterUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val episodeNumber: Int = 1,
    val totalEpisodes: Int = 1,
    val mediaType: String = "tv",
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val speed: Float = 1.0f
)
