package com.nexastream.app.fragments.tv_shows

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
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.FragmentTvShowsTvBinding
import com.nexastream.app.models.Category
import com.nexastream.app.models.TvShow
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.setupFocusUpToTopNav
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TvShowsTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentTvShowsTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TvShowsViewModel by viewModels()

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeTvShows()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowsViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    TvShowsViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is TvShowsViewModel.State.SuccessLoading -> {
                        displayTvShows(state.tvShows, state.categories, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvTvShows.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowsViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.nexastream.app.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.getTvShows()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.getTvShows() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.nexastream.app.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.getTvShows()
                                }
                                binding.vgvTvShows.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeTvShows() {
        appAdapter.onMovieClickListener = { movie ->
            findNavController().navigate(
                TvShowsTvFragmentDirections.actionGlobalMovie(id = movie.id)
            )
        }
        appAdapter.onTvShowClickListener = { tvShow ->
            findNavController().navigate(
                TvShowsTvFragmentDirections.actionTvShowsToTvShow(
                    id = tvShow.id,
                    poster = tvShow.poster,
                    banner = tvShow.banner
                )
            )
        }
        binding.vgvTvShows.apply {
            setupFocusUpToTopNav(activity)
            val spacing = requireContext().resources.getDimension(R.dimen.tv_shows_spacing).toInt()
            setItemSpacing(spacing)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
        }

        binding.root.requestFocus()
    }

    private fun displayTvShows(tvShows: List<TvShow>, categories: List<Category>, hasMore: Boolean) {
        if (categories.isNotEmpty()) {
            binding.vgvTvShows.setNumColumns(1)
            categories.forEach { category ->
                category.itemSpacing = requireContext().resources.getDimension(R.dimen.tv_shows_spacing).toInt()
                category.itemType = if (category.name == Category.FEATURED) {
                    AppAdapter.Type.CATEGORY_TV_COMPOSITE_HERO
                } else {
                    AppAdapter.Type.CATEGORY_TV_ITEM
                }
                category.list.forEach { show ->
                    when (show) {
                        is com.nexastream.app.models.Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                }
            }
            appAdapter.submitList(categories)
        } else {
            binding.vgvTvShows.setNumColumns(1)
            tvShows.forEach { it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM }
            val featuredList = tvShows.take(8)
            val featuredCategory = Category(Category.FEATURED, featuredList).apply {
                itemType = AppAdapter.Type.CATEGORY_TV_COMPOSITE_HERO
            }
            val allCategory = Category("All Series", tvShows).apply {
                itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
            appAdapter.submitList(listOf(featuredCategory, allCategory))
        }

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreTvShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}
