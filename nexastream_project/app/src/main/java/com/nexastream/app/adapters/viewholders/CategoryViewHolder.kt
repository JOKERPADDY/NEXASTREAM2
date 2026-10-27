package com.nexastream.app.adapters.viewholders

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.postDelayed
import androidx.core.view.children
import androidx.leanback.widget.OnChildViewHolderSelectedListener
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
import com.nexastream.app.databinding.ItemTvCompositeHeroBinding
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
                       (::category.isInitialized && (category.itemType == AppAdapter.Type.CATEGORY_MOBILE_SWIPER || category.itemType == AppAdapter.Type.CATEGORY_TV_SWIPER || category.itemType == AppAdapter.Type.CATEGORY_TV_COMPOSITE_HERO))

        when (_binding) {
            is ItemCategoryMobileBinding -> {
                displayMobileItem(_binding, onMovieClick, onTvShowClick, onViewAllClick)
            }
            is ItemCategoryTvBinding -> {
                displayTvItem(_binding, onMovieClick, onTvShowClick, onViewAllClick)
            }
            is ContentCategorySwiperMobileBinding -> {
                displayMobileSwiper(_binding, onMovieClick, onTvShowClick)
                _binding.tvCategoryTitle.visibility = if (isBanner) View.GONE else View.VISIBLE
            }
            is ContentCategorySwiperTvBinding -> {
                displayTvSwiper(_binding)
                _binding.tvCategoryTitle.visibility = if (isBanner) View.GONE else View.VISIBLE
            }
            is ItemTvCompositeHeroBinding -> {
                displayTvCompositeHero(_binding, onMovieClick, onTvShowClick)
            }
        }
    }

    private fun getDisplayName(name: String): String {
        return when (name) {
            "Movies Banner" -> "Featured Movies"
            "Series Banner" -> "Featured Series"
            "Kids Banner" -> "Featured Kids"
            "Anime Banner" -> "Featured Anime"
            else -> name
        }
    }

    private fun displayMobileItem(
        binding: ItemCategoryMobileBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?,
        onViewAllClick: ((Category) -> Unit)?
    ) {
        binding.tvCategoryTitle.text = getDisplayName(category.name)
        binding.tvCategoryTitle.visibility = View.VISIBLE
        
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
        binding.tvCategoryTitle.text = getDisplayName(category.name)
        binding.tvCategoryTitle.visibility = View.VISIBLE

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
                val providerName = when (currentShow) {
                    is Movie -> currentShow.providerName
                    is TvShow -> currentShow.providerName
                    else -> null
                }
                if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
                    val prev = UserPreferences.currentProvider
                    UserPreferences.getProviderByName(providerName)?.let {
                        UserPreferences.currentProvider = it
                        if (UserPreferences.previousProviderToRestore == null) {
                            UserPreferences.previousProviderToRestore = prev
                        }
                        android.widget.Toast.makeText(context, context.getString(R.string.switching_to_provider, providerName), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
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

        binding.btnChipGenre.setOnClickListener {
            binding.root.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToGenre(
                    id = "all_genres",
                    name = context.getString(R.string.search_filters_genres)
                )
            )
        }

        binding.btnChipNew.setOnClickListener {
            binding.root.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToGenre(
                    id = "new_releases",
                    name = "New 2026"
                )
            )
        }

        binding.btnChipTrending.setOnClickListener {
            binding.root.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToGenre(
                    id = "trending",
                    name = "Trending"
                )
            )
        }

        binding.btnChipAll.setOnClickListener {
            binding.root.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToGenre(
                    id = "all_content",
                    name = "All Content"
                )
            )
        }

        // Setup Mini Cards HorizontalGridView
        binding.hgvFeaturedMiniCards.setRecycledViewPool(AppAdapter.sharedViewPool)
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

    private fun displayTvCompositeHero(
        binding: ItemTvCompositeHeroBinding,
        onMovieClick: ((Movie) -> Unit)?,
        onTvShowClick: ((TvShow) -> Unit)?
    ) {
        val showsList = category.list.filterIsInstance<Show>()
        if (showsList.isEmpty()) return

        fun buildMetadataString(show: Show): String {
            val parts = mutableListOf<String>()
            val rating = when (show) {
                is Movie -> show.rating
                is TvShow -> show.rating
                else -> null
            }
            if (rating != null && rating > 0.0) {
                parts.add("⭐ %.1f".format(Locale.US, rating))
            }
            val year = when (show) {
                is Movie -> show.released?.format("yyyy")
                is TvShow -> show.released?.format("yyyy")
                else -> null
            }
            if (!year.isNullOrBlank()) {
                parts.add(year)
            }
            val genres = when (show) {
                is Movie -> show.genres.take(3).joinToString(",") { it.name }
                is TvShow -> show.genres.take(3).joinToString(",") { it.name }
                else -> null
            }
            if (!genres.isNullOrBlank()) {
                parts.add(genres)
            }
            return if (parts.isNotEmpty()) parts.joinToString("  |  ") else "2026"
        }

        fun updateHeroContent(selectedShow: Show) {
            val title = when (selectedShow) {
                is Movie -> selectedShow.title
                is TvShow -> selectedShow.title
                else -> ""
            }
            val overview = when (selectedShow) {
                is Movie -> selectedShow.overview
                is TvShow -> selectedShow.overview
                else -> ""
            }
            val banner = when (selectedShow) {
                is Movie -> selectedShow.banner ?: selectedShow.poster
                is TvShow -> selectedShow.banner ?: selectedShow.poster
                else -> null
            }
            val quality = when (selectedShow) {
                is Movie -> selectedShow.quality
                is TvShow -> selectedShow.quality
                else -> null
            }

            binding.tvHeroTitle.text = title
            binding.tvHeroMetadata.text = buildMetadataString(selectedShow)
            binding.tvHeroOverview.text = overview

            if (!banner.isNullOrBlank()) {
                Glide.with(binding.ivHeroBannerImage.context)
                    .load(banner)
                    .centerCrop()
                    .into(binding.ivHeroBannerImage)

                (binding.root.context as? androidx.fragment.app.FragmentActivity)
                    ?.supportFragmentManager
                    ?.primaryNavigationFragment
                    ?.childFragmentManager
                    ?.fragments
                    ?.firstOrNull { it is HomeTvFragment }
                    ?.let { (it as HomeTvFragment).updateBackground(banner, true) }
            }
        }

        val initialIndex = category.selectedIndex.coerceIn(0, showsList.lastIndex)
        val initialSelected = showsList[initialIndex]
        updateHeroContent(initialSelected)

        binding.btnChipGenre.setOnClickListener {
            binding.root.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToGenre(id = "all_genres", name = "Genres & Categories")
            )
        }
        binding.btnChipNew.setOnClickListener {
            binding.root.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToGenre(id = "new_releases", name = "New Releases 2026")
            )
        }
        binding.btnChipTrending.setOnClickListener {
            binding.root.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToGenre(id = "trending", name = "Trending Now")
            )
        }
        binding.btnChipAll.setOnClickListener {
            binding.root.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToGenre(id = "all_content", name = "All Library Content")
            )
        }

        val miniAdapter = (binding.hgvHeroPosters.adapter as? FeaturedMiniCardAdapter)
            ?: FeaturedMiniCardAdapter().also { binding.hgvHeroPosters.adapter = it }

        miniAdapter.items = showsList
        miniAdapter.selectedIndex = initialIndex
        miniAdapter.onItemSelected = { show, index ->
            category.selectedIndex = index
            updateHeroContent(show)
            binding.hgvHeroPosters.setSelectedPositionSmooth(index)
        }
        miniAdapter.onItemClicked = { show ->
            when (show) {
                is Movie -> onMovieClick?.invoke(show)
                is TvShow -> onTvShowClick?.invoke(show)
            }
        }
        miniAdapter.notifyDataSetChanged()

        binding.hgvHeroPosters.apply {
            setRecycledViewPool(AppAdapter.sharedViewPool)
            setItemSpacing(category.itemSpacing)
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setSelectedPosition(initialIndex)
        }

        val handler = Handler(Looper.getMainLooper())
        var autoRunnable: Runnable? = null

        autoRunnable = Runnable {
            if (binding.root.isAttachedToWindow && showsList.isNotEmpty()) {
                val nextIndex = (category.selectedIndex + 1) % showsList.size
                category.selectedIndex = nextIndex
                miniAdapter.updateSelection(nextIndex)
                binding.hgvHeroPosters.setSelectedPositionSmooth(nextIndex)
                updateHeroContent(showsList[nextIndex])
                autoRunnable?.let { handler.postDelayed(it, 8_000) }
            }
        }

        binding.hgvHeroPosters.setOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: RecyclerView,
                child: RecyclerView.ViewHolder?,
                position: Int,
                subposition: Int
            ) {
                if (position in showsList.indices) {
                    category.selectedIndex = position
                    miniAdapter.updateSelection(position)
                    updateHeroContent(showsList[position])
                    autoRunnable?.let {
                        handler.removeCallbacks(it)
                        handler.postDelayed(it, 8_000)
                    }
                }
            }
        })

        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                autoRunnable?.let { handler.removeCallbacks(it) }
                v.removeOnAttachStateChangeListener(this)
            }
        }
        binding.root.removeOnAttachStateChangeListener(listener)
        binding.root.addOnAttachStateChangeListener(listener)

        autoRunnable?.let {
            handler.removeCallbacks(it)
            handler.postDelayed(it, 8_000)
        }
    }
}
