package com.nexastream.app.adapters.viewholders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.nexastream.app.providers.IptvProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.*
import com.nexastream.app.fragments.home.HomeTvFragment
import com.nexastream.app.fragments.home.HomeTvFragmentDirections
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import android.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nexastream.app.fragments.movie.MovieMobileFragmentDirections
import com.nexastream.app.fragments.tv_show.TvShowMobileFragmentDirections
import com.nexastream.app.fragments.tv_show.TvShowTvFragmentDirections
import com.nexastream.app.fragments.movies.MoviesMobileFragmentDirections
import com.nexastream.app.fragments.movies.MoviesTvFragmentDirections
import com.nexastream.app.fragments.search.SearchMobileFragmentDirections
import com.nexastream.app.fragments.search.SearchTvFragmentDirections
import com.nexastream.app.fragments.genre.GenreMobileFragmentDirections
import com.nexastream.app.fragments.genre.GenreTvFragmentDirections
import com.nexastream.app.fragments.people.PeopleMobileFragmentDirections
import com.nexastream.app.fragments.people.PeopleTvFragmentDirections
import com.nexastream.app.fragments.home.HomeMobileFragmentDirections
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.models.LiveChannelMetadata
import com.nexastream.app.models.LiveHealthState
import com.nexastream.app.live.LiveTvRepository
import com.nexastream.app.live.LiveChannelOptionsDialog
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.ui.ShowOptionsMobileDialog
import com.nexastream.app.ui.ShowOptionsTvDialog
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.format
import com.nexastream.app.utils.toActivity
import com.nexastream.app.utils.getCurrentFragment
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.loadTvShowBanner
import com.nexastream.app.utils.loadTvShowPoster
import com.nexastream.app.utils.ArtworkRepair
import com.nexastream.app.providers.Provider
import java.util.Locale
import kotlinx.coroutines.Job

class TvShowViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
    private lateinit var tvShow: TvShow
    private var liveMetadataJob: Job? = null

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentTvShowSeasonsMobileBinding -> _binding.rvTvShowSeasons
            is ContentTvShowSeasonsTvBinding -> _binding.hgvTvShowSeasons
            is ContentTvShowCastMobileBinding -> _binding.rvTvShowCast
            is ContentTvShowCastTvBinding -> _binding.hgvTvShowCast
            is ContentTvShowRecommendationsMobileBinding -> _binding.rvTvShowRecommendations
            is ContentTvShowRecommendationsTvBinding -> _binding.hgvTvShowRecommendations
            else -> null
        }

    fun bind(tvShow: TvShow) {
        this.tvShow = tvShow

        when (_binding) {
            is ItemTvShowMobileBinding -> displayMobileItem(_binding)
            is ItemLivestreamMobileBinding -> displayLivestreamMobileItem(_binding)
            is ItemLivestreamTvBinding -> displayLivestreamTvItem(_binding)
            is ItemTvShowTvBinding -> displayTvItem(_binding)
            is ItemTvShowGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemTvShowGridBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentTvShowMobileBinding -> displayTvShowMobile(_binding)
            is ContentTvShowTvBinding -> displayTvShowTv(_binding)
            is ContentTvShowSeasonsMobileBinding -> displaySeasonsMobile(_binding)
            is ContentTvShowSeasonsTvBinding -> displaySeasonsTv(_binding)
            is ContentTvShowDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentTvShowDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentTvShowCastMobileBinding -> displayCastMobile(_binding)
            is ContentTvShowCastTvBinding -> displayCastTv(_binding)
            is ContentTvShowRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentTvShowRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }

        bindLiveMetadata()
    }

    private fun isIptvProvider(): Boolean {
        if (tvShow.quality == "LIVE") return true
        val name = tvShow.providerName ?: UserPreferences.currentProvider?.name ?: ""
        val provider = Provider.providers.keys.find { it.name == name }
        return provider is IptvProvider
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        action()
    }

    private fun handleDirectPlay(navController: NavController) {
        if (tvShow.id.isBlank()) {
            Toast.makeText(context, "Error: Missing channel ID", Toast.LENGTH_SHORT).show()
            return
        }

        tvShow.liveMetadata?.channelId?.let { channelId ->
            LiveTvRepository.markChannelWatched(channelId, tvShow.id, tvShow.title)
        }
        val videoType = Video.Type.Episode(
            id = tvShow.id,
            number = 1,
            title = tvShow.title,
            poster = tvShow.poster,
            overview = tvShow.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = tvShow.id,
                title = tvShow.title,
                poster = tvShow.poster,
                banner = tvShow.banner,
                releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                imdbId = tvShow.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = 1,
                title = "Live",
            ),
        )
        
        val args = Bundle().apply {
            putString("id", tvShow.id)
            putString("title", tvShow.title)
            putString("subtitle", tvShow.liveMetadata?.nowNext?.now?.title ?: tvShow.title)
            putSerializable("videoType", videoType)
        }
        navController.navigate(R.id.player, args)
    }

    private fun bindLiveMetadata() {
        liveMetadataJob?.cancel()
        if (!isIptvProvider()) return
        applyLiveMetadata(tvShow.liveMetadata)
        val channelId = tvShow.liveMetadata?.channelId ?: return
        val owner = itemView.findViewTreeLifecycleOwner() ?: return
        liveMetadataJob = owner.lifecycleScope.launch {
            LiveTvRepository.observeNowNext(channelId)
                .flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED)
                .collect { nowNext ->
                    val updated = (tvShow.liveMetadata ?: LiveChannelMetadata(channelId)).copy(nowNext = nowNext)
                    tvShow.liveMetadata = updated
                    applyLiveMetadata(updated)
                }
        }
    }

    private fun applyLiveMetadata(metadata: LiveChannelMetadata?) {
        val quality: TextView
        val programme: TextView
        val progress: ProgressBar
        when (val binding = _binding) {
            is ItemTvShowMobileBinding -> {
                quality = binding.tvTvShowQuality
                programme = binding.tvTvShowLastEpisode
                progress = binding.pbTvShowProgress
            }
            is ItemTvShowTvBinding -> {
                quality = binding.tvTvShowQuality
                programme = binding.tvTvShowLastEpisode
                progress = binding.pbTvShowProgress
            }
            is ItemTvShowGridMobileBinding -> {
                quality = binding.tvTvShowQuality
                programme = binding.tvTvShowLastEpisode
                progress = binding.pbTvShowProgress
            }
            is ItemTvShowGridBinding -> {
                quality = binding.tvTvShowQuality
                programme = binding.tvTvShowLastEpisode
                progress = binding.pbTvShowProgress
            }
            else -> return
        }

        val health = metadata?.health ?: LiveHealthState.UNKNOWN
        val sourceText = metadata?.alternativeCount
            ?.takeIf { it > 1 }
            ?.let { " · $it sources" }
            .orEmpty()
        quality.text = "● ${metadata?.quality ?: "LIVE"}$sourceText"
        quality.setTextColor(
            when (health) {
                LiveHealthState.HEALTHY -> android.graphics.Color.rgb(76, 217, 100)
                LiveHealthState.DEGRADED -> android.graphics.Color.rgb(255, 193, 7)
                LiveHealthState.OFFLINE -> android.graphics.Color.rgb(255, 82, 82)
                LiveHealthState.UNKNOWN -> android.graphics.Color.LTGRAY
            },
        )
        quality.isVisible = true

        val nowNext = metadata?.nowNext
        val scheduleText = when {
            nowNext?.now != null && nowNext.next != null ->
                "Now: ${nowNext.now.title}  ·  Next: ${nowNext.next.title}"
            nowNext?.now != null -> "Now: ${nowNext.now.title}"
            nowNext?.next != null -> "Next: ${nowNext.next.title}"
            else -> ""
        }
        programme.text = scheduleText
        programme.isVisible = scheduleText.isNotEmpty()
        val current = nowNext?.now
        progress.progress = if (current != null) {
            val length = (current.endMillis - current.startMillis).coerceAtLeast(1L)
            (((System.currentTimeMillis() - current.startMillis) * 100L) / length).toInt().coerceIn(0, 100)
        } else {
            0
        }
        progress.isVisible = current != null
    }

    private fun tvShowArgs(): Bundle {
        return Bundle().apply {
            putString("id", tvShow.id)
            putString("poster", tvShow.poster)
            putString("banner", tvShow.banner)
        }
    }

    private fun resolveEpisodeSeason(episode: Episode?): Season? {
        if (episode == null) return null

        val currentSeason = episode.season
        val seasonKey = episode.id.substringBeforeLast("/", "")
            .takeIf { it.isNotBlank() }
        if (currentSeason != null && currentSeason.number != 0) {
            return currentSeason
        }

        return tvShow.seasons.firstOrNull { season ->
            season.id == seasonKey ||
                season.id == currentSeason?.id ||
                season.episodes.any { it.id == episode.id } ||
                (episode.number != 0 && season.episodes.any { it.number == episode.number && it.title == episode.title })
        } ?: currentSeason
    }

    private fun setPoster(imageView: ImageView) {
        imageView.scaleType = if (isIptvProvider()) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        imageView.loadTvShowPoster(tvShow) {
            fallback(R.drawable.glide_fallback_cover)
            dontAnimate()
        }
    }

    private fun displayLivestreamMobileItem(binding: ItemLivestreamMobileBinding) {
        binding.root.setOnClickListener {
            checkProviderAndRun {
                handleDirectPlay(binding.root.findNavController())
            }
        }
        binding.root.setOnLongClickListener {
            if (tvShow.liveMetadata != null) LiveChannelOptionsDialog.show(context, tvShow)
            true
        }
        
        binding.ivLivestreamPoster.scaleType = ImageView.ScaleType.FIT_CENTER
        binding.ivLivestreamPoster.setPadding(16, 16, 16, 16)

        Glide.with(context)
            .load(tvShow.poster ?: tvShow.banner)
            .placeholder(R.drawable.bg_livestream_card)
            .error(R.drawable.bg_livestream_card)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivLivestreamPoster)

        binding.tvLivestreamTitle.text = tvShow.title
    }

    private fun displayLivestreamTvItem(binding: ItemLivestreamTvBinding) {
        binding.root.setOnClickListener {
            checkProviderAndRun {
                handleDirectPlay(binding.root.findNavController())
            }
        }
        binding.root.setOnLongClickListener {
            if (tvShow.liveMetadata != null) LiveChannelOptionsDialog.show(context, tvShow)
            true
        }
        
        binding.ivLivestreamPoster.scaleType = ImageView.ScaleType.FIT_CENTER
        binding.ivLivestreamPoster.setPadding(16, 16, 16, 16)

        Glide.with(context)
            .load(tvShow.poster ?: tvShow.banner)
            .placeholder(R.drawable.bg_livestream_card)
            .error(R.drawable.bg_livestream_card)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivLivestreamPoster)

        binding.tvLivestreamTitle.text = tvShow.title
    }

    private fun displayMobileItem(binding: ItemTvShowMobileBinding) {
        binding.root.setOnClickListener {
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener {
            if (isIptvProvider() && tvShow.liveMetadata != null) LiveChannelOptionsDialog.show(context, tvShow)
            else ShowOptionsMobileDialog(context, tvShow).show()
            true
        }
        setPoster(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        val mobileEpisodeBadge = when {
            isIptvProvider() -> "LIVE"
            tvShow.seasons.lastOrNull()?.episodes?.lastOrNull() != null -> "E${tvShow.seasons.lastOrNull()!!.episodes.last()!!.number}"
            else -> null
        }
        binding.tvTvShowLastEpisode.apply {
            text = mobileEpisodeBadge.orEmpty()
            isVisible = !mobileEpisodeBadge.isNullOrEmpty()
        }
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayTvItem(binding: ItemTvShowTvBinding) {
        val adapter = binding.root.parent?.let { (it as? RecyclerView)?.adapter as? AppAdapter }
        binding.root.apply {
            setOnClickListener {
                if (adapter?.isEditMode == true) {
                    tvShow.isSelected = !tvShow.isSelected
                    binding.ivSelectionCheck.isVisible = tvShow.isSelected
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            setOnLongClickListener {
                if (isIptvProvider() && tvShow.liveMetadata != null) LiveChannelOptionsDialog.show(context, tvShow)
                else ShowOptionsTvDialog(context, tvShow).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
                (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)?.let { fragment ->
                    if (hasFocus) {
                        fragment.pinBackground(tvShow.banner)
                    } else {
                        fragment.releasePinnedBackground()
                    }
                }
            }
        }
        setPoster(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = if (isIptvProvider()) "LIVE" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: tvShow.released?.format("yyyy") ?: context.getString(R.string.tv_show_item_type)
        binding.tvTvShowTitle.text = tvShow.title
        
        binding.ivSelectionCheck.isVisible = adapter?.isEditMode == true && tvShow.isSelected
    }

    private fun displayGridMobileItem(binding: ItemTvShowGridMobileBinding) {
        binding.root.setOnClickListener {
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    val current = context.toActivity()?.getCurrentFragment()
                    if (current is com.nexastream.app.fragments.genre.GenreMobileFragment) {
                        binding.root.findNavController().navigate(com.nexastream.app.fragments.genre.GenreMobileFragmentDirections.actionGenreToTvShow(id = tvShow.id, poster = tvShow.poster, banner = tvShow.banner))
                    } else {
                        binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
        }
        binding.root.setOnLongClickListener {
            if (isIptvProvider() && tvShow.liveMetadata != null) LiveChannelOptionsDialog.show(context, tvShow)
            else ShowOptionsMobileDialog(context, tvShow).show()
            true
        }
        setPoster(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        val gridEpisodeBadge = when {
            isIptvProvider() -> "LIVE"
            tvShow.seasons.lastOrNull()?.episodes?.lastOrNull() != null -> "E${tvShow.seasons.lastOrNull()!!.episodes.last()!!.number}"
            else -> null
        }
        binding.tvTvShowLastEpisode.apply {
            text = gridEpisodeBadge.orEmpty()
            isVisible = !gridEpisodeBadge.isNullOrEmpty()
        }
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayGridTvItem(binding: ItemTvShowGridBinding) {
        val adapter = binding.root.parent?.let { (it as? RecyclerView)?.adapter as? AppAdapter }
        binding.root.apply {
            setOnClickListener {
                if (adapter?.isEditMode == true) {
                    tvShow.isSelected = !tvShow.isSelected
                    binding.ivSelectionCheck.isVisible = tvShow.isSelected
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        val current = context.toActivity()?.getCurrentFragment()
                        if (current is com.nexastream.app.fragments.genre.GenreTvFragment) {
                            findNavController().navigate(com.nexastream.app.fragments.genre.GenreTvFragmentDirections.actionGenreToTvShow(id = tvShow.id, poster = tvShow.poster, banner = tvShow.banner))
                        } else {
                            findNavController().navigate(R.id.tv_show, tvShowArgs())
                        }
                    }
                }
            }
            setOnLongClickListener {
                if (isIptvProvider() && tvShow.liveMetadata != null) LiveChannelOptionsDialog.show(context, tvShow)
                else ShowOptionsTvDialog(context, tvShow).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
            }
        }
        setPoster(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = if (isIptvProvider()) "LIVE" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: tvShow.released?.format("yyyy") ?: context.getString(R.string.tv_show_item_type)
        binding.tvTvShowTitle.text = tvShow.title
        
        binding.ivSelectionCheck.isVisible = adapter?.isEditMode == true && tvShow.isSelected
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled("org.smarttube.stable")) installed.add("org.smarttube.stable")
        if (isPackageInstalled("org.smarttube.beta")) installed.add("org.smarttube.beta")
        return installed
    }

    private fun launchSmartTube(packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(packageName)
        context.startActivity(intent)
    }

    private fun showSmartTubeVersionDialog(packages: List<String>, trailerUrl: String, shouldSavePreference: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()

        val items = packages.map { pkg ->
            if (pkg == "org.smarttube.stable") context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]

                if (shouldSavePreference) {
                    editor.putString("preferred_smarttube_package", selectedPackage).apply()
                }

                launchSmartTube(selectedPackage, trailerUrl)
            }.show()
    }

    private fun handleSmartTubeSelection(trailerUrl: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString("preferred_smarttube_package", null)
        val stPackages = getInstalledSmartTubePackages()

        if (stPackages.isEmpty()) {
            context.startActivity(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }

        if (savedPackage != null && stPackages.contains(savedPackage)) {
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TvShowViewHolder", "Failed to launch YouTube intent", e)
            Toast.makeText(context, context.getString(R.string.player_external_player_error_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTrailerClick(trailer: String) {
        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString("preferred_player", "ask")

        when (preferredPlayer) {
            "smarttube" -> {
                handleSmartTubeSelection(trailer)
            }
            "smarttube_stable" -> {
                launchSmartTube("org.smarttube.stable", trailer)
            }
            "smarttube_beta" -> {
                launchSmartTube("org.smarttube.beta", trailer)
            }
            "youtube" -> {
                safeLaunchYoutube(youtubeIntent)
            }
            else -> {
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }.show()
                } else {
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadTvShowBanner(tvShow) {
            centerCrop().transition(DrawableTransitionOptions.withCrossFade())
        }
        binding.tvSwiperTitle.text = tvShow.title
        binding.tvSwiperTvShowLastEpisode.text = if (isIptvProvider()) "" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: context.getString(R.string.tv_show_item_type)
        
        binding.tvSwiperQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvSwiperReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvSwiperRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivSwiperRatingIcon.isVisible = binding.tvSwiperRating.isVisible

        binding.tvSwiperOverview.text = tvShow.overview
        binding.btnSwiperWatchNow.setOnClickListener {
            if (isIptvProvider()) {
                handleDirectPlay(binding.root.findNavController())
            } else {
                binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
            }
        }
    }

    private fun displayTvShowMobile(binding: ContentTvShowMobileBinding) {
        binding.ivTvShowPoster.run {
            loadTvShowPoster(tvShow) {
                fallback(R.drawable.glide_fallback_cover)
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = if (tvShow.poster.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.tvTvShowTitle.text = tvShow.title

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowRuntime.apply {
            text = tvShow.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                    else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
                }
            }
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        binding.tvTvShowOverview.text = tvShow.overview
        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            setOnClickListener {
                val targetEpisode = episodeToWatch ?: return@setOnClickListener
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                } else {
                    val videoType = Video.Type.Episode(
                        id = targetEpisode.id,
                        number = targetEpisode.number,
                        title = targetEpisode.title,
                        poster = targetEpisode.poster,
                        overview = targetEpisode.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShow.id,
                            title = tvShow.title,
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episodeSeason?.number ?: 1,
                            title = episodeSeason?.title ?: "",
                        ),
                    )
                    val args = Bundle().apply {
                        putString("id", targetEpisode.id)
                        putString("title", tvShow.title)
                        putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            text = if (isIptvProvider()) context.getString(R.string.movie_watch_now) else context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
        }

        binding.pbTvShowProgressEpisode.apply {
            val watchHistory = episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }

        binding.btnTvShowTrailer.apply {
            val trailer = tvShow.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer)
            }
            isVisible = trailer != null
        }

        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
        }
    }

    private fun displayTvShowTv(binding: ContentTvShowTvBinding) {
        binding.ivTvShowPoster.run {
            loadTvShowPoster(tvShow) {
                fallback(R.drawable.glide_fallback_cover)
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = if (tvShow.poster.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.tvTvShowTitle.text = tvShow.title

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowRuntime.apply {
            text = tvShow.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                    else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
                }
            }
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        binding.tvTvShowOverview.text = tvShow.overview
        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            setOnClickListener {
                val targetEpisode = episodeToWatch ?: return@setOnClickListener
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                } else {
                    val videoType = Video.Type.Episode(
                        id = targetEpisode.id,
                        number = targetEpisode.number,
                        title = targetEpisode.title,
                        poster = targetEpisode.poster,
                        overview = targetEpisode.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShow.id,
                            title = tvShow.title,
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episodeSeason?.number ?: 1,
                            title = episodeSeason?.title ?: "",
                        ),
                    )
                    val args = Bundle().apply {
                        putString("id", targetEpisode.id)
                        putString("title", tvShow.title)
                        putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            text = if (isIptvProvider()) context.getString(R.string.movie_watch_now) else context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
        }

        binding.pbTvShowProgressEpisode.apply {
            val watchHistory = episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }

        binding.btnTvShowTrailer.apply {
            val trailer = tvShow.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer)
            }
            isVisible = trailer != null
        }

        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
        }
    }

    private fun displaySeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) {
        binding.rvTvShowSeasons.apply {
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach {
                it.tvShow = tvShow
                it.itemType = AppAdapter.Type.SEASON_MOBILE_ITEM
            }) }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displaySeasonsTv(binding: ContentTvShowSeasonsTvBinding) {
        binding.hgvTvShowSeasons.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach {
                it.tvShow = tvShow
                it.itemType = AppAdapter.Type.SEASON_TV_ITEM
            }) }
            setItemSpacing(20)
        }
    }

    private fun displayDirectorsMobile(binding: ContentTvShowDirectorsMobileBinding) { binding.rvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name } }
    private fun displayDirectorsTv(binding: ContentTvShowDirectorsTvBinding) { binding.hgvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name } }
    private fun displayCastMobile(binding: ContentTvShowCastMobileBinding) {
        binding.rvTvShowCast.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentTvShowCastTvBinding) {
        binding.hgvTvShowCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(20)
        }
    }
    private fun displayRecommendationsMobile(binding: ContentTvShowRecommendationsMobileBinding) {
        binding.rvTvShowRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayRecommendationsTv(binding: ContentTvShowRecommendationsTvBinding) {
        binding.hgvTvShowRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                })
            }
            setItemSpacing(20)
        }
    }
}
