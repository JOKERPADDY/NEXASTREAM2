package com.nexastream.app.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.databinding.FragmentSearchMobileBinding
import com.nexastream.app.models.Category
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.TvShow
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.VoiceRecognitionHelper
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.hideKeyboard
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.nexastream.app.providers.IptvProvider

@AndroidEntryPoint
class SearchMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentSearchMobileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchViewModel by viewModels()
    private var appAdapter = AppAdapter()
    private val voiceHelper by lazy {
        VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.setText(query)
                viewModel.search(query)
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                binding.btnSearchVoice.clearAnimation()
                val isIptv = UserPreferences.currentProvider is IptvProvider
                binding.etSearch.hint = getString(if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint)
            },
            onListeningStateChanged = { isListening ->
                binding.btnSearchVoice.startAnimation(AlphaAnimation(1f, 0.3f).apply {
                    duration = 500
                    repeatCount = Animation.INFINITE
                    repeatMode = Animation.REVERSE
                })
                binding.etSearch.hint = getString(R.string.voice_prompt)
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeSearch()

        binding.swAiSearch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != viewModel.isAiSearchEnabled.value) {
                viewModel.toggleAiSearch()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isAiSearchEnabled.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { isEnabled ->
                if (binding.swAiSearch.isChecked != isEnabled) {
                    binding.swAiSearch.isChecked = isEnabled
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.aiExplanation.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { explanation ->
                if (explanation.isNotBlank() && viewModel.isAiSearchEnabled.value) {
                    binding.tvAiExplanation.text = explanation
                    binding.tvAiExplanation.visibility = View.VISIBLE
                } else {
                    binding.tvAiExplanation.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filters.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { filters ->
                val isActive = !filters.isDefault()
                binding.btnSearchFilter.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isActive) com.nexastream.app.utils.ThemeManager.palette(UserPreferences.selectedTheme).mobileNavActive
                    else android.graphics.Color.WHITE
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                viewModel.searchHistory.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED),
                viewModel.trending.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED),
                viewModel.topRated.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED),
                viewModel.airingToday.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            ) { history, trending, topRated, airingToday ->
                if (viewModel.query.isEmpty() && viewModel.filters.value.isDefault()) {
                    val categories = mutableListOf<Category>()
                    if (history.isNotEmpty()) {
                        val recentItems = history.map { searchHist ->
                            if (!searchHist.poster.isNullOrEmpty()) {
                                if (searchHist.mediaType == "tv") {
                                    TvShow(
                                        id = searchHist.mediaId ?: searchHist.query,
                                        title = searchHist.query,
                                        poster = searchHist.poster
                                    ).apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
                                } else {
                                    Movie(
                                        id = searchHist.mediaId ?: searchHist.query,
                                        title = searchHist.query,
                                        poster = searchHist.poster
                                    ).apply { itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM }
                                }
                            } else {
                                Genre(searchHist.query, searchHist.query).apply {
                                    itemType = AppAdapter.Type.GENRE_GRID_MOBILE_ITEM
                                }
                            }
                        }
                        categories.add(Category(getString(R.string.search_recent), recentItems)
                            .apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
                    }
                    if (trending.isNotEmpty()) {
                        categories.add(Category("Trending Now", trending.onEach {
                            when (it) {
                                is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                            }
                        }).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
                    }
                    if (topRated.isNotEmpty()) {
                        categories.add(Category("Top Rated", topRated.onEach {
                            when (it) {
                                is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                            }
                        }).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
                    }
                    if (airingToday.isNotEmpty()) {
                        categories.add(Category("Airing Today", airingToday.onEach {
                            when (it) {
                                is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                            }
                        }).apply { itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM })
                    }
                    categories
                } else emptyList()
            }.collect { categories ->
                if (viewModel.query.isEmpty() && viewModel.filters.value.isDefault() && categories.isNotEmpty()) {
                    appAdapter.submitList(categories)
                    appAdapter.onGenreClickListener = { genre ->
                        binding.etSearch.setText(genre.name)
                        viewModel.search(genre.name)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is SearchState.Searching, is SearchState.GlobalSearching -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is SearchState.SearchingMore -> appAdapter.isLoading = true
                    is SearchState.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is SearchState.SuccessGlobalSearching -> {
                        displayGlobalSearch(state.providerResults)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is SearchState.FailedSearching -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            viewModel.search(viewModel.query)
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.search(viewModel.query) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        _binding = null
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)

        binding.etSearch.apply {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val query = binding.etSearch.text.toString()
                    hideKeyboard()
                    if (query.isBlank()) {
                        Toast.makeText(requireContext(), getString(R.string.search_empty_query), Toast.LENGTH_SHORT).show()
                        return@setOnEditorActionListener true
                    }
                    if (binding.swGlobalSearch.isChecked) {
                        val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
                        viewModel.searchGlobal(query, currentLanguage)
                    } else {
                        viewModel.search(query)
                    }
                    return@setOnEditorActionListener true
                }
                false
            }

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if(s.isNullOrBlank()){
                        val isIptv = UserPreferences.currentProvider is IptvProvider
                        binding.etSearch.hint = getString(if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint)
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        binding.btnSearchVoice.apply {
            requestFocus()
            visibility = if (voiceHelper.isAvailable()) View.VISIBLE else View.GONE
            setOnClickListener { if (!voiceHelper.isListening) voiceHelper.startWithPermissionCheck() }
        }

        binding.btnSearchFilter.setOnClickListener {
            SearchFilterBottomSheet().show(childFragmentManager, "SearchFilter")
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            val isIptv = UserPreferences.currentProvider is IptvProvider
            binding.etSearch.hint = getString(if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint)
            viewModel.search("")
        }

        binding.rvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(SpacingItemDecoration(10.dp(requireContext())))
        }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        appAdapter.submitList(list.onEach {
            when (it) {
                is Genre -> it.itemType = AppAdapter.Type.GENRE_GRID_MOBILE_ITEM
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                is People -> it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
            }
        })
        if (hasMore && viewModel.query != "") {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun displayGlobalSearch(providerResults: List<SearchProviderResult>) {
        val allItems = mutableListOf<AppAdapter.Item>()
        providerResults.forEach { providerResult ->
            val headerTitle = when (val state = providerResult.state) {
                is SearchProviderResult.State.Loading -> "${providerResult.provider.name} - ${getString(R.string.searching)}"
                is SearchProviderResult.State.Error -> "${providerResult.provider.name} - ${getString(R.string.search_error)}"
                is SearchProviderResult.State.Success -> {
                    val count = state.results.size
                    val resultText = if (count == 1) getString(R.string.result) else getString(R.string.results)
                    "${providerResult.provider.name} - $count $resultText"
                }
            }
            val header = Category(name = headerTitle, list = emptyList()).apply {
                itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
            allItems.add(header)
            if (providerResult.state is SearchProviderResult.State.Success) {
                val results = providerResult.state.results.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                    }
                }
                allItems.addAll(results)
            }
        }
        appAdapter.submitList(allItems)
        appAdapter.setOnLoadMoreListener(null)
    }
}
