package com.nexastream.app.adapters

import android.os.Parcelable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.nexastream.app.adapters.viewholders.CategoryViewHolder
import com.nexastream.app.adapters.viewholders.EpisodeViewHolder
import com.nexastream.app.adapters.viewholders.GenreViewHolder
import com.nexastream.app.adapters.viewholders.MovieViewHolder
import com.nexastream.app.adapters.viewholders.PeopleViewHolder
import com.nexastream.app.adapters.viewholders.ProviderViewHolder
import com.nexastream.app.adapters.viewholders.SeasonViewHolder
import com.nexastream.app.adapters.viewholders.SportMatchViewHolder
import com.nexastream.app.adapters.viewholders.TvShowViewHolder
import com.nexastream.app.databinding.ContentCategorySwiperMobileBinding
import com.nexastream.app.databinding.ContentCategorySwiperTvBinding
import com.nexastream.app.databinding.ContentMovieCastMobileBinding
import com.nexastream.app.databinding.ContentMovieCastTvBinding
import com.nexastream.app.databinding.ContentMovieDirectorsMobileBinding
import com.nexastream.app.databinding.ContentMovieDirectorsTvBinding
import com.nexastream.app.databinding.ContentMovieMobileBinding
import com.nexastream.app.databinding.ContentMovieRecommendationsMobileBinding
import com.nexastream.app.databinding.ContentMovieRecommendationsTvBinding
import com.nexastream.app.databinding.ContentMovieTvBinding
import com.nexastream.app.databinding.ContentTvShowCastMobileBinding
import com.nexastream.app.databinding.ContentTvShowCastTvBinding
import com.nexastream.app.databinding.ContentTvShowDirectorsMobileBinding
import com.nexastream.app.databinding.ContentTvShowDirectorsTvBinding
import com.nexastream.app.databinding.ContentTvShowMobileBinding
import com.nexastream.app.databinding.ContentTvShowRecommendationsMobileBinding
import com.nexastream.app.databinding.ContentTvShowRecommendationsTvBinding
import com.nexastream.app.databinding.ContentTvShowSeasonsMobileBinding
import com.nexastream.app.databinding.ContentTvShowSeasonsTvBinding
import com.nexastream.app.databinding.ContentTvShowTvBinding
import com.nexastream.app.databinding.ItemCategoryMobileBinding
import com.nexastream.app.databinding.ItemCategorySwiperMobileBinding
import com.nexastream.app.databinding.ItemCategoryTvBinding
import com.nexastream.app.databinding.ItemEpisodeContinueWatchingMobileBinding
import com.nexastream.app.databinding.ItemEpisodeContinueWatchingTvBinding
import com.nexastream.app.databinding.ItemEpisodeTvGridBinding
import com.nexastream.app.databinding.ItemEpisodeMobileBinding
import com.nexastream.app.databinding.ItemEpisodeTvBinding
import com.nexastream.app.databinding.ItemGenreGridMobileBinding
import com.nexastream.app.databinding.ItemGenreGridTvBinding
import com.nexastream.app.databinding.ItemLivestreamMobileBinding
import com.nexastream.app.databinding.ItemLivestreamTvBinding
import com.nexastream.app.databinding.ItemLoadingBinding
import com.nexastream.app.databinding.ItemMovieGridMobileBinding
import com.nexastream.app.databinding.ItemMovieGridTvBinding
import com.nexastream.app.databinding.ItemMovieMobileBinding
import com.nexastream.app.databinding.ItemMovieTvBinding
import com.nexastream.app.databinding.ItemPeopleMobileBinding
import com.nexastream.app.databinding.ItemPeopleTvBinding
import com.nexastream.app.databinding.ItemProviderMobileBinding
import com.nexastream.app.databinding.ItemProviderTvBinding
import com.nexastream.app.databinding.ItemSeasonMobileBinding
import com.nexastream.app.databinding.ItemSeasonTvBinding
import com.nexastream.app.databinding.ItemSportMatchMobileBinding
import com.nexastream.app.databinding.ItemSportMatchTvBinding
import com.nexastream.app.databinding.ItemTvShowGridBinding
import com.nexastream.app.databinding.ItemTvShowGridMobileBinding
import com.nexastream.app.databinding.ItemTvShowMobileBinding
import com.nexastream.app.databinding.ItemTvShowTvBinding
import com.nexastream.app.models.SportMatch
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.Provider
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow

class AppAdapter(
    val items: MutableList<Item> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    // --- LISTENERS AÑADIDOS AQUÍ ---
    var onMovieClickListener: ((Movie) -> Unit)? = null
    var onTvShowClickListener: ((TvShow) -> Unit)? = null
    var onMovieLongClickListener: ((Movie) -> Unit)? = null
    var onTvShowLongClickListener: ((TvShow) -> Unit)? = null
    var onGenreClickListener: ((Genre) -> Unit)? = null
    var onPeopleClickListener: ((People) -> Unit)? = null
    var onEpisodeClickListener: ((Episode) -> Unit)? = null
    var onSeasonClickListener: ((Season) -> Unit)? = null
    var onProviderClickListener: ((Provider) -> Unit)? = null
    var onViewAllClickListener: ((Category) -> Unit)? = null
    
    var isEditMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    // ---------------------------------
    interface Item {
        var itemType: Type
        var isSelected: Boolean
    }

    enum class Type {
        CATEGORY_MOBILE_ITEM,
        CATEGORY_TV_ITEM,

        CATEGORY_MOBILE_SWIPER,
        CATEGORY_TV_SWIPER,

        EPISODE_MOBILE_ITEM,
        EPISODE_TV_ITEM,
        EPISODE_TV_GRID_ITEM,
        EPISODE_CONTINUE_WATCHING_MOBILE_ITEM,
        EPISODE_CONTINUE_WATCHING_TV_ITEM,

        FOOTER,

        GENRE_GRID_MOBILE_ITEM,
        GENRE_GRID_TV_ITEM,

        HEADER,

        LOADING_ITEM,

        LIVESTREAM_MOBILE_ITEM,
        LIVESTREAM_TV_ITEM,

        MOVIE_MOBILE_ITEM,
        MOVIE_TV_ITEM,
        MOVIE_CONTINUE_WATCHING_MOBILE_ITEM,
        MOVIE_CONTINUE_WATCHING_TV_ITEM,
        MOVIE_GRID_MOBILE_ITEM,
        MOVIE_GRID_TV_ITEM,
        MOVIE_SWIPER_MOBILE_ITEM,

        MOVIE_MOBILE,
        MOVIE_TV,
        MOVIE_DIRECTORS_MOBILE,
        MOVIE_DIRECTORS_TV,
        MOVIE_CAST_MOBILE,
        MOVIE_CAST_TV,
        MOVIE_RECOMMENDATIONS_MOBILE,
        MOVIE_RECOMMENDATIONS_TV,

        PEOPLE_MOBILE_ITEM,
        PEOPLE_TV_ITEM,

        PROVIDER_MOBILE_ITEM,
        PROVIDER_TV_ITEM,

        SEASON_MOBILE_ITEM,
        SEASON_TV_ITEM,

        SPORT_MATCH_ITEM,
        TV_CHANNEL_ITEM,

        TV_SHOW_MOBILE_ITEM,
        TV_SHOW_TV_ITEM,
        TV_SHOW_GRID_MOBILE_ITEM,
        TV_SHOW_GRID_TV_ITEM,
        TV_SHOW_SWIPER_MOBILE_ITEM,

        TV_SHOW_MOBILE,
        TV_SHOW_TV,
        TV_SHOW_SEASONS_MOBILE,
        TV_SHOW_SEASONS_TV,
        TV_SHOW_DIRECTORS_MOBILE,
        TV_SHOW_DIRECTORS_TV,
        TV_SHOW_CAST_MOBILE,
        TV_SHOW_CAST_TV,
        TV_SHOW_RECOMMENDATIONS_MOBILE,
        TV_SHOW_RECOMMENDATIONS_TV,
    }

    private val states = mutableMapOf<Int, Parcelable?>()
    private var itemIdentities: List<String> = emptyList()
    private var itemIdentityCounts: MutableMap<String, Int> = mutableMapOf()
    private var itemStableIds: LongArray = longArrayOf()

    var isLoading = false
    private var header: Header<ViewBinding>? = null
    private var onLoadMoreListener: (() -> Unit)? = null
    private var footer: Footer<ViewBinding>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val holder = when (Type.entries[viewType]) {
            Type.CATEGORY_MOBILE_ITEM -> CategoryViewHolder(
                ItemCategoryMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.CATEGORY_TV_ITEM -> CategoryViewHolder(
                ItemCategoryTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.CATEGORY_MOBILE_SWIPER -> CategoryViewHolder(
                ContentCategorySwiperMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.CATEGORY_TV_SWIPER -> CategoryViewHolder(
                ContentCategorySwiperTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.EPISODE_MOBILE_ITEM -> EpisodeViewHolder(
                ItemEpisodeMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.EPISODE_TV_ITEM -> EpisodeViewHolder(
                ItemEpisodeTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.EPISODE_TV_GRID_ITEM -> EpisodeViewHolder(
                ItemEpisodeTvGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM -> EpisodeViewHolder(
                ItemEpisodeContinueWatchingMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.EPISODE_CONTINUE_WATCHING_TV_ITEM -> EpisodeViewHolder(
                ItemEpisodeContinueWatchingTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.FOOTER -> FooterViewHolder(
                footer!!.binding(parent)
            )

            Type.GENRE_GRID_MOBILE_ITEM -> GenreViewHolder(
                ItemGenreGridMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.GENRE_GRID_TV_ITEM -> GenreViewHolder(
                ItemGenreGridTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.HEADER -> HeaderViewHolder(
                header!!.binding(parent)
            )

            Type.LOADING_ITEM -> LoadingViewHolder(
                ItemLoadingBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.LIVESTREAM_MOBILE_ITEM -> TvShowViewHolder(
                ItemLivestreamMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.LIVESTREAM_TV_ITEM -> TvShowViewHolder(
                ItemLivestreamTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM,
            Type.MOVIE_MOBILE_ITEM -> MovieViewHolder(
                ItemMovieMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_CONTINUE_WATCHING_TV_ITEM,
            Type.MOVIE_TV_ITEM -> MovieViewHolder(
                ItemMovieTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_GRID_MOBILE_ITEM -> MovieViewHolder(
                ItemMovieGridMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_GRID_TV_ITEM -> MovieViewHolder(
                ItemMovieGridTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_SWIPER_MOBILE_ITEM -> MovieViewHolder(
                ItemCategorySwiperMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.MOVIE_MOBILE -> MovieViewHolder(
                ContentMovieMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_TV -> MovieViewHolder(
                ContentMovieTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_DIRECTORS_MOBILE -> MovieViewHolder(
                ContentMovieDirectorsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_DIRECTORS_TV -> MovieViewHolder(
                ContentMovieDirectorsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_CAST_MOBILE -> MovieViewHolder(
                ContentMovieCastMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_CAST_TV -> MovieViewHolder(
                ContentMovieCastTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_RECOMMENDATIONS_MOBILE -> MovieViewHolder(
                ContentMovieRecommendationsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_RECOMMENDATIONS_TV -> MovieViewHolder(
                ContentMovieRecommendationsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.PEOPLE_MOBILE_ITEM -> PeopleViewHolder(
                ItemPeopleMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.PEOPLE_TV_ITEM -> PeopleViewHolder(
                ItemPeopleTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.PROVIDER_MOBILE_ITEM -> ProviderViewHolder(
                ItemProviderMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.PROVIDER_TV_ITEM -> ProviderViewHolder(
                ItemProviderTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.SEASON_MOBILE_ITEM -> SeasonViewHolder(
                ItemSeasonMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.SEASON_TV_ITEM -> SeasonViewHolder(
                ItemSeasonTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.SPORT_MATCH_ITEM -> SportMatchViewHolder(
                if (parent.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
                    ItemSportMatchTvBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false,
                    )
                } else {
                    ItemSportMatchMobileBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false,
                    )
                }
            )

            Type.TV_CHANNEL_ITEM -> TvShowViewHolder( // Reusing for now
                if (parent.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
                    ItemTvShowTvBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false,
                    )
                } else {
                    ItemTvShowMobileBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false,
                    )
                }
            )

            Type.TV_SHOW_MOBILE_ITEM -> TvShowViewHolder(
                ItemTvShowMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_TV_ITEM -> TvShowViewHolder(
                ItemTvShowTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_GRID_MOBILE_ITEM -> TvShowViewHolder(
                ItemTvShowGridMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_GRID_TV_ITEM -> TvShowViewHolder(
                ItemTvShowGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_SWIPER_MOBILE_ITEM -> TvShowViewHolder(
                ItemCategorySwiperMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            Type.TV_SHOW_MOBILE -> TvShowViewHolder(
                ContentTvShowMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_TV -> TvShowViewHolder(
                ContentTvShowTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_SEASONS_MOBILE -> TvShowViewHolder(
                ContentTvShowSeasonsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_SEASONS_TV -> TvShowViewHolder(
                ContentTvShowSeasonsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_DIRECTORS_MOBILE -> TvShowViewHolder(
                ContentTvShowDirectorsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_DIRECTORS_TV -> TvShowViewHolder(
                ContentTvShowDirectorsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_CAST_MOBILE -> TvShowViewHolder(
                ContentTvShowCastMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_CAST_TV -> TvShowViewHolder(
                ContentTvShowCastTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_RECOMMENDATIONS_MOBILE -> TvShowViewHolder(
                ContentTvShowRecommendationsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_RECOMMENDATIONS_TV -> TvShowViewHolder(
                ContentTvShowRecommendationsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
        }

        var p = parent.parent
        while (p != null) {
            if (p is androidx.viewpager2.widget.ViewPager2) {
                holder.itemView.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                break
            }
            p = p.parent
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position >= itemCount - 5 && !isLoading) {
            onLoadMoreListener?.invoke()
            isLoading = true
        }

        val adjustedPosition = header?.let { position - 1 } ?: position
        when (holder) {
            is CategoryViewHolder -> holder.bind(
                items[adjustedPosition] as Category,
                onMovieClickListener,
                onTvShowClickListener,
                onViewAllClickListener,
            )
            is EpisodeViewHolder -> holder.bind(
                items[adjustedPosition] as Episode
            ) // Tu original no pasaba listener, lo respeto
            is FooterViewHolder -> footer?.bind?.invoke(holder.binding)
            is GenreViewHolder -> holder.bind(
                items[adjustedPosition] as Genre
            ) // Tu original no pasaba listener, lo respeto
            is HeaderViewHolder -> header?.bind?.invoke(holder.binding)
            is MovieViewHolder -> holder.bind(
                items[adjustedPosition] as Movie
            ) // Los listeners se manejan dentro del ViewHolder
            is PeopleViewHolder -> holder.bind(
                items[adjustedPosition] as People
            ) // Tu original no pasaba listener, lo respeto
            is ProviderViewHolder -> holder.bind(
                items[adjustedPosition] as Provider
            ) // Tu original no pasaba listener, lo respeto
            is SeasonViewHolder -> holder.bind(
                items[adjustedPosition] as Season
            ) // Tu original no pasaba listener, lo respeto
            is SportMatchViewHolder -> holder.bind(
                items[adjustedPosition] as SportMatch
            )
            is TvShowViewHolder -> holder.bind(
                items[adjustedPosition] as TvShow
            ) // Los listeners se manejan dentro del ViewHolder
        }

        val state = states[holder.layoutPosition]
        if (state != null) {
            when (holder) {
                is CategoryViewHolder -> holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state)
                is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state)
                is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state)
            }
        }
    }

    private var submitGeneration = 0

    override fun getItemCount(): Int = items.size +
            (header?.let { 1 } ?: 0) +
            (onLoadMoreListener?.let { 1 } ?: 0) +
            (footer?.let { 1 } ?: 0)

    override fun getItemId(position: Int): Long {
        if (header != null && position == 0) return Long.MIN_VALUE

        val loadMorePosition = itemCount - 1 - (if (footer != null) 1 else 0)
        if (onLoadMoreListener != null && position == loadMorePosition) {
            return Long.MIN_VALUE + 1
        }

        if (footer != null && position == itemCount - 1) {
            return Long.MIN_VALUE + 2
        }

        val adjustedPosition = header?.let { position - 1 } ?: position
        if (adjustedPosition in itemStableIds.indices) {
            return itemStableIds[adjustedPosition]
        }

        return RecyclerView.NO_ID
    }

    override fun getItemViewType(position: Int): Int {
        if (header != null && position == 0) {
            return Type.HEADER.ordinal
        }

        val loadMorePosition = itemCount - 1 - (if (footer != null) 1 else 0)
        if (onLoadMoreListener != null && position == loadMorePosition) {
            return Type.LOADING_ITEM.ordinal
        }

        if (footer != null && position == itemCount - 1) {
            return Type.FOOTER.ordinal
        }

        val adjustedPosition = header?.let { position - 1 } ?: position
        if (adjustedPosition in items.indices) {
            return try {
                items[adjustedPosition].itemType.ordinal
            } catch (e: Exception) {
                Type.LOADING_ITEM.ordinal
            }
        }

        return Type.LOADING_ITEM.ordinal
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)

        val state = when (holder) {
            is CategoryViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            else -> null
        }

        if (state != null) {
            states[holder.layoutPosition] = state
        } else {
            states.remove(holder.layoutPosition)
        }
    }

    fun onSaveInstanceState(recyclerView: RecyclerView) {
        val offset = header?.let { 1 } ?: 0
        for (i in items.indices) {
            val adapterPosition = i + offset
            val holder = recyclerView.findViewHolderForAdapterPosition(adapterPosition) ?: continue

            val state = when (holder) {
                is CategoryViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                else -> null
            }

            if (state != null) {
                states[adapterPosition] = state
            } else {
                states.remove(adapterPosition)
            }
        }
    }


    companion object {
        val sharedViewPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(Type.MOVIE_MOBILE_ITEM.ordinal, 25)
            setMaxRecycledViews(Type.MOVIE_TV_ITEM.ordinal, 25)
            setMaxRecycledViews(Type.TV_SHOW_MOBILE_ITEM.ordinal, 25)
            setMaxRecycledViews(Type.TV_SHOW_TV_ITEM.ordinal, 25)
            setMaxRecycledViews(Type.EPISODE_MOBILE_ITEM.ordinal, 20)
            setMaxRecycledViews(Type.EPISODE_TV_ITEM.ordinal, 20)
            setMaxRecycledViews(Type.SPORT_MATCH_ITEM.ordinal, 15)
        }
        private val diffExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    fun submitList(list: List<Item>) {
        isLoading = false
        if (items == list) return

        val generation = ++submitGeneration
        val oldItems = items.toList()
        val newItemCount = list.size

        if (oldItems.isEmpty()) {
            val newIdentityState = list.buildIdentityState()
            items.clear()
            items.addAll(list)
            itemIdentities = newIdentityState.identities
            itemIdentityCounts = newIdentityState.counts
            itemStableIds = newIdentityState.stableIds
            notifyDataSetChanged()
            return
        }

        if (oldItems.size <= newItemCount && oldItems == list.subList(0, oldItems.size)) {
            val appendedItems = list.subList(oldItems.size, newItemCount)
            if (appendedItems.isNotEmpty()) {
                val appendedIdentityState = appendedItems.buildIdentityState(itemIdentityCounts)

                items.addAll(appendedItems)
                itemIdentities = itemIdentities + appendedIdentityState.identities
                itemIdentityCounts = appendedIdentityState.counts
                itemStableIds = itemStableIds + appendedIdentityState.stableIds

                notifyItemRangeInserted(
                    oldItems.size + (header?.let { 1 } ?: 0),
                    appendedItems.size
                )
            }
            return
        }

        val oldIdentities = itemIdentities
        diffExecutor.execute {
            val newIdentityState = list.buildIdentityState()
            val newIdentities = newIdentityState.identities

            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = oldItems[oldItemPosition]
                    val newItem = list[newItemPosition]
                    return oldIdentities.getOrNull(oldItemPosition) == newIdentities.getOrNull(newItemPosition) &&
                            oldItem::class == newItem::class
                }
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldItem = oldItems[oldItemPosition]
                    val newItem = list[newItemPosition]
                    return oldItem == newItem
                }
            })

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (generation != submitGeneration) return@post

                val newStates = mutableMapOf<Int, android.os.Parcelable?>()
                val headerOffset = header?.let { 1 } ?: 0
                for (newItemPosition in list.indices) {
                    val oldItemPosition = result.convertNewPositionToOld(newItemPosition)
                    if (oldItemPosition != -1) {
                        states[oldItemPosition + headerOffset]?.let { newStates[newItemPosition + headerOffset] = it }
                    }
                }

                states.clear()
                states.putAll(newStates)

                items.clear()
                items.addAll(list)
                itemIdentities = newIdentities
                itemIdentityCounts = newIdentityState.counts
                itemStableIds = newIdentityState.stableIds

                try {
                    result.dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
                        override fun onInserted(position: Int, count: Int) {
                            notifyItemRangeInserted(position + headerOffset, count)
                        }
                        override fun onRemoved(position: Int, count: Int) {
                            notifyItemRangeRemoved(position + headerOffset, count)
                        }
                        override fun onMoved(fromPosition: Int, toPosition: Int) {
                            notifyItemMoved(fromPosition + headerOffset, toPosition + headerOffset)
                        }
                        override fun onChanged(position: Int, count: Int, payload: Any?) {
                            notifyItemRangeChanged(position + headerOffset, count, payload)
                        }
                    })
                } catch (_: Exception) {
                    notifyDataSetChanged()
                }
            }
        }
    }


    fun <T : ViewBinding> setHeader(
        binding: (parent: ViewGroup) -> T,
        bind: ((binding: T) -> Unit)? = null,
    ) {
        val hadHeader = this.header != null
        @Suppress("UNCHECKED_CAST")
        this.header = Header(
            binding = binding,
            bind = bind as ((ViewBinding) -> Unit)?,
        )
        if (hadHeader) {
            notifyItemChanged(0)
        } else {
            notifyItemInserted(0)
        }
    }

    fun setOnLoadMoreListener(onLoadMoreListener: (() -> Unit)?) {
        val hadListener = this.onLoadMoreListener != null
        val hasListener = onLoadMoreListener != null
        this.onLoadMoreListener = onLoadMoreListener

        val position = items.size + (header?.let { 1 } ?: 0)
        if (hadListener && !hasListener) {
            notifyItemRemoved(position)
        } else if (!hadListener && hasListener) {
            notifyItemInserted(position)
        }
    }

    fun <T : ViewBinding> setFooter(
        binding: (parent: ViewGroup) -> T,
        bind: ((binding: T) -> Unit)? = null,
    ) {
        val hadFooter = this.footer != null
        @Suppress("UNCHECKED_CAST")
        this.footer = Footer(
            binding = binding,
            bind = bind as ((ViewBinding) -> Unit)?,
        )
        if (hadFooter) {
            notifyItemChanged(itemCount - 1)
        } else {
            notifyItemInserted(itemCount - 1)
        }
    }


    private class HeaderViewHolder(
        val binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    )

    private data class Header<T : ViewBinding>(
        val binding: (parent: ViewGroup) -> T,
        val bind: ((binding: T) -> Unit)? = null,
    )

    private class LoadingViewHolder(
        binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    )

    private class FooterViewHolder(
        val binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    )

    private data class Footer<T : ViewBinding>(
        val binding: (parent: ViewGroup) -> T,
        val bind: ((binding: T) -> Unit)? = null,
    )

    private data class IdentityState(
        val identities: List<String>,
        val counts: MutableMap<String, Int>,
        val stableIds: LongArray,
    )

    private fun List<Item>.buildIdentityState(
        startingCounts: Map<String, Int> = emptyMap()
    ): IdentityState {
        val occurrenceCounts = startingCounts.toMutableMap()
        val identities = ArrayList<String>(size)
        val stableIds = LongArray(size)

        forEachIndexed { index, item ->
            val baseKey = item.baseIdentityKey()
            val typeOrdinal = try { item.itemType.ordinal } catch (e: Exception) { -1 }
            val key = "$typeOrdinal:$baseKey"
            val occurrenceIndex = occurrenceCounts.getOrDefault(key, 0)
            occurrenceCounts[key] = occurrenceIndex + 1

            val identity = "$key:$occurrenceIndex"
            identities.add(identity)
            stableIds[index] = identity.fold(1125899906842597L) { acc, char ->
                31L * acc + char.code
            }
        }

        return IdentityState(
            identities = identities,
            counts = occurrenceCounts,
            stableIds = stableIds,
        )
    }

    private fun Item.baseIdentityKey(): String = when (this) {
        is Category -> "category:${name}"
        is Episode -> "episode:${id}"
        is Genre -> "genre:${id}"
        is Movie -> "movie:${id}"
        is People -> "people:${id}"
        is Provider -> "provider:${name}"
        is Season -> "season:${id}"
        is TvShow -> "tvshow:${id}"
        is SportMatch -> "sportmatch:${id}"
        else -> "item:${this::class.java.simpleName}"
    }
}
