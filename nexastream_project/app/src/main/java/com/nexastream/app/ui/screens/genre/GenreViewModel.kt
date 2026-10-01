package com.nexastream.app.ui.screens.genre

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Genre
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

data class GenreUiState(
    val isLoading: Boolean = false,
    val genre: Genre? = null,
    val error: String? = null
)

@HiltViewModel
class GenreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: HomeRepository
) : ViewModel() {

    private val id: String = URLDecoder.decode(checkNotNull(savedStateHandle["id"]), "UTF-8")
    private val name: String = URLDecoder.decode(checkNotNull(savedStateHandle["name"]), "UTF-8")

    private val _uiState = MutableStateFlow(GenreUiState())
    val uiState: StateFlow<GenreUiState> = _uiState

    init {
        loadGenre()
    }

    private fun loadGenre() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val provider = UserPreferences.currentProvider
                val genre = if (id == "tmdb_recommended_for_you" || id == "Recommended For You" || id == "✨ Recommended For You") {
                    runCatching {
                        provider?.getGenre(id, 1) ?: com.nexastream.app.providers.NexaHomeProvider.getGenre("tmdb_recommended_for_you", 1)
                    }.getOrElse {
                        com.nexastream.app.providers.NexaHomeProvider.getGenre("tmdb_recommended_for_you", 1)
                    }
                } else {
                    provider?.getGenre(id, 1) ?: Genre(id = id, name = name, shows = emptyList())
                }
                _uiState.value = GenreUiState(genre = genre, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = GenreUiState(error = e.message, isLoading = false)
            }
        }
    }
}
