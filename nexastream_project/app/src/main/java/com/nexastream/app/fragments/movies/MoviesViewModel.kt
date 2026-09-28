package com.nexastream.app.fragments.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Movie
import com.nexastream.app.utils.ParentalControlUtils
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.ProviderChangeNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val database: AppDatabase
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getMovies()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = _state.flatMapLatest { state ->
        if (state is State.SuccessLoading) {
            if (state.movies.isEmpty()) {
                flowOf(state)
            } else {
                database.movieDao().getByIds(state.movies.map { it.id }).map { moviesDb ->
                    val moviesById = moviesDb.associateBy { it.id }
                    State.SuccessLoading(
                        movies = state.movies.map { movie ->
                            moviesById[movie.id]?.takeIf { !movie.isSame(it) }?.let { movie.copy().merge(it) } ?: movie
                        },
                        hasMore = state.hasMore
                    )
                }
            }
        } else {
            flowOf(state)
        }
    }.flowOn(Dispatchers.IO)

    private var page = 1

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val movies: List<Movie>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getMovies()
    }


    fun getMovies() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val movies = ParentalControlUtils.filterItems(
                UserPreferences.currentProvider!!.getMovies()
            ).filterIsInstance<Movie>()

            page = 1

            _state.emit(State.SuccessLoading(movies, movies.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("MoviesViewModel", "getMovies: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreMovies() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val movies = ParentalControlUtils.filterItems(
                    UserPreferences.currentProvider!!.getMovies(page + 1)
                ).filterIsInstance<Movie>()

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        movies = currentState.movies + movies,
                        hasMore = movies.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("MoviesViewModel", "loadMoreMovies: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
