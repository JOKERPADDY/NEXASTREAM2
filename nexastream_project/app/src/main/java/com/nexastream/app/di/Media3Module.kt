package com.nexastream.app.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexastream.app.utils.DnsResolver
import com.nexastream.app.utils.HeaderInterceptingDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Singleton

@UnstableApi
@Module
@InstallIn(SingletonComponent::class)
object Media3Module {

    @Provides
    @Singleton
    fun provideDatabaseProvider(@ApplicationContext context: Context): DatabaseProvider {
        return StandaloneDatabaseProvider(context)
    }

    @Provides
    @Singleton
    fun provideCache(@ApplicationContext context: Context, databaseProvider: DatabaseProvider): Cache {
        val downloadRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val downloadDir = File(downloadRoot, "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()
        return SimpleCache(downloadDir, NoOpCacheEvictor(), databaseProvider)
    }

    @Provides
    @Singleton
    fun provideHttpDataSourceFactory(): HttpDataSource.Factory {
        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        return OkHttpDataSource.Factory(client)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
    }

    @Provides
    @Singleton
    fun provideDataSourceFactory(
        @ApplicationContext context: Context,
        httpDataSourceFactory: HttpDataSource.Factory,
        cache: Cache
    ): DataSource.Factory {
        val interceptingFactory = HeaderInterceptingDataSource.Factory(httpDataSourceFactory, context)
        val upstreamFactory = DefaultDataSource.Factory(context, interceptingFactory)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheReadDataSourceFactory(DefaultDataSource.Factory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
