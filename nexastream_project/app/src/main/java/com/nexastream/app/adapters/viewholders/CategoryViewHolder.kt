package com.nexastream.app.adapters.viewholders

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.postDelayed
import androidx.core.view.children
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.adapters.FeaturedMiniCardAdapter
import com.nexastream.app.databinding.ContentCategorySwiperMobileBinding
import com.nexastream.app.databinding.ContentCategorySwiperTvBinding
import com.nexastream.app.databinding.ItemCategoryMobileBinding
import com.nexastream.app.databinding.ItemCategoryTvBinding
import com.nexastream.app.fragments.home.HomeMobileFragment
import com.nexastream.app.fragments.home.HomeTvFragment
import com.nexastream.app.fragments.home.HomeTvFragmentDirections
import com.nexastream.app.models.Category
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.format
import com.nexastream.app.utils.getCurrentFragment
import com.nexastream.app.utils.toActivity
import com.bumptech.glide.Glide
import java.util.Locale
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.providers.Provider
import com.nexastream.app.database.AppDatabase

class CategoryViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var category: Category

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ItemCategoryMobileBinding -> _binding.rvCategory
            is ItemCategoryTvBinding -> _binding.hgvCategory
            is ContentCategorySwiperMobileBinding -> _binding.vpCategorySwiper.javaClass
                .getDeclaredField("mRecyclerView").let {
                    it.isAccessible = true
                    it.get(_binding.vpCategorySwiper) as RecyclerView
                }
            else -> null
        }

    fun bind(
        category: Category,
        onMovieClick: ((Movie) -> Unit)? = null,
        onTvShowClick: ((TvShow) -> Unit)? = null,
        onViewAllClick: ((Category) -> Unit)? = null
    ) {
        this.category = category

        val isBanner = category.name.contains("Banner", ignoreCase = true) || 
                       category.name == Category.FEATURED ||
                       (::category.isInitialized && (category.itemType == AppAdapter.Type.CATEGORY_MOBILE_SWIPER || category.itemType == AppAdapter.Type.CATEGORY_TV_SWIPER))

        when (_binding) {
            is ItemCategoryMobileBinding -> {
                displayMobileItem(_binding, onMovieClick, onTvShowClick, onViewAllClick)
                _binding.tvCategoryTitle.visibility = if (isBanner) View.GONE else View.VISIBLE
                _binding.tvCategoryViewAll.visibility = if (isBanner) View.GONE else _binding.tvCategoryViewAll.visibility
            }
            is ItemCategoryTvBinding -> {
                displayTvItem(_binding, onMovieClick, onTvShowClick, onViewAllClick)
                _binding.tvCategoryTitle.visibility = if (isBanner) View.GONE else View.VISIBLE
                _binding.tvCategoryViewAll.visibility = if (isBanner) View.GONE else _binding.tvCategoryViewAll.visibility
            }
            is ContentCategorySwiperMobileBinding -> {
                displayMobileSwiper(_binding, onMovieClick, onTvShowClick)
                _binding.tvCategoryTitle.visibility = if (isBanner) View.GONE else View.VISIBLE
            }
            is ContentCategorySwiperTvBinding -> {
                displayTvSwiper(_binding)
                _binding.tvCategoryTitle.visibility = if (isBanner) View.GONE else View.VISIBLE
            }
        }
    }

    private fun displayMobileItem(
        binding: ItemCategoryMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onViewAllClick: ((Category) -> Unit)?
    ) {
        binding.tvCategoryTitle.text = category.name
        
        if (onViewAllClick != null && category.list.isNotEmpty() && category.name != Category.FEATURED) {
            binding.tvCategoryViewAll.visibility = View.VISIBLE
            binding.tvCategoryViewAll.setOnClickListener { onViewAllClick(category) }
        } else {
            binding.tvCategoryViewAll.visibility = View.GONE
        }

        binding.rvCategory.apply {
            setRecycledViewPool(AppAdapter.sharedViewPool)
            setHasFixedSize(true)
            setItemViewCacheSize(6)
            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                submitList(category.list)
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(category.itemSpacing))
            }
        }
    }

    private fun displayTvItem(
        binding: ItemCategoryTvBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onViewAllClick: ((Category) -> Unit)?
    ) {
        binding.tvCategoryTitle.text = category.name
        binding.tvCategoryTitle.visibility = if (category.name.contains("Banner", ignoreCase = true) || category.name == Category.FEATURED) View.GONE else View.VISIBLE

        if (onViewAllClick != null && category.list.isNotEmpty() && category.name != Category.FEATURED) {
            binding.tvCategoryViewAll.visibility = View.VISIBLE
            binding.tvCategoryViewAll.setOnClickListener { onViewAllClick(category) }
        } else {
            binding.tvCategoryViewAll.visibility = View.GONE
        }

        val parentAdapter = binding.root.parent?.let { (it as? RecyclerView)?.adapter as? AppAdapter }

        binding.hgvCategory.apply {
            setRecycledViewPool(AppAdapter.sharedViewPool)
            setHasFixedSize(true)
            setItemViewCacheSize(6)
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)

            val categoryAdapter = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
            categoryAdapter.apply {
                this.isEditMode = parentAdapter?.isEditMode ?: false
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                submitList(category.list)
            }
            setItemSpacing(category.itemSpacing)

            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }


    private fun displayMobileSwiper(
        binding: ContentCategorySwiperMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?
    ) {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(8_000) {
            binding.vpCategorySwiper.currentItem += 1
        }

        val items = listOf(
            listOfNotNull(category.list.lastOrNull()),
            category.list,
            listOfNotNull(category.list.firstOrNull()),
        ).flatten()
        binding.vpCategorySwiper.apply {
            adapter = AppAdapter().apply {
                this.onMovieClickListener = onMovieClick
                this.onTvShowClickListener = onTvShowClick
                submitList(category.list)
                post { (adapter as AppAdapter).submitList(items) }
            }
        }

        binding.llDotsIndicator.apply {
            removeAllViews()
            repeat(category.list.size) {
                val view = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(15, 15).apply {
                        setMargins(10, 0, 10, 0)
                    }
                    setBackgroundResource(R.drawable.bg_dot_indicator)
                }
                addView(view)
            }
        }

        binding.vpCategorySwiper.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val indicatorPosition = when (position) {
                    0 -> category.list.lastIndex
                    items.lastIndex -> 0
                    else -> position - 1
                }
                binding.llDotsIndicator.children.forEachIndexed { index, view ->
                    view.isSelected = (indicatorPosition == index)
                }

                handler.removeCallbacksAndMessages(null)
                handler.postDelayed(8_000) {
                    binding.vpCategorySwiper.currentItem += 1
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    when (binding.vpCategorySwiper.currentItem) {
                        0 -> binding.vpCategorySwiper.setCurrentItem(
                            items.lastIndex - 1,
                            false
                        )
                        items.lastIndex -> binding.vpCategorySwiper.setCurrentItem(
                            1,
                            false
                        )
                    }
                }
            }
        })
    }

    private fun displayTvSwiper(binding: ContentCategorySwiperTvBinding) {
        binding.tvCategoryTitle.text = category.name
        binding.tvCategoryTitle.visibility = if (category.name.contains("Banner", ignoreCase = true) || category.name == Category.FEATURED) View.GONE else View.VISIBLE

        val showsList = category.list.filterIsInstance<Show>()
        if (showsList.isEmpty()) return

        val initialSelected = showsList.getOrNull(category.selectedIndex) ?: showsList.first()
        bindSpotlightDetails(binding, initialSelected)

        // Force background update
        val backdrop = initialSelected.banner ?: initialSelected.poster
        when (val fragment = context.toActivity()?.getCurrentFragment()) {
            is HomeTvFragment -> {
                if (backdrop != null) {
                    fragment.updateBackground(backdrop, false)
                }
            }
        }

        binding.btnSwiperWatchNow.apply {
            setOnClickListener {
                val currentShow = showsList.getOrNull(category.selectedIndex) ?: initialSelected
                binding.root.findNavController().navigate(
                    when (currentShow) {
                        is Movie -> HomeTvFragmentDirections.actionHomeToMovie(currentShow.id)
                        is TvShow -> HomeTvFragmentDirections.actionHomeToTvShow(
                            id = currentShow.id,
                            poster = currentShow.poster,
                            banner = currentShow.banner,
                        )
                        else -> return@setOnClickListener
                    }
                )
            }
        }

        // Setup Mini Cards HorizontalGridView
        val miniCardAdapter = (binding.hgvFeaturedMiniCards.adapter as? FeaturedMiniCardAdapter)
            ?: FeaturedMiniCardAdapter().also { adapter ->
                binding.hgvFeaturedMiniCards.adapter = adapter
            }

        miniCardAdapter.items = showsList
        miniCardAdapter.selectedIndex = category.selectedIndex.coerceIn(0, showsList.lastIndex)
        miniCardAdapter.onItemSelected = { show, index ->
            if (category.selectedIndex != index) {
                category.selectedIndex = index
                bindSpotlightDetails(binding, show)
                val poster = show.banner ?: show.poster
                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> {
                        if (poster != null) {
                            fragment.updateBackground(poster, true)
                        }
                        fragment.resetSwiperSchedule()
                    }
                }
            }
        }
        miniCardAdapter.onItemClicked = { show ->
            when (show) {
                is Movie -> binding.root.findNavController().navigate(
                    HomeTvFragmentDirections.actionHomeToMovie(show.id)
                )
                is TvShow -> binding.root.findNavController().navigate(
                    HomeTvFragmentDirections.actionHomeToTvShow(
                        id = show.id,
                        poster = show.poster,
                        banner = show.banner,
                    )
                )
            }
        }
        miniCardAdapter.notifyDataSetChanged()
        binding.hgvFeaturedMiniCards.setSelectedPositionSmooth(category.selectedIndex)
    }

    private fun bindSpotlightDetails(binding: ContentCategorySwiperTvBinding, selected: Show) {
        binding.tvSwiperTitle.text = selected.title

        binding.tvSwiperTvShowLastEpisode.apply {
            text = when (selected) {
                is TvShow -> selected.seasons.lastOrNull()?.let { season ->
                    season.episodes.lastOrNull()?.let { episode ->
                        if (season.number != 0) {
                            context.getString(
                                R.string.tv_show_item_season_number_episode_number,
                                season.number,
                                episode.number
                            )
                        } else {
                            context.getString(
                                R.string.tv_show_item_episode_number,
                                episode.number
                            )
                        }
                    }
                } ?: context.getString(R.string.tv_show_item_type)
                else -> context.getString(R.string.movie_item_type)
            }
        }

        binding.tvSwiperQuality.apply {
            text = selected.quality
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvSwiperReleased.apply {
            text = selected.released?.format("yyyy")
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvSwiperRating.apply {
            text = selected.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.ivSwiperRatingIcon.visibility = binding.tvSwiperRating.visibility

        binding.tvSwiperGenres.apply {
            val genresList = when (selected) {
                is Movie -> selected.genres
                is TvShow -> selected.genres
                else -> emptyList()
            }
            val genresStr = genresList.joinToString(", ") { it.name }
            text = genresStr
            visibility = if (genresStr.isEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvSwiperOverview.text = selected.overview

        binding.pbSwiperProgress.apply {
            val watchHistory = when (selected) {
                is Movie -> selected.watchHistory
                is TvShow -> null
                else -> null
            }
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = if (watchHistory != null) View.VISIBLE else View.GONE
        }
    }
}
