package com.nexastream.app.fragments.player

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.debrid.RealDebridManager
import com.nexastream.app.models.Video
import com.nexastream.app.providers.IptvProvider
import com.nexastream.app.utils.EpisodeManager
import com.nexastream.app.utils.OpenSubtitles
import com.nexastream.app.utils.SubDL
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
    startImmediately: Boolean = true,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode

    private val initialVideoType = videoType
    private val initialId = id
    private var hasStarted = false

    init {
        if (startImmediately) start()
    }

    fun start() {
        if (hasStarted) return
        hasStarted = true
        getServers(initialVideoType, initialId)
        getSubtitles(initialVideoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)
        try {
            val servers = UserPreferences.currentProvider!!.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")

            // REORDERING logic: 
            // 1. User Preferred Server (if available)
            // 2. VixSrc (as default requested by user)
            // 3. Others (Provider default order)
            val preferredName = UserPreferences.preferredServerName
            val distinctServers = servers.distinctBy { it.id }
            val finalServers = if (UserPreferences.currentProvider is IptvProvider) {
                // IPTV providers already order mirrors using persisted health and latency.
                distinctServers
            } else distinctServers.sortedWith { s1, s2 ->
                val n1 = s1.name.uppercase()
                val n2 = s2.name.uppercase()
                
                if (!preferredName.isNullOrBlank() && preferredName.uppercase() != "VIXSRC") {
                    val pref = preferredName.uppercase()
                    if (n1 == pref && n2 != pref) return@sortedWith -1
                    if (n2 == pref && n1 != pref) return@sortedWith 1
                    if (n1.contains(pref) && !n2.contains(pref)) return@sortedWith -1
                    if (n2.contains(pref) && !n1.contains(pref)) return@sortedWith 1
                }
                
                // Priority 1: MovieBox (our primary server)
                if (n1.contains("MOVIEBOX") && !n2.contains("MOVIEBOX")) return@sortedWith -1
                if (n2.contains("MOVIEBOX") && !n1.contains("MOVIEBOX")) return@sortedWith 1

                // Priority 2: VixSrc (fallback)
                if (n1.contains("VIXSRC") && !n2.contains("VIXSRC")) return@sortedWith -1
                if (n2.contains("VIXSRC") && !n1.contains("VIXSRC")) return@sortedWith 1
                
                0
            }
            
            // LOG POTENZIATO: Mostra tutti i server disponibili per il player
            Log.i("NexaStream", "[SERVERS LIST] -> Provider: ${UserPreferences.currentProvider!!.name}")
            Log.i("NexaStream", "[SERVERS LIST] -> Found ${finalServers.size} servers: ${finalServers.joinToString { it.name }}")

            Log.d("PlayerViewModel", "Ricerca server completata: ${finalServers.size} server trovati")
            _state.emit(State.SuccessLoadingServers(finalServers))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        _state.emit(State.LoadingVideo(server))
        try {
            var video = UserPreferences.currentProvider!!.getVideo(server)
            if (video.source.isEmpty()) throw Exception("No source found")

            // Real-Debrid Unrestrict Logic
            if (UserPreferences.realDebridToken.isNotBlank() && !video.source.contains("m3u8") && !video.source.contains("mp4")) {
                val unrestrictedLink = RealDebridManager.unrestrict(video.source)
                if (unrestrictedLink != null) {
                    video = video.copy(source = unrestrictedLink)
                    Log.i("RealDebrid", "Link unrestricted: $unrestrictedLink")
                }
            }

            // LOGICA SOTTOTITOLI GLOBALE: 
            // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),
            // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.
            // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.
            val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
            val hasDefaultAlready = video.subtitles.any { it.default }

            if (!hasDefaultAlready && currentProviderLang != "es") {
                if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                    video.subtitles
                        .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                        ?.default = true
		}
            }

            Log.d("PlayerViewModel", "Estrazione video completata con successo")
            // Save as preferred server if it works
            UserPreferences.preferredServerName = server.name
            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        OpenSubtitles.search(
                            query = videoType.tvShow.title,
                            season = videoType.season.number,
                            episode = videoType.number,
                        )
                    }
                    is Video.Type.Movie -> {
                        OpenSubtitles.search(query = videoType.title)
                    }
                }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))
                
                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv"
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie"
                        )
                    }
                }
                
                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    fun autoDownloadSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        if (!UserPreferences.autoDownloadSubtitles) return@launch
        
        val langCode = UserPreferences.currentProvider?.language?.substringBefore("-") ?: "en"
        val subLanguageId = when (langCode) {
            "it" -> "ita"
            "es" -> "spa"
            "en" -> "eng"
            "fr" -> "fre"
            "de" -> "ger"
            else -> langCode
        }

        try {
            // Try OpenSubtitles first
            val openSubtitles = when (videoType) {
                is Video.Type.Episode -> {
                    OpenSubtitles.search(
                        query = videoType.tvShow.title,
                        season = videoType.season.number,
                        episode = videoType.number,
                        subLanguageId = subLanguageId
                    )
                }
                is Video.Type.Movie -> {
                    OpenSubtitles.search(query = videoType.title, subLanguageId = subLanguageId)
                }
            }.sortedByDescending { it.subDownloadsCnt?.toIntOrNull() ?: 0 }

            if (openSubtitles.isNotEmpty()) {
                downloadSubtitle(openSubtitles.first())
                return@launch
            }

            // Fallback to SubDL if OpenSubtitles has no results
            val subDlSubtitles = when (videoType) {
                is Video.Type.Episode -> {
                    SubDL.search(
                        filmName = videoType.tvShow.title,
                        seasonNumber = videoType.season.number,
                        episodeNumber = videoType.number,
                        type = "tv"
                    )
                }
                is Video.Type.Movie -> {
                    SubDL.search(filmName = videoType.title, type = "movie")
                }
            }.filter { 
                it.lang?.contains(langCode, ignoreCase = true) == true || 
                it.language?.contains(langCode, ignoreCase = true) == true 
            }

            if (subDlSubtitles.isNotEmpty()) {
                downloadSubDLSubtitle(subDlSubtitles.first())
            }
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Auto-download subtitles failed", e)
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
