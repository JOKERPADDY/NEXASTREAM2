package com.nexastream.app.providers.streams

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface StreamsApi {

    @GET("v1/home")
    suspend fun getHome(): StreamsHomeResponse

    @GET("v1/catalog/{name}")
    suspend fun getCatalog(
        @Path("name") category: String,
        @Query("page") page: Int
    ): StreamsCatalogResponse

    @GET("v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int
    ): StreamsSearchResponse

    @GET("v1/search/suggestions")
    suspend fun getSuggestions(
        @Query("q") query: String
    ): List<String>

    @GET("v1/search/popular")
    suspend fun getPopularSearch(): List<String>

    @GET("v1/titles/{path}")
    suspend fun getDetail(
        @Path("path") path: String
    ): StreamsDetailResponse

    @GET("v1/titles/{path}/playback")
    suspend fun getPlayback(
        @Path("path") path: String,
        @Query("season") season: Int = 0,
        @Query("episode") episode: Int = 0
    ): StreamsPlaybackResponse

    @GET("v1/titles/{path}/captions")
    suspend fun getCaptions(
        @Path("path") path: String,
        @Query("stream_id") streamId: String?,
        @Query("season") season: Int = 0,
        @Query("episode") episode: Int = 0
    ): List<StreamsCaption>

    companion object {
        private const val BASE_URL = "https://movie-box-sepia-six.vercel.app/"

        fun create(): StreamsApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Referer", "https://themoviebox.xyz/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(StreamsApi::class.java)
        }
    }
}
