package com.nexastream.app.providers

import com.nexastream.app.models.shorttv.*
import com.nexastream.app.utils.NetworkClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface ShortTvService {

    companion object {
        private const val BASE_URL = "https://api4.aoneroom.com/"

        fun build(): ShortTvService {
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(NetworkClient.default)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return retrofit.create(ShortTvService::class.java)
        }
    }

    @GET("wefeed-mobile-bff/shorts/get-info")
    suspend fun getShortTvInfo(
        @Query("subjectId") subjectId: String,
        @Query("host") host: String = "api4.aoneroom.com"
    ): BffBaseResp<BffShortTvInfo>

    @GET("wefeed-mobile-bff/shorts/mini-list/v2")
    suspend fun getShortTvEpisodes(
        @Query("subjectId") subjectId: String,
        @Query("page") page: Int = 1,
        @Query("perPage") perPage: Int = 100,
        @Query("pagerMode") pagerMode: Int = 1,
        @Query("host") host: String = "api4.aoneroom.com"
    ): BffBaseResp<BffShortTvEpisodeListData>

    @GET("wefeed-mobile-bff/home/operating")
    suspend fun getOperatingList(
        @Query("tabId") tabId: Int = 13,
        @Query("page") page: Int = 1,
        @Query("perPage") perPage: Int = 20,
        @Query("host") host: String = "api4.aoneroom.com"
    ): BffBaseResp<BffOperatingListData>
}
