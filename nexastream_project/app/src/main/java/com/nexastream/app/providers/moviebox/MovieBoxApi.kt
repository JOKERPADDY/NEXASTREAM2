package com.nexastream.app.providers.moviebox

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface MovieBoxApi {

    @GET("trending")
    suspend fun getTrending(): HomeListResponse

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 1
    ): SearchResponse

    @GET("detail/{subjectId}")
    suspend fun getDetail(
        @Path("subjectId") subjectId: String
    ): SubjectDetailResponse

    @GET("stream/{subjectId}")
    suspend fun playInfo(
        @Path("subjectId") subjectId: String,
        @Query("season") season: Int = 1,
        @Query("episode") episode: Int = 1,
        @Query("quality") quality: String = "1080p",
        @Query("resource_id") resourceId: String? = null
    ): PlayInfo

    companion object {
        fun create(baseUrl: String = "http://127.0.0.1:3000/"): MovieBoxApi {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MovieBoxApi::class.java)
        }
    }
}
