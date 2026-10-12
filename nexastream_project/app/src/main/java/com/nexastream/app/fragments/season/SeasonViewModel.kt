package com.nexastream.app.fragments.season

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Episode
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SeasonViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val database: AppDatabase
) : ViewModel() {

    private val seasonId: String = savedStateHandle.get<String>("seasonId") ?: ""
    private val _state = MutableStateFlow<State>(State.Loading)
    
    val state: Flow<State> = _state.combine(
        database.episodeDao().getBySeasonIdAsFlow(seasonId)
    ) { currentState, episodesDb ->
        if (currentState is State.SuccessLoading) {
            val episodesDbMap = episodesDb.associateBy { e: Episode -> e.id }
            State.SuccessLoading(
                episodes = currentState.episodes.map { episode: Episode ->
                    episodesDbMap[episode.id]?.takeIf { eDb: Episode -> !episode.isSame(eDb) }?.let { eDb: Episode ->
                        episode.copy().merge(eDb)
                    } ?: episode
                }
            )
        } else currentState
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val episodes: List<Episode>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getEpisodes()
    }


    fun getEpisodes() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)
        try {
            val episodes = UserPreferences.currentProvider.getEpisodesBySeason(seasonId)
            
            // Inject TV Show and Season info into episodes if missing
            val tvShowId: String = savedStateHandle.get<String>("tvShowId") ?: ""
            val tvShowTitle: String = savedStateHandle.get<String>("tvShowTitle") ?: ""
            val tvShowPoster: String? = savedStateHandle.get<String>("tvShowPoster")
            val tvShowBanner: String? = savedStateHandle.get<String>("tvShowBanner")
            val seasonNumber: Int = savedStateHandle.get<Int>("seasonNumber") ?: 1
            val seasonTitle: String? = savedStateHandle.get<String>("seasonTitle")

            val tvShow = com.nexastream.app.models.TvShow(
                id = tvShowId,
                title = tvShowTitle,
                poster = tvShowPoster,
                banner = tvShowBanner
            )
            val season = com.nexastream.app.models.Season(
                id = seasonId,
                number = seasonNumber,
                title = seasonTitle
            ).apply { this.tvShow = tvShow }

            episodes.forEach {
                it.tvShow = it.tvShow ?: tvShow
                it.season = it.season ?: season
            }

            _state.emit(State.SuccessLoading(episodes))
        } catch (e: Exception) {
            Log.e("SeasonViewModel", "getEpisodes: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }
}
