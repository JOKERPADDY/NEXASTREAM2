package com.nexastream.app.fragments.movie

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Movie
import com.nexastream.app.utils.ParentalControlUtils
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MovieViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase
) : ViewModel() {

    private val id: String = savedStateHandle.get<String>("id") ?: ""
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = combine(
        _state,
        database.movieDao().getByIdAsFlow(id)
    ) { state, movieDb ->
        if (state is State.SuccessLoading) {
            State.SuccessLoading(
                movie = state.movie.copy().apply {
                    movieDb?.let { merge(it) }
                }
            )
        } else state
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val movie: Movie) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getMovie()
    }


    fun getMovie() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            var movie = runCatching { UserPreferences.currentProvider.getMovie(id) }.getOrNull()
            if (movie == null || movie.title.isBlank()) {
                for (provider in com.nexastream.app.providers.Provider.providers.keys) {
                    if (provider == UserPreferences.currentProvider || provider == com.nexastream.app.providers.NexaHomeProvider) continue
                    val res = runCatching { provider.getMovie(id) }.getOrNull()
                    if (res != null && res.title.isNotBlank()) {
                        UserPreferences.currentProvider = provider
                        movie = res
                        break
                    }
                }
            }
            if (movie == null) {
                movie = UserPreferences.currentProvider.getMovie(id)
            }

            _state.emit(State.SuccessLoading(movie))
        } catch (e: Exception) {
            Log.e("MovieViewModel", "getMovie: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }
}
