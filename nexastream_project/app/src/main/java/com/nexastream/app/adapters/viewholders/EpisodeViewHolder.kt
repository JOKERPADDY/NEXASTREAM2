package com.nexastream.app.adapters.viewholders

import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import androidx.core.view.isVisible
import com.nexastream.app.providers.Provider
import com.nexastream.app.R
import com.nexastream.app.databinding.ItemEpisodeContinueWatchingMobileBinding
import com.nexastream.app.databinding.ItemEpisodeContinueWatchingTvBinding
import com.nexastream.app.databinding.ItemEpisodeTvGridBinding
import com.nexastream.app.databinding.ItemEpisodeMobileBinding
import com.nexastream.app.databinding.ItemEpisodeTvBinding
import com.nexastream.app.fragments.home.HomeMobileFragmentDirections
import com.nexastream.app.fragments.home.HomeTvFragment
import com.nexastream.app.fragments.home.HomeTvFragmentDirections
import com.nexastream.app.fragments.season.SeasonMobileFragmentDirections
import com.nexastream.app.fragments.season.SeasonTvFragmentDirections
import com.nexastream.app.fragments.tv_show.TvShowMobileFragmentDirections
import com.nexastream.app.fragments.tv_show.TvShowTvFragmentDirections
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Video
import com.nexastream.app.ui.ShowOptionsMobileDialog
import com.nexastream.app.ui.ShowOptionsTvDialog
import com.nexastream.app.utils.EpisodeManager
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.format
import com.nexastream.app.utils.getCurrentFragment
import com.nexastream.app.utils.loadEpisodePoster
import android.util.Log
import com.nexastream.app.utils.DownloadManager
import com.nexastream.app.utils.loadTvShowCardArtwork
import com.nexastream.app.utils.toActivity
import android.app.AlertDialog
import android.widget.Toast
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.nexastream.app.utils.DownloadQualityFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nexastream.app.adapters.AppAdapter

class EpisodeViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val downloadManager: DownloadManager by lazy {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            DownloadManagerEntryPoint::class.java
        ).downloadManager()
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface DownloadManagerEntryPoint {
        fun downloadManager(): DownloadManager
    }

    private lateinit var episode: Episode

    fun bind(episode: Episode) {
        this.episode = episode

        when (_binding) {
            is ItemEpisodeMobileBinding -> displayMobileItem(_binding)
            is ItemEpisodeTvBinding -> displayTvItem(_binding)
            is ItemEpisodeTvGridBinding -> displayTvGridItem(_binding)
            is ItemEpisodeContinueWatchingMobileBinding -> displayContinueWatchingMobileItem(_binding)
            is ItemEpisodeContinueWatchingTvBinding -> displayContinueWatchingTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemEpisodeMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    SeasonMobileFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            loadEpisodePoster(episode) {
                error(R.drawable.glide_fallback_cover)
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade())
            }
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeInfo.text = context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeTitle.text = episode.title ?: context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeReleased.apply {
            text = episode.released?.let { " • ${it.format("yyyy-MM-dd")}" }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvEpisodeOverview.text = episode.overview ?: ""

        binding.btnEpisodeDownload.apply {
            isVisible = Provider.supportsDownloads(UserPreferences.currentProvider)
            setOnClickListener {
                showDownloadDialog()
            }
        }
    }

    private fun displayTvItem(binding: ItemEpisodeTvBinding) {
        val adapter = binding.root.parent?.let { (it as? RecyclerView)?.adapter as? AppAdapter }
        binding.root.apply {
            setOnClickListener {
                if (adapter?.isEditMode == true) {
                    episode.isSelected = !episode.isSelected
                    binding.ivSelectionCheck.isVisible = episode.isSelected
                    return@setOnClickListener
                }
                findNavController().navigate(
                    SeasonTvFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                binding.root.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            loadEpisodePoster(episode) {
                error(R.drawable.glide_fallback_cover)
                fallback(R.drawable.glide_fallback_cover)
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade())
            }
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeInfo.text = context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeTitle.text = episode.title ?: context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeReleased.apply {
            text = episode.released?.format("EEEE - MMMM dd, yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvEpisodeOverview.text = episode.overview ?: ""

        binding.btnEpisodeDownload.apply {
            isVisible = false
            setOnClickListener {
                showDownloadDialog()
            }
        }
        
        binding.ivSelectionCheck.isVisible = adapter?.isEditMode == true && episode.isSelected
    }

    private fun displayContinueWatchingMobileItem(binding: ItemEpisodeContinueWatchingMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToTvShow(
                        id = episode.tvShow?.id ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                    )
                )
                findNavController().navigate(
                    TvShowMobileFragmentDirections.actionTvShowToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork()
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
    }

    private fun displayContinueWatchingTvItem(binding: ItemEpisodeContinueWatchingTvBinding) {
        val adapter = binding.root.parent?.let { (it as? RecyclerView)?.adapter as? AppAdapter }
        binding.root.apply {
            setOnClickListener {
                if (adapter?.isEditMode == true) {
                    episode.isSelected = !episode.isSelected
                    binding.ivSelectionCheck.isVisible = episode.isSelected
                    return@setOnClickListener
                }
                findNavController().navigate(
                    HomeTvFragmentDirections.actionHomeToTvShow(
                        id = episode.tvShow?.id ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                    )
                )
                findNavController().navigate(
                    TvShowTvFragmentDirections.actionTvShowToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true

                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> {
                        if (hasFocus) {
                            fragment.pinBackground(episode.tvShow?.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork(withFallback = true)
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
        
        binding.ivSelectionCheck.isVisible = adapter?.isEditMode == true && episode.isSelected
    }

    private fun displayTvGridItem(binding: ItemEpisodeTvGridBinding) {
        val adapter = binding.root.parent?.let { (it as? RecyclerView)?.adapter as? AppAdapter }
        binding.root.apply {
            setOnClickListener {
                if (adapter?.isEditMode == true) {
                    episode.isSelected = !episode.isSelected
                    binding.ivSelectionCheck.isVisible = episode.isSelected
                    return@setOnClickListener
                }
                findNavController().navigate(
                    SeasonTvFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsTvDialog(context, episode).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                binding.root.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            loadEpisodePoster(episode) {
                error(R.drawable.glide_fallback_cover)
                fallback(R.drawable.glide_fallback_cover)
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade())
            }
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeInfo.text = context.getString(R.string.episode_number, episode.number)
        binding.tvEpisodeTitle.text = episode.title ?: context.getString(R.string.episode_number, episode.number)
        binding.tvEpisodeOverview.text = episode.overview ?: ""
        binding.ivSelectionCheck.isVisible = adapter?.isEditMode == true && episode.isSelected
    }

    @androidx.media3.common.util.UnstableApi
    private fun showDownloadDialog() {
        Log.d("DownloadDebug", "showDownloadDialog called for episode ${episode.number}")
        val provider = UserPreferences.currentProvider ?: run {
            Log.e("DownloadDebug", "Current provider is null")
            return
        }
        if (!Provider.supportsDownloads(provider)) return
        val activity = context.toActivity()
        Log.d("DownloadDebug", "Activity found: ${activity != null}")

        val lifecycleScope = itemView.findViewTreeLifecycleOwner()?.lifecycleScope
            ?: activity?.lifecycleScope
            ?: run {
                Log.e("DownloadDebug", "Lifecycle scope not found")
                return
            }

        val videoType = Video.Type.Episode(
            id = episode.id,
            number = episode.number,
            title = episode.title,
            poster = episode.poster,
            overview = episode.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = episode.tvShow?.id ?: "",
                title = episode.tvShow?.title ?: "",
                poster = episode.tvShow?.poster,
                banner = episode.tvShow?.banner,
                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                imdbId = episode.tvShow?.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = episode.season?.number ?: 0,
                title = episode.season?.title,
            ),
        )

        Toast.makeText(context, "Loading servers...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val servers = withContext(Dispatchers.IO) {
                    if (provider is com.nexastream.app.providers.StreamsProvider) {
                        provider.getDownloadServers(
                            title = episode.tvShow?.title ?: episode.title ?: "",
                            releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                            videoType = videoType,
                            contentId = episode.id
                        )
                    } else {
                        provider.getServers(episode.id, videoType)
                    }
                }

                if (servers.isEmpty()) {
                    Toast.makeText(context, "No servers found for download", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (activity != null) {
                    val bs = com.nexastream.app.ui.DownloadQualityBottomSheet.newInstance(
                        servers = servers,
                        mediaId = episode.id,
                        mediaTitle = "${episode.tvShow?.title} - S${episode.season?.number}E${episode.number}",
                        mediaPoster = episode.poster,
                        videoType = videoType
                    )
                    bs.show(activity.supportFragmentManager, "DownloadQualityBottomSheet")
                } else {
                    AlertDialog.Builder(context)
                        .setTitle("Select Download Quality")
                        .setItems(servers.map { downloadQualityDialogLabel(it) }.toTypedArray()) { _, which ->
                            val selectedServer = servers[which]
                            lifecycleScope.launch {
                                try {
                                    Toast.makeText(context, "Starting extraction...", Toast.LENGTH_SHORT).show()
                                    val video = withContext(Dispatchers.IO) {
                                        provider.getVideo(selectedServer)
                                    }
                                    downloadManager.startDownload(
                                        id = episode.id,
                                        title = "${episode.tvShow?.title} - S${episode.season?.number}E${episode.number}",
                                        poster = episode.poster,
                                        url = video.source,
                                        quality = DownloadQualityFormatter.qualityLabel(selectedServer, video),
                                        headers = video.headers,
                                        mimeType = video.type
                                    )
                                    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to get video: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load servers: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadQualityDialogLabel(server: Video.Server): String {
        val details = DownloadQualityFormatter.details(server)
        return if (details.isBlank()) {
            DownloadQualityFormatter.title(server)
        } else {
            "${DownloadQualityFormatter.title(server)}\n$details"
        }
    }

    private fun ImageView.loadContinueWatchingArtwork(withFallback: Boolean = false) {
        val tvShow = episode.tvShow
        if (tvShow == null) {
            loadEpisodePoster(episode) {
                error(R.drawable.glide_fallback_cover)
                apply {
                    if (withFallback) fallback(R.drawable.glide_fallback_cover)
                }
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade())
            }
            return
        }

        loadTvShowCardArtwork(tvShow) {
            error(R.drawable.glide_fallback_cover)
            apply {
                if (withFallback) fallback(R.drawable.glide_fallback_cover)
            }
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }
}
