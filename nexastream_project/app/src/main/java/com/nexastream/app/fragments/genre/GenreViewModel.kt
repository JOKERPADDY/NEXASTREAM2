package com.nexastream.app.fragments.genre

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GenreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase
) : ViewModel() {

    private val id: String = savedStateHandle.get<String>("id") ?: ""
    private val _state = MutableStateFlow<State>(State.Loading)
    
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = _state.flatMapLatest { state ->
        if (state is State.SuccessLoading) {
            val shows = state.genre.shows
            if (shows.isEmpty()) {
                flowOf(state)
            } else {
                val movies = shows.filterIsInstance<Movie>()
                val tvShows = shows.filterIsInstance<TvShow>()
                
                val moviesDbFlow = if (movies.isEmpty()) flowOf(emptyList<Movie>()) else database.movieDao().getByIds(movies.map { it.id })
                val tvShowsDbFlow = if (tvShows.isEmpty()) flowOf(emptyList<TvShow>()) else database.tvShowDao().getByIds(tvShows.map { it.id })
                
                combine(moviesDbFlow, tvShowsDbFlow) { mDb, tvDb ->
                    val mDbMap = mDb.associateBy { it.id }
                    val tvDbMap = tvDb.associateBy { it.id }
                    
                    val mergedShows = shows.map { item ->
                        when (item) {
                            is Movie -> mDbMap[item.id]?.takeIf { !item.isSame(it) }?.let { item.copy().merge(it) } ?: item
                            is TvShow -> tvDbMap[item.id]?.takeIf { !item.isSame(it) }?.let { item.copy().merge(it) } ?: item
                            else -> item
                        }
                    }
                    State.SuccessLoading(
                        genre = state.genre.copy(shows = mergedShows),
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
        object Loading : State()
        object LoadingMore : State()
        data class SuccessLoading(val genre: Genre, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getGenre()
    }

    fun getGenre() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val genre = UserPreferences.currentProvider!!.getGenre(id, 1)

            page = 1

            _state.emit(State.SuccessLoading(genre, genre.shows.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("GenreViewModel", "getGenre: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMore() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val genre = UserPreferences.currentProvider!!.getGenre(id, page + 1)

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        genre = currentState.genre.copy(
                            shows = currentState.genre.shows + genre.shows
                        ),
                        hasMore = genre.shows.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("GenreViewModel", "loadMore: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
