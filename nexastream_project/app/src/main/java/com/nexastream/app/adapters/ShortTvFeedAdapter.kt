package com.nexastream.app.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.R
import com.nexastream.app.models.TvShow
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Calendar

class ShortTvFeedAdapter(
    private val isLiked: (String) -> Boolean,
    private val onLike: (TvShow) -> Unit,
    private val onAddToList: (TvShow) -> Unit,
    private val onShare: (TvShow) -> Unit,
    private val onSelectEpisode: (TvShow) -> Unit,
    private val onTogglePlayback: (TvShow) -> Unit,
) : ListAdapter<TvShow, ShortTvFeedAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val episodeCounts = mutableMapOf<String, Int?>()
    private val loadedEpisodeCounts = mutableSetOf<String>()
    private val selectedEpisodes = mutableMapOf<String, Int>()
    private var statusBarHeight = 0
    private var navigationBarHeight = 0

    fun setSystemWindowInsets(top: Int, bottom: Int) {
        statusBarHeight = top
        navigationBarHeight = bottom
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_short_tv_feed, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setEpisodeCount(id: String, count: Int?) {
        episodeCounts[id] = count
        loadedEpisodeCounts += id
        val position = currentList.indexOfFirst { it.id == id }
        if (position != -1) notifyItemChanged(position)
    }

    fun refreshActions(id: String) {
        val position = currentList.indexOfFirst { it.id == id }
        if (position != -1) notifyItemChanged(position)
    }

    fun setSelectedEpisode(id: String, episode: Int) {
        selectedEpisodes[id] = episode
        refreshActions(id)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private var boundShowId: String? = null
        private var hasRenderedVideo = false
        private val poster: ImageView = itemView.findViewById(R.id.iv_short_poster)
        private val video: PlayerView = itemView.findViewById(R.id.pv_short_video)
        private val videoProgress: ProgressBar = itemView.findViewById(R.id.pb_short_video)
        private val videoTapTarget: View = itemView.findViewById(R.id.short_video_tap_target)
        private val playbackStateIcon: ImageView = itemView.findViewById(R.id.iv_short_playback_state)
        private val feedActions: View = itemView.findViewById(R.id.short_feed_actions)
        private val feedInformation: View = itemView.findViewById(R.id.short_feed_information)
        private val title: TextView = itemView.findViewById(R.id.tv_short_drama_name)
        private val description: TextView = itemView.findViewById(R.id.tv_short_drama_description)
        private val metadata: TextView = itemView.findViewById(R.id.tv_short_drama_metadata)
        private val likeIcon: ImageView = itemView.findViewById(R.id.iv_short_like)
        private val listIcon: ImageView = itemView.findViewById(R.id.iv_short_list)
        private val likeAction: View = itemView.findViewById(R.id.action_short_like)
        private val listAction: View = itemView.findViewById(R.id.action_short_list)
        private val shareAction: View = itemView.findViewById(R.id.action_short_share)
        private val watchButton: TextView = itemView.findViewById(R.id.btn_short_watch_now)

        fun attachPlayer(player: ExoPlayer) {
            video.player = player
        }

        fun detachPlayer() {
            video.player = null
        }

        fun showPoster() {
            poster.animate().cancel()
            hasRenderedVideo = false
            poster.alpha = 1f
            poster.visibility = View.VISIBLE
            videoProgress.visibility = View.GONE
        }

        fun showVideoLoading() {
            if (!hasRenderedVideo) poster.visibility = View.VISIBLE
            videoProgress.visibility = View.VISIBLE
        }

        fun showVideo() {
            hasRenderedVideo = true
            videoProgress.visibility = View.GONE
            poster.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction { poster.visibility = View.GONE }
                .start()
        }

        fun showVideoError() {
            showPoster()
        }

        fun setControlsVisible(visible: Boolean, playbackPaused: Boolean) {
            val controls = listOf(feedActions, feedInformation)
            controls.forEach { control ->
                control.animate().cancel()
                if (visible) {
                    control.visibility = View.VISIBLE
                    control.animate().alpha(1f).setDuration(CONTROLS_FADE_MS).start()
                } else {
                    control.animate()
                        .alpha(0f)
                        .setDuration(CONTROLS_FADE_MS)
                        .withEndAction { control.visibility = View.GONE }
                        .start()
                }
            }
            playbackStateIcon.apply {
                setImageResource(if (playbackPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
                contentDescription = itemView.context.getString(
                    if (playbackPaused) R.string.short_tv_paused else R.string.short_tv_playing
                )
                visibility = if (visible || playbackPaused) View.VISIBLE else View.GONE
                alpha = if (visible || playbackPaused) 1f else 0f
            }
        }

        fun bind(show: TvShow) {
            val infoParams = feedInformation.layoutParams as ViewGroup.MarginLayoutParams
            infoParams.bottomMargin = 24 * itemView.resources.displayMetrics.density.toInt() + navigationBarHeight
            feedInformation.layoutParams = infoParams

            val actionsParams = feedActions.layoutParams as ViewGroup.MarginLayoutParams
            actionsParams.bottomMargin = 208 * itemView.resources.displayMetrics.density.toInt() + navigationBarHeight
            feedActions.layoutParams = actionsParams

            if (boundShowId != show.id) {
                boundShowId = show.id
                showPoster()
                setControlsVisible(visible = true, playbackPaused = false)
            }
            title.text = show.title
            description.text = show.overview?.takeIf(String::isNotBlank)
                ?: itemView.context.getString(R.string.short_tv_description_fallback)

            val episodeCount = show.seasons.sumOf { it.episodes.size }
                .takeIf { it > 0 }
                ?: episodeCounts[show.id]
            val selectedEpisode = selectedEpisodes[show.id] ?: 1
            val episodeLabel = when {
                episodeCount != null -> itemView.context.getString(
                    R.string.short_tv_episode_selector,
                    selectedEpisode,
                    episodeCount,
                )
                show.id in loadedEpisodeCounts -> itemView.context.getString(
                    R.string.short_tv_episode_selector_unknown,
                    selectedEpisode,
                )
                else -> itemView.context.getString(R.string.short_tv_episode_count_loading)
            }
            val year = show.released?.get(Calendar.YEAR)?.toString()
            val genre = show.genres.firstOrNull()?.name
            description.text = itemView.context.getString(
                R.string.short_tv_current_episode,
                selectedEpisode,
                show.title,
            )
            metadata.text = listOfNotNull(year, genre).joinToString("  •  ")

            val imageUrl = show.poster?.takeIf(String::isNotBlank)
                ?: show.banner?.takeIf(String::isNotBlank)
            Glide.with(itemView)
                .load(imageUrl)
                .centerCrop()
                .placeholder(R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .transition(DrawableTransitionOptions.withCrossFade(240))
                .into(poster)

            val liked = isLiked(show.id)
            likeIcon.setColorFilter(if (liked) RED else Color.WHITE)
            listIcon.setColorFilter(if (show.isFavorite) RED else Color.WHITE)
            likeAction.setOnClickListener { onLike(show) }
            listAction.setOnClickListener { onAddToList(show) }
            shareAction.setOnClickListener { onShare(show) }
            watchButton.text = episodeLabel
            watchButton.setOnClickListener { onSelectEpisode(show) }
            videoTapTarget.setOnClickListener { onTogglePlayback(show) }
        }
    }

    companion object {
        private const val CONTROLS_FADE_MS = 180L
        private val RED = Color.rgb(229, 9, 20)
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TvShow>() {
            override fun areItemsTheSame(oldItem: TvShow, newItem: TvShow): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TvShow, newItem: TvShow): Boolean =
                oldItem.title == newItem.title &&
                    oldItem.overview == newItem.overview &&
                    oldItem.poster == newItem.poster &&
                    oldItem.banner == newItem.banner &&
                    oldItem.isFavorite == newItem.isFavorite &&
                    oldItem.released == newItem.released &&
                    oldItem.genres == newItem.genres
        }
    }
}
