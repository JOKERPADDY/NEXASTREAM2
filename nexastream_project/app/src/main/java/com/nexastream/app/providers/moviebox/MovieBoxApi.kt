package com.nexastream.app.providers.moviebox

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface MovieBoxApi {

    @GET("trending")
    suspend fun getTrending(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 24
    ): HomeListResponse

    @GET("movies")
    suspend fun getMoviesCategory(
        @Query("page") page: Int = 1
    ): HomeListResponse

    @GET("asian")
    suspend fun getSeriesCategory(
        @Query("page") page: Int = 1
    ): HomeListResponse

    @GET("anime")
    suspend fun getAnimeCategory(
        @Query("page") page: Int = 1
    ): HomeListResponse

    @GET("kids")
    suspend fun getKidsCategory(
        @Query("page") page: Int = 1
    ): HomeListResponse

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 24
    ): SearchResponse

    @GET("detail/{subjectId}")
    suspend fun getDetail(
        @Path("subjectId") subjectId: String
    ): SubjectDetailResponse

    @GET("episodes/{seriesId}")
    suspend fun getEpisodes(
        @Path("seriesId") seriesId: String
    ): EpisodesResponse

    @GET("stream/{subjectId}")
    suspend fun playInfo(
        @Path("subjectId") subjectId: String,
        @Query("season") season: Int = 1,
        @Query("episode") episode: Int = 1,
        @Query("quality") quality: String = "1080p",
        @Query("resource_id") resourceId: String? = null
    ): PlayInfo

    companion object {
        fun create(context: Context, baseUrl: String = "http://127.0.0.1:3001/"): MovieBoxApi {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MovieBoxApi::class.java)
        }
    }
}
