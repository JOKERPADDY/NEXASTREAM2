package com.nexastream.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.utils.AiSearchEngine
import com.nexastream.app.utils.AiSearchPrompt
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val results: List<AppAdapter.Item> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isAiSearchEnabled: Boolean = true,
    val aiExplanation: String = "",
    val aiPrompts: List<AiSearchPrompt> = AiSearchEngine.defaultPrompts
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: HomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var lastQuery: String = ""

    fun toggleAiSearch() {
        val currentState = _uiState.value
        val newState = !currentState.isAiSearchEnabled
        _uiState.value = currentState.copy(isAiSearchEnabled = newState)
        if (lastQuery.isNotBlank()) {
            search(lastQuery)
        }
    }

    fun search(query: String) {
        lastQuery = query
        searchJob?.cancel()
        if (query.length < 2) {
            _uiState.value = _uiState.value.copy(results = emptyList(), aiExplanation = "")
            return
        }

        searchJob = viewModelScope.launch {
            delay(400) // Debounce
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val provider = UserPreferences.currentProvider
                val currentState = _uiState.value

                if (currentState.isAiSearchEnabled) {
                    val aiIntent = AiSearchEngine.parseQuery(query)
                    val lang = UserPreferences.currentLanguage ?: "en"
                    val aiDiscovered = AiSearchEngine.discoverByAiIntent(aiIntent, language = lang)

                    val providerResults = provider?.let { p ->
                        if (aiIntent.cleanedQuery.isNotBlank()) {
                            runCatching {
                                repository.search(p, aiIntent.cleanedQuery)
                            }.getOrDefault(emptyList())
                        } else emptyList()
                    } ?: emptyList()

                    val combined = (aiDiscovered + providerResults).distinctBy { item ->
                        when (item) {
                            is Movie -> "movie:${item.id}"
                            is TvShow -> "tv:${item.id}"
                            else -> item.hashCode().toString()
                        }
                    }

                    _uiState.value = SearchUiState(
                        results = combined,
                        isLoading = false,
                        isAiSearchEnabled = true,
                        aiExplanation = aiIntent.explanation
                    )
                } else {
                    val results = provider?.let { p ->
                        repository.search(p, query)
                    } ?: emptyList()

                    _uiState.value = SearchUiState(
                        results = results,
                        isLoading = false,
                        isAiSearchEnabled = false,
                        aiExplanation = ""
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
