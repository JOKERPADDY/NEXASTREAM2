package com.nexastream.app.providers.moviebox

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface MovieBoxApi {

    @GET("home")
    suspend fun getHome(
        @Query("host") host: String = "themoviebox.xyz"
    ): BffResponse<BffHomeData>

    @GET("subject/trending")
    suspend fun getTrending(
        @Query("page") page: Int = 1,
        @Query("perPage") perPage: Int = 18
    ): Response<BffResponse<BffTrendingData>>

    @POST("subject/filter")
    suspend fun filterSubjects(
        @Body request: BffFilterRequest
    ): BffResponse<BffFilterData>

    @POST("subject/search")
    suspend fun searchSubjects(
        @Header("Authorization") authHeader: String,
        @Header("Referer") referer: String,
        @Body request: BffSearchRequest
    ): BffResponse<BffSearchData>

    @GET("detail")
    suspend fun getDetail(
        @Query("detailPath") detailPath: String
    ): BffResponse<BffDetailData>

    @GET("subject/play")
    suspend fun getPlayInfo(
        @Query("subjectId") subjectId: String? = null,
        @Query("se") season: Int = 0,
        @Query("ep") episode: Int = 0,
        @Query("detailPath") detailPath: String,
        @Query("streamSignType") streamSignType: Int = 1,
        @Query("supportCodecs[h264]") h264Codec: Int = 1,
        @Header("Referer") referer: String
    ): BffResponse<BffPlayData>

    @GET("subject/caption")
    suspend fun getCaptions(
        @Query("format") format: String = "MP4",
        @Query("id") streamId: String,
        @Query("subjectId") subjectId: String? = null,
        @Query("detailPath") detailPath: String,
        @Header("Referer") referer: String
    ): BffResponse<BffCaptionData>

    companion object {
        const val BASE_URL = "https://themoviebox.xyz/wefeed-h5api-bff/"

        fun create(): MovieBoxApi {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Referer", "https://themoviebox.xyz/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("X-Request-Lang", "en")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MovieBoxApi::class.java)
        }
    }
}
