package com.nexastream.app.providers.genga

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GengaApi {

    @GET("trending")
    suspend fun getTrending(): GengaHomeResponse

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 1
    ): GengaSearchResponse

    @GET("{category}")
    suspend fun getCategory(
        @Path("category") category: String,
        @Query("page") page: Int = 1
    ): GengaHomeResponse

    @GET("detail/{subjectId}")
    suspend fun getDetail(
        @Path("subjectId") subjectId: String
    ): GengaSubjectDetailResponse

    @GET("episodes/{seriesId}")
    suspend fun getEpisodes(
        @Path("seriesId") seriesId: String
    ): GengaEpisodesResponse

    @GET("stream/{subjectId}")
    suspend fun playInfo(
        @Path("subjectId") subjectId: String,
        @Query("season") season: Int = 1,
        @Query("episode") episode: Int = 1,
        @Query("quality") quality: String = "1080p",
        @Query("resource_id") resourceId: String? = null
    ): GengaPlayInfo

    companion object {
        fun create(baseUrl: String = "http://127.0.0.1:3001/"): GengaApi {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GengaApi::class.java)
        }
    }
}
