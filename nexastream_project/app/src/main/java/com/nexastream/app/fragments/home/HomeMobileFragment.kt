package com.nexastream.app.fragments.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.FragmentHomeMobileBinding
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.TvShow
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.ProviderChangeNotifier
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.activityViewModelsFactory
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class HomeMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by activityViewModelsFactory {
        val appCtx = requireActivity().applicationContext
        val db = AppDatabase.getInstance(appCtx)
        HomeViewModel(
            db,
            HomeRepository(appCtx, db)
        )
    }

    private val appAdapter = AppAdapter()
    private var allCategories: List<Category> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.clHomeHeader.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        initializeHome()

        // Bind header controls
        binding.ivSearch.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.home) {
                findNavController().navigate(R.id.search)
            }
        }
        binding.ivChangeProvider.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.home) {
                findNavController().navigate(R.id.providers)
            }
        }
        binding.ivSettings.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.home) {
                findNavController().navigate(R.id.settings)
            }
        }

        updateProviderLogo()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { 
                    viewModel.getHome(force = true)
                    updateProviderLogo()
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> {
                        if (appAdapter.items.isEmpty()) {
                            (activity as? com.nexastream.app.activities.main.MainMobileActivity)?.setHomeLoadingState(true)
                            binding.isLoading.apply {
                                root.alpha = 1f
                                root.visibility = View.VISIBLE
                                pbIsLoading.visibility = View.VISIBLE
                                gIsLoadingRetry.visibility = View.GONE
                                tvLoadingVersion.text = "v${com.nexastream.app.BuildConfig.VERSION_NAME}"
                            }
                        }
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        (activity as? com.nexastream.app.activities.main.MainMobileActivity)?.setHomeLoadingState(false)
                        if (binding.isLoading.root.visibility == View.VISIBLE) {
                            binding.isLoading.root.animate()
                                .alpha(0f)
                                .setDuration(250)
                                .withEndAction {
                                    _binding?.isLoading?.root?.visibility = View.GONE
                                    _binding?.isLoading?.root?.alpha = 1f
                                }
                        } else {
                            binding.isLoading.root.visibility = View.GONE
                        }
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        (activity as? com.nexastream.app.activities.main.MainMobileActivity)?.setHomeLoadingState(false)
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            viewModel.getHome()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            val doRetry = { viewModel.getHome() }
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

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.rvHome)
        _binding = null
    }


    private fun initializeHome() {
        val tabs = binding.root.findViewById<TabLayout>(R.id.tl_home_tabs)
        tabs?.apply {
            if (tabCount == 0) {
                val isIptv = UserPreferences.currentProvider is com.nexastream.app.providers.IptvProvider
                if (!isIptv) {
                    addTab(newTab().setText("Recommended"))
                    addTab(newTab().setText("Movies"))
                    addTab(newTab().setText("Series"))
                    addTab(newTab().setText("Kids"))
                    addTab(newTab().setText("Anime"))
                } else {
                    addTab(newTab().setText("All Live"))
                }
            }

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    filterAndDisplayHome(tab?.position ?: 0)
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }

        binding.rvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                onViewAllClickListener = { category ->
                    val (genreId, genreName) = when (category.name) {
                        "CDN Live Channels", "Livestream" -> "cdn_all_channels" to "CDN Live TV"
                        "Live Sports (CDN)" -> "cdn_sports" to "Live Sports"
                        "Latest Movies" -> "latest_movies" to category.name
                        "All Cinema", "At Cinema" -> "all_cinema" to category.name
                        "New Season and Episode", "New Season & Episode" -> "new_season_tv" to category.name
                        
                        "Netflix Movies" -> "tmdb_watch_provider_movies_8" to category.name
                        "Disney+ Movies", "Disney+" -> "tmdb_watch_provider_movies_337" to category.name
                        "Paramount+ Movies", "Paramount+" -> "tmdb_watch_provider_movies_531" to category.name
                        
                        "Netflix Series" -> "tmdb_network_tv_213" to category.name
                        "Prime Video Series", "Prime Video" -> "tmdb_network_tv_1024" to category.name
                        "Disney+ Series" -> "tmdb_network_tv_2739" to category.name
                        "Max Series", "Max" -> "tmdb_network_tv_49" to category.name
                        "Paramount+ Series" -> "tmdb_network_tv_4330" to category.name
                        "Apple TV Series", "Apple TV" -> "tmdb_network_tv_2552" to category.name
                        "Hulu Series", "Hulu" -> "tmdb_network_tv_453" to category.name

                        "Action Movies" -> "tmdb_movies_genre_28" to category.name
                        "Crime Movies" -> "tmdb_movies_genre_80" to category.name
                        "Drama Movies" -> "tmdb_movies_genre_18" to category.name
                        "Adventure Movies" -> "tmdb_movies_genre_12" to category.name
                        "Comedy Movies" -> "tmdb_movies_genre_35" to category.name
                        "Thriller Movies" -> "tmdb_movies_genre_53" to category.name
                        "Romance Movies" -> "tmdb_movies_genre_10749" to category.name
                        "Sci-Fi Movies" -> "tmdb_movies_genre_878" to category.name
                        "Documentary Movies" -> "tmdb_movies_genre_99" to category.name
                        "Horror Movies" -> "tmdb_movies_genre_27" to category.name
                        "Fantasy Movies" -> "tmdb_movies_genre_14" to category.name
                        "Family Movies" -> "tmdb_movies_genre_10751" to category.name
                        "History Movies" -> "tmdb_movies_genre_36" to category.name
                        "Mystery Movies" -> "tmdb_movies_genre_9648" to category.name
                        "War Movies" -> "tmdb_movies_genre_10752" to category.name
                        "Western Movies" -> "tmdb_movies_genre_37" to category.name
                        "Teen Romance Movies" -> "teen_romance_movies" to category.name
                        "Biography Movies" -> "biography_movies" to category.name
                        "Sport Movies" -> "sport_movies" to category.name
                        "All Movies", "Top Movies" -> "tmdb_movies_popular" to category.name
                        "Trending Movies" -> "tmdb_movies_popular" to category.name

                        "Action & Adventure Series" -> "tmdb_tv_genre_10759" to category.name
                        "Comedy Series" -> "tmdb_tv_genre_35" to category.name
                        "Crime Series" -> "tmdb_tv_genre_80" to category.name
                        "Documentary Series" -> "tmdb_tv_genre_99" to category.name
                        "Drama Series" -> "tmdb_tv_genre_18" to category.name
                        "Family Series" -> "tmdb_tv_genre_10751" to category.name
                        "Sci-Fi & Fantasy Series" -> "tmdb_tv_genre_10765" to category.name
                        "History Series" -> "tmdb_tv_genre_36" to category.name
                        "Horror Series" -> "tmdb_tv_genre_27" to category.name
                        "Mystery Series" -> "tmdb_tv_genre_9648" to category.name
                        "Romance Series" -> "tmdb_tv_genre_10749" to category.name
                        "Thriller Series" -> "tmdb_tv_genre_53" to category.name
                        "War & Politics Series" -> "tmdb_tv_genre_10768" to category.name
                        "Reality TV", "Reality Series" -> "tmdb_tv_genre_10764" to category.name
                        "Western Series" -> "tmdb_tv_genre_37" to category.name
                        "Teen Romance Series", "Teen Romance", "Trending Teen Romance" -> "teen_romance_series" to category.name
                        "Biography Series" -> "biography_series" to category.name
                        "Sport Series" -> "sport_series" to category.name
                        "Musical Series" -> "search_Musical Series" to category.name
                        "Top Rated TV Shows", "Top Rated TV Show" -> "tmdb_tv_top_rated" to category.name
                        "All TV Shows", "Top TV Shows" -> "tmdb_tv_popular" to category.name
                        "Trending Series" -> "tmdb_tv_popular" to category.name

                        "Kids & Family", "Kids" -> "tmdb_kids_family" to category.name
                        "Cartoon Movies", "CartoonMovies" -> "tmdb_cartoon_movies" to category.name
                        "Cartoon Series" -> "tmdb_cartoon_series" to category.name
                        "Baby" -> "tmdb_keyword_10229" to category.name
                        "Age 2-6" -> "tmdb_kids_family" to category.name
                        "Pixar" -> "tmdb_studio_3" to category.name
                        "DreamWorks" -> "tmdb_studio_521" to category.name
                        "Blue Sky Studios", "BlueSky Studios", "Bluesky Studios" -> "tmdb_studio_10378" to category.name
                        "Illumination" -> "tmdb_studio_6704" to category.name
                        "Toys" -> "tmdb_keyword_11134" to category.name
                        "Kung Fu Panda" -> "search_Kung Fu Panda" to category.name
                        "Cars" -> "search_Cars" to category.name
                        "Frozen" -> "search_Frozen" to category.name
                        "Minions" -> "search_Minions" to category.name
                        "Peppa Pig" -> "search_Peppa Pig" to category.name

                        "Anime Universe", "Anime" -> "tmdb_anime_universe" to category.name
                        "Anime Movies" -> "tmdb_cartoon_movies" to category.name
                        "Japanese Anime" -> "tmdb_japanese_anime" to category.name
                        "European & American Anime", "American Anime" -> "tmdb_western_anime" to category.name
                        "Age 7-12" -> "tmdb_anime_age_7_12" to category.name
                        "Dragon Ball" -> "search_Dragon Ball" to category.name
                        "Naruto" -> "search_Naruto" to category.name
                        "One Piece" -> "search_One Piece" to category.name

                        "Movies Banner" -> "tmdb_movies_popular" to "Movies"
                        "Series Banner" -> "tmdb_tv_popular" to "Series"
                        "Kids Banner" -> "tmdb_kids_family" to "Kids & Family"
                        "Anime Banner" -> "tmdb_anime_universe" to "Anime Universe"
                        "Trending Today" -> "tmdb_movies_popular" to "Trending"
                        "✨ Recommended For You", "Recommended For You", "Recommended for you" -> "tmdb_recommended_for_you" to "Recommended For You"

                        else -> {
                            val name = category.name
                            when {
                                name.startsWith("Popular on") || name.startsWith("Popolari su") -> {
                                    val platform = name.substringAfter("on ").substringAfter("su ").trim()
                                    val providerId = when (platform) {
                                        "Netflix" -> 8
                                        "Disney+" -> 337
                                        "Hulu" -> 15
                                        "HBO" -> 384
                                        "Apple TV+" -> 350
                                        "Amazon" -> 10
                                        else -> 0
                                    }
                                    if (providerId != 0) "tmdb_watch_provider_movies_$providerId" to name else name to name
                                }
                                else -> name to name
                            }
                        }
                    }
                    
                    if (genreId.isNotEmpty() && findNavController().currentDestination?.id == R.id.home) {
                        findNavController().navigate(
                            HomeMobileFragmentDirections.actionHomeToGenre(
                                id = genreId,
                                name = genreName
                            )
                        )
                    }
                }
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
        
        // Ensure background image is hidden on mobile to show theme color
        binding.ivHomeBackground.visibility = View.GONE
    }

    private fun displayHome(categories: List<Category>) {
        this.allCategories = categories
        val tabs = binding.root.findViewById<TabLayout>(R.id.tl_home_tabs)
        tabs?.apply {
            val isIptv = UserPreferences.currentProvider is com.nexastream.app.providers.IptvProvider
            val currentTabText = if (tabCount > 0) getTabAt(0)?.text?.toString() else null
            
            val needsRebuild = if (isIptv) {
                currentTabText == "Recommended" || tabCount != categories.size + 1
            } else {
                currentTabText == "All Live" || tabCount != 5
            }
            
            if (needsRebuild) {
                removeAllTabs()
                if (isIptv) {
                    addTab(newTab().setText("All Live"))
                    categories.forEach { addTab(newTab().setText(it.name)) }
                } else {
                    addTab(newTab().setText("Recommended"))
                    addTab(newTab().setText("Movies"))
                    addTab(newTab().setText("Series"))
                    addTab(newTab().setText("Kids"))
                    addTab(newTab().setText("Anime"))
                }
            }
        }
        filterAndDisplayHome(tabs?.selectedTabPosition ?: 0)
    }

    private fun updateProviderLogo() {
        val providerLogo = UserPreferences.currentProvider?.logo
        Glide.with(this)
            .load(providerLogo?.takeIf { it.isNotEmpty() } ?: R.drawable.ic_provider_default_logo)
            .error(R.drawable.ic_provider_default_logo)
            .fitCenter()
            .into(binding.ivProviderLogo)
    }

    private fun filterAndDisplayHome(tabIndex: Int) {
        val isIptv = UserPreferences.currentProvider is com.nexastream.app.providers.IptvProvider

        val filtered = if (isIptv) {
            if (tabIndex == 0) {
                allCategories
            } else {
                val selectedCategory = allCategories.getOrNull(tabIndex - 1)
                if (selectedCategory != null) listOf(selectedCategory) else emptyList()
            }
        } else {
            val kidsCategories = listOf(
                "Kids Banner", "Kids & Family", "Cartoon Movies", "CartoonMovies", "Cartoon Series",
                "Baby", "Age 2-6", "Pixar", "DreamWorks", "Blue Sky Studios", "BlueSky Studios", "Bluesky Studios",
                "Illumination", "Toys", "Kung Fu Panda", "Cars", "Frozen", "Minions", "Peppa Pig"
            )
            val animeCategories = listOf(
                "Anime Banner", "Anime Universe", "Anime Movies", "Japanese Anime",
                "European & American Anime", "American Anime", "Age 7-12", "Dragon Ball", "Naruto", "One Piece"
            )
            val moviesCategories = listOf(
                "Movies Banner", "Trending Movies", "Top Rated Movies", "Upcoming Movies",
                "Latest Movies", "All Cinema", "Netflix Movies", "Disney+ Movies", "Disney+", "Paramount+ Movies", "Paramount+",
                "Action Movies", "Crime Movies", "Drama Movies", "Adventure Movies", "Comedy Movies",
                "Thriller Movies", "Romance Movies", "Sci-Fi Movies", "Documentary Movies",
                "Horror Movies", "Fantasy Movies", "Family Movies", "History Movies",
                "Mystery Movies", "War Movies", "Biography Movies", "Sport Movies", "Western Movies", "Teen Romance Movies",
                "All Movies", "Top Movies", "At Cinema"
            )
            val seriesCategories = listOf(
                "Series Banner", "Trending Series", "Top Rated TV Shows", "Top Rated TV Show",
                "New Season and Episode", "Netflix Series", "Prime Video Series", "Prime Video", "Disney+ Series",
                "Max Series", "Paramount+ Series", "Apple TV Series", "Apple TV", "Hulu Series", "Hulu",
                "Action & Adventure Series", "Action & Adventure", "Comedy Series", "Crime Series", "Teen Romance Series", "Teen Romance",
                "Documentary Series", "Drama Series", "Family Series", "Sci-Fi & Fantasy Series",
                "History Series", "Horror Series", "Mystery Series", "Romance Series",
                "Thriller Series", "War & Politics Series", "Biography Series", "Reality TV", "Reality Series", "Sport Series", "Western Series",
                "All TV Shows", "Top TV Shows", "New Season & Episode", "Musical Series"
            )

            fun isLiveCategory(name: String): Boolean {
                val lower = name.lowercase()
                return lower.contains("livestream") || lower.contains("live tv") || lower.contains("live sports") || lower.contains("live channels") || lower == "live"
            }

            when (tabIndex) {
                1 -> allCategories.mapNotNull { cat ->
                    if (isLiveCategory(cat.name)) return@mapNotNull null
                    if (cat.name in moviesCategories || cat.name.contains("Movie", ignoreCase = true) || cat.name.contains("Cinema", ignoreCase = true) || cat.name.contains("Film", ignoreCase = true)) {
                        cat
                    } else if (cat.name == "✨ Recommended For You" || cat.name == "Recommended For You" || cat.name == "Trending Today" || cat.name == Category.FAVORITE_MOVIES) {
                        val moviesOnly = cat.list.filterIsInstance<Movie>()
                        if (moviesOnly.isNotEmpty()) cat.copy(list = moviesOnly) else null
                    } else if (cat.list.isNotEmpty() && cat.list.all { it is Movie }) {
                        cat
                    } else null
                }
                2 -> allCategories.mapNotNull { cat ->
                    if (isLiveCategory(cat.name)) return@mapNotNull null
                    if (cat.name in seriesCategories || cat.name.contains("Series", ignoreCase = true) || cat.name.contains("TV", ignoreCase = true) || cat.name.contains("Show", ignoreCase = true)) {
                        cat
                    } else if (cat.name == "✨ Recommended For You" || cat.name == "Recommended For You" || cat.name == "Trending Today" || cat.name == Category.FAVORITE_TV_SHOWS) {
                        val tvOnly = cat.list.filterIsInstance<TvShow>()
                        if (tvOnly.isNotEmpty()) cat.copy(list = tvOnly) else null
                    } else if (cat.list.isNotEmpty() && cat.list.all { it is TvShow }) {
                        cat
                    } else null
                }
                3 -> allCategories.filter { cat ->
                    !isLiveCategory(cat.name) && (cat.name in kidsCategories || cat.name.contains("Kids", ignoreCase = true) || cat.name.contains("Cartoon", ignoreCase = true))
                }
                4 -> allCategories.filter { cat ->
                    !isLiveCategory(cat.name) && (cat.name in animeCategories || cat.name.contains("Anime", ignoreCase = true))
                }
                else -> allCategories.filter { cat ->
                    (cat.name !in moviesCategories &&
                    cat.name !in seriesCategories &&
                    cat.name !in kidsCategories &&
                    cat.name !in animeCategories) || cat.name == "Teen Romance" || cat.name == "✨ Recommended For You" || cat.name == Category.FEATURED || cat.name.isEmpty() || isLiveCategory(cat.name)
                }
            }
        }

        appAdapter.submitList(
            filtered
                .filter { it.list.isNotEmpty() }
                .mapIndexed { index, category ->
                    val isBannerCategory = category.name == Category.FEATURED || 
                                            category.name.endsWith("Banner", ignoreCase = true) ||
                                            category.itemType == AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                    val isSwiper = index == 0 && isBannerCategory && UserPreferences.currentProvider !is com.nexastream.app.providers.IptvProvider
                    
                    val itemsToProcess = if (isSwiper) category.list.take(4) else category.list
                    val clonedList = itemsToProcess.map { show ->
                        if (isSwiper) {
                            when (show) {
                                is Movie -> show.copy().apply { itemType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM }
                                is TvShow -> show.copy().apply { itemType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM }
                                else -> show
                            }
                        } else if (category.name == "Livestream") {
                            when (show) {
                                is TvShow -> show.copy().apply { itemType = AppAdapter.Type.LIVESTREAM_MOBILE_ITEM }
                                else -> show
                            }
                        } else if (category.name == getString(R.string.home_continue_watching)) {
                            when (show) {
                                is Episode -> show.copy().apply { itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM }
                                is Movie -> show.copy().apply { itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM }
                                else -> show
                            }
                        } else {
                            when (show) {
                                is Episode -> show.copy().apply { itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM }
                                is Movie -> show.copy().apply { itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM }
                                is TvShow -> show.copy().apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
                                is SportMatch -> (show as? SportMatch)?.copy()?.apply { itemType = AppAdapter.Type.SPORT_MATCH_ITEM } ?: show
                                else -> show
                            }
                        }
                    }

                    category.copy(list = clonedList).apply {
                        itemSpacing = 10.dp(requireContext())
                        itemType = if (isSwiper) AppAdapter.Type.CATEGORY_MOBILE_SWIPER else AppAdapter.Type.CATEGORY_MOBILE_ITEM
                    }
                }
        )
    }
}
