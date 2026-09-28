package com.nexastream.app.providers

import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.models.SearchFilters
import kotlinx.coroutines.sync.Mutex

interface ProviderPortalUrl {
    val portalUrl: String
    val defaultPortalUrl: String
}

interface ProviderConfigUrl {
    val defaultBaseUrl: String

    suspend fun onChangeUrl(forceRefresh: Boolean = false): String
    val changeUrlMutex: Mutex
}

interface IptvProvider : Provider

interface Provider {

    val baseUrl: String
    val name: String
    val logo: String
    val language: String

    suspend fun getHome(): List<Category>

    suspend fun search(query: String, page: Int = 1, filters: SearchFilters? = null): List<AppAdapter.Item>

    suspend fun getMovies(page: Int = 1): List<Movie>

    suspend fun getTvShows(page: Int = 1): List<TvShow>

    suspend fun getMovie(id: String): Movie

    suspend fun getTvShow(id: String): TvShow

    suspend fun getEpisodesBySeason(seasonId: String): List<Episode>

    suspend fun getGenre(id: String, page: Int = 1): Genre

    suspend fun getPeople(id: String, page: Int = 1): People

    suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server>

    suspend fun getVideo(server: Video.Server): Video

    companion object {
        data class ProviderSupport(
            val movies: Boolean,
            val tvShows: Boolean
        )

        val providers: Map<Provider, ProviderSupport> = mapOf(
            NexaHomeProvider to ProviderSupport(movies = true, tvShows = true),
            SflixProvider to ProviderSupport(movies = true, tvShows = true),
            StreamingCommunityProvider("it") to ProviderSupport(movies = true, tvShows = true),
            StreamingCommunityProvider("en") to ProviderSupport(movies = true, tvShows = true),
            AnimeWorldProvider to ProviderSupport(movies = true, tvShows = true),
            MkissaProvider to ProviderSupport(movies = true, tvShows = true),
            MStreamProvider to ProviderSupport(movies = true, tvShows = true),
            PoseidonHD2Provider to ProviderSupport(movies = true, tvShows = true),
            LatanimeProvider to ProviderSupport(movies = true, tvShows = true),
            AnimeAv1Provider to ProviderSupport(movies = false, tvShows = true),
            Altadefinizione01Provider to ProviderSupport(movies = true, tvShows = true),
            AnimeUnityProvider to ProviderSupport(movies = true, tvShows = true),
            FrenchStreamProvider to ProviderSupport(movies = true, tvShows = true),
            EinschaltenProvider to ProviderSupport(movies = true, tvShows = false),
            FilmyOnlineCcProvider to ProviderSupport(movies = true, tvShows = true),
            FrembedProvider to ProviderSupport(movies = true, tvShows = true),
            FrenchMangaProvider to ProviderSupport(movies = false, tvShows = true),
            IptvOrgProvider to ProviderSupport(movies = false, tvShows = true),
            IptvSpainProvider to ProviderSupport(movies = false, tvShows = true),
            CdnLiveTvProvider to ProviderSupport(movies = false, tvShows = true),
            TvLibrefutbolProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvMxProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvArProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvDeProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvEsProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvFrProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvItProvider to ProviderSupport(movies = false, tvShows = true),
            PlutoTvUsProvider to ProviderSupport(movies = false, tvShows = true),
            CineCityProvider to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("de") to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("it") to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("fr") to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("es") to ProviderSupport(movies = false, tvShows = true),
            VavooProvider("pl") to ProviderSupport(movies = false, tvShows = true),
            HiAnimeProvider to ProviderSupport(movies = true, tvShows = true),
            SuperStreamProvider to ProviderSupport(movies = true, tvShows = true),
            StreamingItaProvider to ProviderSupport(movies = true, tvShows = true),
            PelisflixHdProvider to ProviderSupport(movies = true, tvShows = true),
            SoloLatinoProvider to ProviderSupport(movies = true, tvShows = true),
            FlixLatamProvider to ProviderSupport(movies = true, tvShows = true),
            FilmPalastProvider to ProviderSupport(movies = true, tvShows = false),
            PatrickTvProvider to ProviderSupport(movies = false, tvShows = true)
            // TmdbProvider to ProviderSupport(movies = true, tvShows = true) // TmdbProvider is a special case with language parameter
        )

        // Helper functions to check support
        fun supportsMovies(provider: Provider): Boolean {
            return providers[provider]?.movies ?: false
        }

        fun supportsTvShows(provider: Provider): Boolean {
            return providers[provider]?.tvShows ?: false
        }

        fun supportsDownloads(provider: Provider?): Boolean {
            if (provider == null || provider is IptvProvider) return false
            val isTv = runCatching {
                com.nexastream.app.NexastreamApp.instance.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            }.getOrDefault(false) || com.nexastream.app.utils.UserPreferences.forceTvUi

            if (isTv) return false

            if (provider is TmdbProvider) return true
            val support = providers[provider] ?: return false
            return support.movies || support.tvShows
        }
    }
}
