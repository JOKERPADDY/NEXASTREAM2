package com.nexastream.app.fragments.genre

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.adapters.ShortTvFeedAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.FragmentShortTvFeedBinding
import com.nexastream.app.fragments.genre.GenreViewModel.State
import com.nexastream.app.models.TvShow
import com.nexastream.app.providers.StreamsProvider
import com.nexastream.app.providers.StreamsShortEpisodePlayback
import com.nexastream.app.utils.CacheUtils
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
open class ShortTvFeedFragment : Fragment() {

    @Inject
    lateinit var database: AppDatabase

    private var _binding: FragmentShortTvFeedBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GenreViewModel by viewModels()
    private val likedIds = mutableSetOf<String>()
    private val pendingFavorites = mutableSetOf<String>()
    private val loadedEpisodeCounts = mutableSetOf<String>()
    private val episodeCounts = mutableMapOf<String, Int?>()
    private val selectedEpisodes = mutableMapOf<String, Int>()
    private var episodeCountJob: Job? = null
    private var episodePlaybackJob: Job? = null
    private var controlsHideJob: Job? = null
    private var activeShowId: String? = null
    private var userPausedPlayback = false
    private var activeViewHolder: ShortTvFeedAdapter.ViewHolder? = null
    private var player: ExoPlayer? = null
    private val episodePlaybackCache = mutableMapOf<String, StreamsShortEpisodePlayback>()
    private var isLoadingMore = false
    private var hasMoreItems = false
    private var hasAutoCleared409 = false

    private val adapter by lazy {
        ShortTvFeedAdapter(
            isLiked = { it in likedIds },
            onLike = ::toggleLike,
            onAddToList = ::toggleMyList,
            onShare = ::share,
            onSelectEpisode = ::selectEpisode,
            onTogglePlayback = ::togglePlayback,
        )
    }

    private val snapHelper = PagerSnapHelper()
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) {
                activeViewHolder?.showVideoLoading()
            }
        }

        override fun onRenderedFirstFrame() {
            val showId = activeShowId ?: return
            val episode = selectedEpisodes[showId] ?: 1
            if (player?.currentMediaItem?.mediaId == "$showId:$episode") {
                activeViewHolder?.showVideo()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Short TV video playback failed for $activeShowId", error)
            activeViewHolder?.showVideoError()
            context?.let {
                Toast.makeText(it, R.string.short_tv_video_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentShortTvFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        likedIds += requireContext()
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getStringSet(LIKED_SHOWS_KEY, emptySet())
            .orEmpty()

        player = ExoPlayer.Builder(requireContext()).build().also {
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            it.addListener(playerListener)
            it.playWhenReady = false
        }
        setupFeed()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            adapter.setSystemWindowInsets(systemBars.top, systemBars.bottom)
            insets
        }
        binding.btnShortFeedRetry.setOnClickListener { viewModel.getGenre() }
        observeGenre()
    }

    override fun onDestroyView() {
        episodeCountJob?.cancel()
        episodePlaybackJob?.cancel()
        controlsHideJob?.cancel()
        activeViewHolder?.detachPlayer()
        activeViewHolder = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        snapHelper.attachToRecyclerView(null)
        _binding = null
        super.onDestroyView()
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!userPausedPlayback && player?.currentMediaItem != null) player?.play()
    }

    private fun setupFeed() {
        binding.rvShortFeed.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false).apply {
                initialPrefetchItemCount = 1
            }
            adapter = this@ShortTvFeedFragment.adapter
            setHasFixedSize(true)
            setItemViewCacheSize(2)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = true
        }
        snapHelper.attachToRecyclerView(binding.rvShortFeed)
        binding.rvShortFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    onPageChanged()
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    player?.pause()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (hasMoreItems && !isLoadingMore && adapter.itemCount > 0 &&
                    lastVisible >= adapter.itemCount - PREFETCH_THRESHOLD
                ) {
                    viewModel.loadMore()
                }
            }
        })
    }

    private fun observeGenre() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { state ->
                    when (state) {
                        State.Loading -> {
                            isLoadingMore = false
                            binding.pbShortFeed.visibility = View.VISIBLE
                            binding.emptyShortFeed.visibility = View.GONE
                            binding.rvShortFeed.visibility = View.GONE
                        }

                        State.LoadingMore -> isLoadingMore = true

                        is State.SuccessLoading -> {
                            isLoadingMore = false
                            hasMoreItems = state.hasMore
                            binding.pbShortFeed.visibility = View.GONE
                            val shows = state.genre.shows.filterIsInstance<TvShow>()
                            adapter.submitList(shows) {
                                binding.rvShortFeed.visibility = if (shows.isEmpty()) View.GONE else View.VISIBLE
                                binding.emptyShortFeed.visibility = if (shows.isEmpty()) View.VISIBLE else View.GONE
                                binding.tvShortFeedMessage.setText(R.string.short_tv_empty)
                                onPageChanged()
                            }
                        }

                        is State.FailedLoading -> {
                            isLoadingMore = false
                            Log.e(TAG, "Failed to load Short TV", state.error)
                            if (adapter.itemCount > 0) {
                                Toast.makeText(
                                    requireContext(),
                                    state.error.message ?: getString(R.string.short_tv_load_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                binding.pbShortFeed.visibility = View.GONE
                                binding.rvShortFeed.visibility = View.GONE
                                binding.emptyShortFeed.visibility = View.VISIBLE
                                binding.tvShortFeedMessage.setText(R.string.short_tv_load_failed)
                            }

                            val code = (state.error as? retrofit2.HttpException)?.code()
                            if (code == 409 && !hasAutoCleared409) {
                                hasAutoCleared409 = true
                                CacheUtils.clearAppCache(requireContext())
                                viewModel.getGenre()
                            }
                        }
                    }
                }
        }
    }

    private fun onPageChanged() {
        val recycler = _binding?.rvShortFeed ?: return
        val position = snapHelper.findSnapView(recycler.layoutManager)
            ?.let(recycler::getChildAdapterPosition)
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: return
        val show = adapter.currentList.getOrNull(position) ?: return
        val viewHolder = recycler.findViewHolderForAdapterPosition(position) as? ShortTvFeedAdapter.ViewHolder
            ?: return
        val activePlayer = player ?: return

        if (activeViewHolder !== viewHolder) {
            activeViewHolder?.detachPlayer()
            activeViewHolder = viewHolder
            viewHolder.attachPlayer(activePlayer)
        }
        val isNewShow = activeShowId != show.id
        if (isNewShow) userPausedPlayback = false
        controlsHideJob?.cancel()
        viewHolder.setControlsVisible(visible = true, playbackPaused = userPausedPlayback)
        scheduleControlsHide(show)
        viewHolder.itemView.apply {
            animate().cancel()
            alpha = 0.94f
            animate().alpha(1f).setDuration(PAGE_TRANSITION_MS).start()
        }
        preloadNeighborPosters(position)
        if (isNewShow) {
            activeShowId = show.id
            episodePlaybackJob?.cancel()
            startEpisodePlayback(show, selectedEpisodes[show.id] ?: 1)
            loadEpisodeCount(show.id)
        } else if (
            !userPausedPlayback &&
            activePlayer.currentMediaItem != null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            activePlayer.play()
        }
    }

    private fun togglePlayback(show: TvShow) {
        if (activeShowId != show.id) return
        val activePlayer = player ?: return
        userPausedPlayback = !userPausedPlayback
        if (userPausedPlayback) {
            activePlayer.pause()
        } else {
            activePlayer.play()
        }
        activeViewHolder?.setControlsVisible(visible = true, playbackPaused = userPausedPlayback)
        scheduleControlsHide(show)
    }

    private fun scheduleControlsHide(show: TvShow) {
        controlsHideJob?.cancel()
        controlsHideJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(CONTROLS_AUTO_HIDE_MS)
            if (activeShowId == show.id) {
                activeViewHolder?.setControlsVisible(
                    visible = false,
                    playbackPaused = userPausedPlayback,
                )
            }
        }
    }

    private fun preloadNeighborPosters(position: Int) {
        val recycler = _binding?.rvShortFeed ?: return
        val width = recycler.width.takeIf { it > 0 } ?: return
        val height = recycler.height.takeIf { it > 0 } ?: return
        listOf(position - 1, position + 1).forEach { neighbor ->
            val show = adapter.currentList.getOrNull(neighbor) ?: return@forEach
            val poster = show.poster?.takeIf(String::isNotBlank)
                ?: show.banner?.takeIf(String::isNotBlank)
                ?: return@forEach
            Glide.with(this)
                .load(poster)
                .centerCrop()
                .preload(width, height)
        }
    }

    private fun loadEpisodeCount(showId: String) {
        if (showId in loadedEpisodeCounts) return
        episodeCountJob?.cancel()
        episodeCountJob = viewLifecycleOwner.lifecycleScope.launch {
            adapter.setEpisodeCount(showId, null)
            val count = withContext(Dispatchers.IO) {
                StreamsProvider.getShortTvEpisodeCount(showId)
            }
            loadedEpisodeCounts += showId
            episodeCounts[showId] = count
            adapter.setEpisodeCount(showId, count)
        }
    }

    private fun selectEpisode(show: TvShow) {
        val count = episodeCounts[show.id]
        if (count == null || count <= 0) {
            if (show.id !in loadedEpisodeCounts) {
                loadEpisodeCount(show.id)
                Toast.makeText(requireContext(), R.string.short_tv_episode_count_loading, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.short_tv_episode_count_unavailable, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val currentEpisode = selectedEpisodes[show.id] ?: 1
        AlertDialog.Builder(requireContext())
            .setTitle(show.title)
            .setSingleChoiceItems(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_single_choice,
                    (1..count).map { getString(R.string.short_tv_episode_option, it) },
                ),
                currentEpisode - 1,
            ) { dialog, selected ->
                val episode = selected + 1
                selectedEpisodes[show.id] = episode
                adapter.setSelectedEpisode(show.id, episode)
                if (activeShowId == show.id) startEpisodePlayback(show, episode)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startEpisodePlayback(show: TvShow, episode: Int) {
        userPausedPlayback = false
        player?.apply {
            pause()
            stop()
            clearMediaItems()
        }
        activeViewHolder?.showPoster()
        activeViewHolder?.showVideoLoading()
        loadEpisodePlayback(show, episode)
    }

    private fun loadEpisodePlayback(show: TvShow, episode: Int) {
        episodePlaybackJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cacheKey = "${show.id}:$episode"
                val playback = episodePlaybackCache[cacheKey] ?: withContext(Dispatchers.IO) {
                    StreamsProvider.getShortTvEpisodePlayback(show.id, episode)
                }
                if (activeShowId != show.id || selectedEpisodes[show.id]?.let { it != episode } == true) {
                    return@launch
                }
                if (playback == null) {
                    activeViewHolder?.showVideoError()
                    Toast.makeText(
                        requireContext(),
                        R.string.short_tv_video_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                episodePlaybackCache[cacheKey] = playback
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(playback.requestHeaders)
                val mediaItem = MediaItem.Builder()
                    .setMediaId("${show.id}:$episode")
                    .setUri(playback.url)
                    .setMimeType(playback.mimeType)
                    .build()
                val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                val activePlayer = player ?: return@launch
                activePlayer.setMediaSource(mediaSource)
                activePlayer.prepare()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    activePlayer.play()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to load Short TV video for ${show.id}", e)
                if (activeShowId == show.id) {
                    activeViewHolder?.showVideoError()
                    Toast.makeText(
                        requireContext(),
                        R.string.short_tv_video_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun toggleLike(show: TvShow) {
        if (!likedIds.add(show.id)) likedIds.remove(show.id)
        requireContext()
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(LIKED_SHOWS_KEY, likedIds.toSet())
            .apply()
        adapter.refreshActions(show.id)
    }

    private fun toggleMyList(show: TvShow) {
        if (!pendingFavorites.add(show.id)) return
        val shouldFavorite = !show.isFavorite
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.tvShowDao().upsertFavorite(show, shouldFavorite)
                }
                show.isFavorite = shouldFavorite
                adapter.refreshActions(show.id)
                Toast.makeText(
                    requireContext(),
                    if (shouldFavorite) R.string.short_tv_added_to_list else R.string.short_tv_removed_from_list,
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to update Short TV My List entry", e)
                Toast.makeText(
                    requireContext(),
                    e.message ?: getString(R.string.short_tv_load_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                pendingFavorites.remove(show.id)
            }
        }
    }

    private fun share(show: TvShow) {
        val text = getString(R.string.short_tv_share_text, show.title)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.short_tv_share_chooser)))
    }

    companion object {
        private const val TAG = "ShortTvFeed"
        private const val PREFERENCES_NAME = "short_tv_feed"
        private const val LIKED_SHOWS_KEY = "liked_shows"
        private const val PREFETCH_THRESHOLD = 3
        private const val PAGE_TRANSITION_MS = 220L
        private const val CONTROLS_AUTO_HIDE_MS = 5_000L
    }
}
