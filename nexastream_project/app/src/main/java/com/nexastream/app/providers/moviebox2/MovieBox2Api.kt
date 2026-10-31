package com.nexastream.app.providers.moviebox2

import android.content.Context
import com.nexastream.app.providers.moviebox.MovieBoxSignatureInterceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface MovieBox2Api {

    @GET("home")
    suspend fun getHome(
        @Query("host") host: String = "themoviebox.xyz"
    ): Map<String, Any?>

    @POST("subject/filter")
    suspend fun getCatalog(
        @Body body: Map<String, Any?>
    ): Map<String, Any?>

    @POST("subject/search")
    suspend fun search(
        @Body body: Map<String, Any?>
    ): Map<String, Any?>

    @GET("detail")
    suspend fun getDetail(
        @Query("detailPath") detailPath: String
    ): Map<String, Any?>

    @GET("subject/play")
    suspend fun getPlayback(
        @Query("detailPath") detailPath: String,
        @Query("se") season: Int = 0,
        @Query("ep") episode: Int = 0,
        @Query("streamSignType") streamSignType: Int = 1
    ): Map<String, Any?>

    @GET("subject/caption")
    suspend fun getCaptions(
        @Query("detailPath") detailPath: String,
        @Query("id") streamId: String,
        @Query("format") format: String = "MP4"
    ): Map<String, Any?>

    companion object {
        fun create(context: Context): MovieBox2Api {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(MovieBoxSignatureInterceptor(context))
                .build()

            return Retrofit.Builder()
                .baseUrl("https://api.inmoviebox.com/wefeed-h5api-bff/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MovieBox2Api::class.java)
        }
    }
}
