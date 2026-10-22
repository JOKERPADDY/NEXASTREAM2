package com.nexastream.app.fragments.shorttv

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.models.ShortTvItem

class ShortTvAdapter(
    private var items: List<ShortTvItem> = emptyList(),
    private val onWatchFullClick: (ShortTvItem) -> Unit,
    private val onLikeClick: (ShortTvItem) -> Unit = {},
    private val onSpeedClick: (ShortTvItem) -> Unit = {},
    private val onEpisodesClick: (ShortTvItem) -> Unit = {},
    private val onDownloadClick: (ShortTvItem) -> Unit = {}
) : RecyclerView.Adapter<ShortTvAdapter.ShortTvViewHolder>() {

    private val attachedViewHolders = mutableMapOf<Int, ShortTvViewHolder>()
    private var activePosition: Int = 0

    fun submitList(newItems: List<ShortTvItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortTvViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_short_tv_reel, parent, false)
        return ShortTvViewHolder(view)
    }

    override fun onBindViewHolder(holder: ShortTvViewHolder, position: Int) {
        attachedViewHolders[position] = holder
        holder.bind(
            item = items[position],
            autoPlay = (position == activePosition),
            onWatchFullClick = onWatchFullClick,
            onLikeClick = onLikeClick,
            onSpeedClick = onSpeedClick,
            onEpisodesClick = onEpisodesClick,
            onDownloadClick = onDownloadClick
        )
    }

    override fun onViewRecycled(holder: ShortTvViewHolder) {
        attachedViewHolders.values.remove(holder)
        holder.releasePlayer()
        super.onViewRecycled(holder)
    }

    fun setActivePage(position: Int) {
        activePosition = position
        attachedViewHolders.forEach { (pos, holder) ->
            if (pos == position) {
                holder.play()
            } else {
                holder.pause()
            }
        }
    }

    fun pauseAll() {
        attachedViewHolders.values.forEach { holder ->
            holder.pause()
        }
    }

    fun releaseAll() {
        attachedViewHolders.values.forEach { holder ->
            holder.releasePlayer()
        }
        attachedViewHolders.clear()
    }

    override fun getItemCount(): Int = items.size

    class ShortTvViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val playerView: PlayerView = itemView.findViewById(R.id.reel_player_view)
        private val episodeTag: TextView = itemView.findViewById(R.id.reel_episode_tag)
        private val titleText: TextView = itemView.findViewById(R.id.reel_title)
        private val descriptionText: TextView = itemView.findViewById(R.id.reel_description)
        private val watchFullButton: Button = itemView.findViewById(R.id.reel_watch_full)
        private val likeButton: ImageButton = itemView.findViewById(R.id.reel_btn_like)
        private val likeCountText: TextView = itemView.findViewById(R.id.reel_like_count)
        private val speedButton: ImageButton = itemView.findViewById(R.id.reel_btn_speed)
        private val speedText: TextView = itemView.findViewById(R.id.reel_speed_text)
        private val episodesButton: ImageButton = itemView.findViewById(R.id.reel_btn_episodes)
        private val downloadButton: ImageButton = itemView.findViewById(R.id.reel_btn_download)

        private var player: ExoPlayer? = null

        @OptIn(UnstableApi::class)
        fun bind(
            item: ShortTvItem,
            autoPlay: Boolean,
            onWatchFullClick: (ShortTvItem) -> Unit,
            onLikeClick: (ShortTvItem) -> Unit,
            onSpeedClick: (ShortTvItem) -> Unit,
            onEpisodesClick: (ShortTvItem) -> Unit,
            onDownloadClick: (ShortTvItem) -> Unit
        ) {
            titleText.text = item.title
            descriptionText.text = item.description
            episodeTag.text = "EP ${item.episodeNumber} / ${item.totalEpisodes}"
            likeCountText.text = "${item.likeCount}"
            speedText.text = "${item.speed}x"

            likeButton.setImageResource(
                if (item.isLiked) R.drawable.ic_favorite_enable else R.drawable.ic_favorite_disable
            )

            watchFullButton.setOnClickListener { onWatchFullClick(item) }
            likeButton.setOnClickListener { onLikeClick(item) }
            speedButton.setOnClickListener { onSpeedClick(item) }
            episodesButton.setOnClickListener { onEpisodesClick(item) }
            downloadButton.setOnClickListener { onDownloadClick(item) }

            releasePlayer()
            val context = itemView.context

            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(item.headers["User-Agent"] ?: "MovieBoxPro/16.2.1 (Android 12; Pixel 6)")
                .setAllowCrossProtocolRedirects(true)

            if (item.headers.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(item.headers)
            }

            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                .setDataSourceFactory(httpDataSourceFactory)

            playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

            val exoPlayer = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(item.videoUrl)))
                    videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    setPlaybackSpeed(item.speed)
                    repeatMode = ExoPlayer.REPEAT_MODE_ONE
                    prepare()
                    playWhenReady = autoPlay
                }
            player = exoPlayer
            playerView.player = exoPlayer
        }

        fun play() {
            player?.playWhenReady = true
        }

        fun pause() {
            player?.playWhenReady = false
        }

        fun releasePlayer() {
            player?.let {
                it.stop()
                it.release()
            }
            player = null
            playerView.player = null
        }
    }
}
