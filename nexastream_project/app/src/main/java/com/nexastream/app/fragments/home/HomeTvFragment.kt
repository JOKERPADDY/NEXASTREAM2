package com.nexastream.app.fragments.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.nexastream.app.activities.main.MainViewModel
import com.nexastream.app.utils.GitHub
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.FragmentHomeTvBinding
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.TvShow
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.ProviderChangeNotifier
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.activityViewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.isVisible

class HomeTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by activityViewModelsFactory {
        val appCtx = requireActivity().applicationContext
        val db = AppDatabase.getInstance(appCtx)
        HomeViewModel(
            db,
            HomeRepository(appCtx, db)
        )
    }
    private val mainViewModel: MainViewModel by activityViewModels()

    private val appAdapter = AppAdapter()

    private val swiperHandler = Handler(Looper.getMainLooper())
    private var isBackgroundPinned = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeHome()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { viewModel.getHome(force = true) }
        }

        setupUpdateBanner()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> {
                        if (appAdapter.items.isEmpty()) {
                            binding.isLoading.apply {
                                root.visibility = View.VISIBLE
                                pbIsLoading.visibility = View.VISIBLE
                                gIsLoadingRetry.visibility = View.GONE
                            }
                        }
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.vgvHome.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
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
                            btnIsLoadingRetry.setOnClickListener { viewModel.getHome() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                viewModel.getHome()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            binding.vgvHome.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        // Riavvia il carosello se i dati sono già stati caricati e il fragment è visibile
        appAdapter.items
            .filterIsInstance<Category>()
            .firstOrNull { it.name == Category.FEATURED }
            ?.let {
                resetSwiperSchedule()
            }
    }

    override fun onStop() {
        super.onStop()
        swiperHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.vgvHome)
        _binding = null
    }


    private fun setupUpdateBanner() {
        binding.btnUpdateBannerClose.setOnClickListener {
            binding.cvUpdateBanner.isVisible = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        binding.cvUpdateBanner.isVisible = true
                        binding.tvUpdateBannerMessage.text = "Version ${state.newReleases.first().tagName} is available."
                        binding.btnUpdateBannerInstall.setOnClickListener {
                            mainViewModel.downloadUpdate(requireContext(), state.asset)
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> {
                        binding.btnUpdateBannerInstall.isEnabled = false
                        binding.btnUpdateBannerInstall.text = "Downloading..."
                    }
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        mainViewModel.installUpdate(requireContext(), state.apk)
                        binding.btnUpdateBannerInstall.text = "Installing..."
                    }
                    else -> {}
                }
            }
        }
    }

    private var swiperHasLastFocus: Boolean = false
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {
        val binding = _binding ?: return
        val ctx = context ?: return
        if (!isAdded || isDetached) return
        if (swiperHasFocus == null && isBackgroundPinned) return
        if (swiperHasFocus == null && !swiperHasLastFocus) return

        runCatching {
            Glide.with(ctx)
                .load(uri)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivHomeBackground)
        }
        swiperHasLastFocus = swiperHasFocus ?: swiperHasLastFocus
    }

    fun pinBackground(uri: String?) {
        val binding = _binding ?: return
        val ctx = context ?: return
        if (!isAdded || isDetached) return
        isBackgroundPinned = true
        runCatching {
            Glide.with(ctx)
                .load(uri)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivHomeBackground)
        }
    }

    fun releasePinnedBackground() {
        if (!isBackgroundPinned) return
        isBackgroundPinned = false
        syncFeaturedBackground()
    }

    private fun initializeHome() {
        binding.btnHomeEditMode.setOnClickListener {
            appAdapter.isEditMode = !appAdapter.isEditMode
            binding.llHomeEditActions.isVisible = appAdapter.isEditMode
            binding.btnHomeEditMode.isVisible = !appAdapter.isEditMode
            
            if (appAdapter.isEditMode) {
                binding.btnHomeDeleteSelected.requestFocus()
            }
        }

        binding.btnHomeDeleteSelected.setOnClickListener {
            val selectedItems = appAdapter.items
                .filterIsInstance<Category>()
                .flatMap { it.list }
                .filter { it.isSelected }
            
            if (selectedItems.isEmpty()) return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val database = AppDatabase.getInstance(requireContext().applicationContext)
                selectedItems.forEach { item ->
                    when (item) {
                        is Movie -> database.movieDao().delete(item)
                        is TvShow -> database.tvShowDao().delete(item)
                        is Episode -> database.episodeDao().delete(item)
                    }
                }
                withContext(Dispatchers.Main) {
                    appAdapter.isEditMode = false
                    binding.llHomeEditActions.isVisible = false
                    binding.btnHomeEditMode.isVisible = true
                    viewModel.getHome()
                }
            }
        }

        binding.btnHomeClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_all)
                .setMessage("Are you sure you want to clear all history and favorites?")
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val database = AppDatabase.getInstance(requireContext().applicationContext)
                        database.movieDao().deleteAll()
                        database.tvShowDao().deleteAll()
                        database.episodeDao().deleteAll()
                        withContext(Dispatchers.Main) {
                            appAdapter.isEditMode = false
                            binding.llHomeEditActions.isVisible = false
                            binding.btnHomeEditMode.isVisible = true
                            viewModel.getHome()
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.vgvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                onViewAllClickListener = { category ->
                    val (genreId, genreName) = when (category.name) {
                        "CDN Live Channels", "Livestream", "Live Channels" -> "cdn_all_channels" to "CDN Live TV"
                        "Live Sports (CDN)", "Live Sports" -> "cdn_sports" to "Live Sports"
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
                        "All Movies" -> "tmdb_movies_popular" to category.name
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
                        "Top Rated TV Shows", "Top Rated TV Show" -> "tmdb_tv_top_rated" to category.name
                        "All TV Shows" -> "tmdb_tv_popular" to category.name
                        "Trending Series" -> "tmdb_tv_popular" to category.name

                        "Kids & Family" -> "tmdb_kids_family" to category.name
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

                        "Anime Universe" -> "tmdb_anime_universe" to category.name
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
                    
                    if (genreId.isNotEmpty()) {
                        findNavController().navigate(
                            HomeTvFragmentDirections.actionHomeToGenre(
                                id = genreId,
                                name = genreName
                            )
                        )
                    }
                }
            }
            setItemSpacing(resources.getDimension(R.dimen.home_spacing).toInt() * 2)
        }

        binding.root.requestFocus()
    }

    private fun displayHome(categories: List<Category>) {
        categories
            .find { it.name == Category.FEATURED }
            ?.also {
                val index = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { item -> item.name == Category.FEATURED }
                    ?.selectedIndex
                    ?: 0
                it.selectedIndex = index
                
                // Initialize background with first item from featured category immediately
                val firstItem = it.list.getOrNull(index)
                val poster = when (firstItem) {
                    is Movie -> firstItem.banner
                    is TvShow -> firstItem.banner
                    else -> null
                }
                // Force background update without waiting for focus
                if (poster != null) {
                    updateBackground(poster, null)
                }
                
                resetSwiperSchedule()
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        appAdapter.submitList(
            categories
                .filter { it.list.isNotEmpty() }
                .onEach { category ->
                    if (category.name != getString(R.string.home_continue_watching)) {
                        category.list.forEach { show ->
                            if (category.name == "Livestream" || category.name == "CDN Live Channels" || category.name == "Live Channels") {
                                if (show is TvShow) {
                                    show.itemType = AppAdapter.Type.LIVESTREAM_TV_ITEM
                                } else {
                                    show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                                }
                            } else {
                                when (show) {
                                    is Episode -> show.itemType = AppAdapter.Type.EPISODE_TV_ITEM
                                    is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                                    is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                                    is SportMatch -> show.itemType = AppAdapter.Type.SPORT_MATCH_ITEM
                                }
                            }
                        }
                    }
                    category.itemSpacing = resources.getDimension(R.dimen.home_spacing).toInt()
                    category.itemType = when (category.name) {
                        Category.FEATURED -> AppAdapter.Type.CATEGORY_TV_SWIPER
                        else -> AppAdapter.Type.CATEGORY_TV_ITEM
                    }
                }
        )
    }

    fun resetSwiperSchedule() {
        swiperHandler.removeCallbacksAndMessages(null)
        swiperHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isBackgroundPinned) {
                    swiperHandler.postDelayed(this, 8_000)
                    return
                }

                val position = appAdapter.items
                    .filterIsInstance<Category>()
                    .find { it.name == Category.FEATURED }
                    ?.let { category ->
                        if (category.list.isEmpty()) return@let null
                        category.selectedIndex = (category.selectedIndex + 1) % category.list.size
                        
                        // Update background when swiper rotates automatically
                        val currentItem = category.list.getOrNull(category.selectedIndex)
                        val poster = when (currentItem) {
                            is Movie -> currentItem.banner
                            is TvShow -> currentItem.banner
                            else -> null
                        }
                        // Update background if it's not null
                        if (poster != null) {
                            updateBackground(poster, null)
                        }

                        appAdapter.items.indexOf(category)
                    }
                    ?.takeIf { it != -1 }

                if (position == null) {
                    swiperHandler.removeCallbacksAndMessages(null)
                    return
                }

                appAdapter.notifyItemChanged(position)
                swiperHandler.postDelayed(this, 8_000)
            }
        }, 8_000)
    }

    private fun syncFeaturedBackground() {
        val featured = appAdapter.items
            .filterIsInstance<Category>()
            .find { it.name == Category.FEATURED }
            ?: return

        val currentItem = featured.list.getOrNull(featured.selectedIndex)
        val poster = when (currentItem) {
            is Movie -> currentItem.banner
            is TvShow -> currentItem.banner
            else -> null
        }

        if (poster != null) {
            updateBackground(poster, null)
        }
    }
}
