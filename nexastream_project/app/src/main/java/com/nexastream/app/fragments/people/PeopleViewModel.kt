package com.nexastream.app.fragments.people

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase
) : ViewModel() {

    private val id: String = savedStateHandle.get<String>("id") ?: ""
    private val _state = MutableStateFlow<State>(State.Loading)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = _state.flatMapLatest { state ->
        if (state is State.SuccessLoading) {
            val shows = state.people.filmography.filterIsInstance<Show>()
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
                    
                    val mergedFilmography = shows.map { show ->
                        when (show) {
                            is Movie -> mDbMap[show.id]?.takeIf { !show.isSame(it) }?.let { show.copy().merge(it) } ?: show
                            is TvShow -> tvDbMap[show.id]?.takeIf { !show.isSame(it) }?.let { show.copy().merge(it) } ?: show
                            else -> show
                        }
                    }
                    State.SuccessLoading(
                        people = state.people.copy(
                            filmography = mergedFilmography
                        ),
                        hasMore = state.hasMore
                    )
                }
            }
        } else {
            flowOf(state)
        }
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val people: People, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    private var page = 1

    init {
        getPeople()
    }


    fun getPeople() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val people = UserPreferences.currentProvider!!.getPeople(id, 1)
            page = 1
            _state.emit(State.SuccessLoading(people, people.filmography.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("PeopleViewModel", "getPeople: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMorePeopleFilmography() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val people = UserPreferences.currentProvider!!.getPeople(id, page + 1)
                page += 1
                _state.emit(
                    State.SuccessLoading(
                        people = currentState.people.copy(
                            filmography = currentState.people.filmography + people.filmography
                        ),
                        hasMore = people.filmography.isNotEmpty()
                    )
                )
            } catch (e: Exception) {
                Log.e("PeopleViewModel", "loadMore: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
