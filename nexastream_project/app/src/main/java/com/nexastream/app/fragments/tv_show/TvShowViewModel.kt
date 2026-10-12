package com.nexastream.app.fragments.tv_show

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.ParentalControlUtils
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TvShowViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val database: AppDatabase
) : ViewModel() {

    private val id: String = savedStateHandle.get<String>("id") ?: ""
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = combine(
        _state,
        database.tvShowDao().getByIdAsFlow(id)
    ) { state, tvShowDb ->
        if (state is State.SuccessLoading) {
            State.SuccessLoading(
                tvShow = state.tvShow.copy().apply {
                    tvShowDb?.let { merge(it) }
                }
            )
        } else state
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val tvShow: TvShow) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getTvShow()
    }


    fun getTvShow() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val tvShow = UserPreferences.currentProvider.getTvShow(id)

            _state.emit(State.SuccessLoading(tvShow))
        } catch (e: Exception) {
            Log.e("TvShowViewModel", "getTvShow: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }
}
