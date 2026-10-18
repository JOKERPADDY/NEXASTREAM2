package com.nexastream.app.adapters.viewholders

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import androidx.core.view.isVisible
import com.nexastream.app.NavMainGraphMobileDirections
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.ContentMovieCastMobileBinding
import com.nexastream.app.databinding.ContentMovieCastTvBinding
import com.nexastream.app.databinding.ContentMovieMobileBinding
import com.nexastream.app.databinding.ContentMovieRecommendationsMobileBinding
import com.nexastream.app.databinding.ContentMovieRecommendationsTvBinding
import com.nexastream.app.databinding.ContentMovieTvBinding
import com.nexastream.app.databinding.ItemCategorySwiperMobileBinding
import com.nexastream.app.databinding.ItemMovieGridMobileBinding
import com.nexastream.app.databinding.ItemMovieGridTvBinding
import com.nexastream.app.databinding.ItemMovieMobileBinding
import com.nexastream.app.databinding.ItemMovieTvBinding
import com.nexastream.app.fragments.genre.GenreMobileFragment
import com.nexastream.app.fragments.genre.GenreMobileFragmentDirections
import com.nexastream.app.fragments.genre.GenreTvFragment
import com.nexastream.app.fragments.genre.GenreTvFragmentDirections
import com.nexastream.app.fragments.home.HomeMobileFragment
import com.nexastream.app.fragments.home.HomeMobileFragmentDirections
import com.nexastream.app.fragments.home.HomeTvFragment
import com.nexastream.app.fragments.home.HomeTvFragmentDirections
import com.nexastream.app.fragments.movie.MovieMobileFragment
import com.nexastream.app.fragments.movie.MovieMobileFragmentDirections
import com.nexastream.app.fragments.movie.MovieTvFragment
import com.nexastream.app.fragments.movie.MovieTvFragmentDirections
import com.nexastream.app.fragments.movies.MoviesMobileFragment
import com.nexastream.app.fragments.movies.MoviesMobileFragmentDirections
import com.nexastream.app.fragments.movies.MoviesTvFragment
import com.nexastream.app.fragments.movies.MoviesTvFragmentDirections
import com.nexastream.app.fragments.people.PeopleMobileFragment
import com.nexastream.app.fragments.people.PeopleMobileFragmentDirections
import com.nexastream.app.fragments.people.PeopleTvFragment
import com.nexastream.app.fragments.people.PeopleTvFragmentDirections
import com.nexastream.app.fragments.search.SearchMobileFragment
import com.nexastream.app.fragments.search.SearchMobileFragmentDirections
import com.nexastream.app.fragments.search.SearchTvFragment
import com.nexastream.app.fragments.search.SearchTvFragmentDirections
import com.nexastream.app.fragments.tv_show.TvShowMobileFragment
import com.nexastream.app.fragments.tv_show.TvShowMobileFragmentDirections
import com.nexastream.app.fragments.tv_show.TvShowTvFragment
import com.nexastream.app.fragments.tv_show.TvShowTvFragmentDirections
import com.nexastream.app.fragments.tv_shows.TvShowsTvFragment
import com.nexastream.app.fragments.tv_shows.TvShowsTvFragmentDirections
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.ui.ShowOptionsMobileDialog
import com.nexastream.app.ui.ShowOptionsTvDialog
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.dp
import androidx.preference.Preference
import com.nexastream.app.utils.format
import com.nexastream.app.utils.getCurrentFragment
import com.nexastream.app.utils.loadMovieBanner
import com.nexastream.app.utils.loadMoviePoster
import com.nexastream.app.utils.ArtworkRepair
import com.nexastream.app.utils.toActivity
import java.util.Locale
import com.nexastream.app.utils.DownloadManager
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.providers.Provider
import android.view.KeyEvent
import android.widget.TextView
import com.nexastream.app.databinding.ContentMovieDirectorsMobileBinding
import com.nexastream.app.databinding.ContentMovieDirectorsTvBinding
import com.nexastream.app.utils.DownloadQualityFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.nexastream.app.utils.ReminderManager
import kotlinx.coroutines.withContext

@androidx.media3.common.util.UnstableApi
class MovieViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
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

    private lateinit var movie: Movie
    private val TAG = "TrailerChoiceDebug" // Logging Tag

    companion object {
        private const val KEY_PREFERRED_PLAYER = "preferred_player"
        private const val KEY_SMARTTUBE_PACKAGE = "preferred_smarttube_package" // New key for saving the exact package
        private const val PLAYER_YOUTUBE = "youtube"
        private const val PLAYER_SMARTTUBE = "smarttube"
        private const val PLAYER_SMARTTUBE_STABLE = "smarttube_stable"
        private const val PLAYER_SMARTTUBE_BETA = "smarttube_beta"
        private const val PLAYER_ASK = "ask"
        private const val SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable"
        private const val SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_TV_PACKAGE = "com.google.android.tv.youtube"
    }

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentMovieCastMobileBinding -> _binding.rvMovieCast
            is ContentMovieCastTvBinding -> _binding.hgvMovieCast
            is ContentMovieRecommendationsMobileBinding -> _binding.rvMovieRecommendations
            is ContentMovieRecommendationsTvBinding -> _binding.hgvMovieRecommendations
            else -> null
        }

    fun bind(movie: Movie) {
        this.movie = movie

        when (_binding) {
            is ItemMovieMobileBinding -> displayMobileItem(_binding)
            is ItemMovieTvBinding -> displayTvItem(_binding)
            is ItemMovieGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemMovieGridTvBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentMovieMobileBinding -> displayMovieMobile(_binding)
            is ContentMovieTvBinding -> displayMovieTv(_binding)
            is ContentMovieDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentMovieDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentMovieCastMobileBinding -> displayCastMobile(_binding)
            is ContentMovieCastTvBinding -> displayCastTv(_binding)
            is ContentMovieRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentMovieRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        val providerName = movie.providerName
        if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
            val prev = UserPreferences.currentProvider
            UserPreferences.getProviderByName(providerName)?.let {
                UserPreferences.currentProvider = it
                if (UserPreferences.previousProviderToRestore == null) {
                    UserPreferences.previousProviderToRestore = prev
                }
                Toast.makeText(context, context.getString(R.string.switching_to_provider, providerName), Toast.LENGTH_SHORT).show()
            }
        }
        action()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled(SMARTTUBE_STABLE_PACKAGE)) installed.add(SMARTTUBE_STABLE_PACKAGE)
        if (isPackageInstalled(SMARTTUBE_BETA_PACKAGE)) installed.add(SMARTTUBE_BETA_PACKAGE)
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
            if (pkg == SMARTTUBE_STABLE_PACKAGE) context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]
                
                if (shouldSavePreference) {
                    // Salva la scelta dell'utente se la preferenza principale è "smarttube"
                    editor.putString(KEY_SMARTTUBE_PACKAGE, selectedPackage).apply()
                    Log.d(TAG, "SmartTube version saved: $selectedPackage")
                }
                
                launchSmartTube(selectedPackage, trailerUrl)
            }.show()
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube intent", e)
            Toast.makeText(context, context.getString(R.string.player_external_player_error_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSmartTubeSelection(trailerUrl: String, logPrefix: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString(KEY_SMARTTUBE_PACKAGE, null)
        val stPackages = getInstalledSmartTubePackages()

        Log.d(TAG, "$logPrefix: SmartTube packages found: ${stPackages.size}. Saved package: $savedPackage")
        
        if (stPackages.isEmpty()) {
            // Caso 1: Nessuna SmartTube installata. Fallback su YouTube.
            Log.d(TAG, "$logPrefix: No SmartTube installed, falling back to YouTube")
            safeLaunchYoutube(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            // Caso 2: Una sola SmartTube installata. Avvia direttamente.
            Log.d(TAG, "$logPrefix: Only one SmartTube installed: ${stPackages[0]}. Launching directly.")
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }
        
        // Caso 3: Stable e Beta installate.
        if (savedPackage != null && stPackages.contains(savedPackage)) {
            // Caso 3a: Versione preferita è installata. Avvia direttamente la versione salvata.
            Log.d(TAG, "$logPrefix: Saved SmartTube version found: $savedPackage. Launching directly.")
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            // Caso 3b: Nessuna preferenza salvata O la versione salvata non è più installata. Chiedi all'utente e salva la nuova scelta.
            Log.d(TAG, "$logPrefix: Saved version invalid or missing. Asking user which version to use.")
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun handleTrailerClick(trailer: String, logPrefix: String) {
        Log.d(TAG, "$logPrefix: Clicked. Trailer URL: $trailer")

        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString(KEY_PREFERRED_PLAYER, PLAYER_ASK)
        Log.d(TAG, "$logPrefix: Preferred player from settings: $preferredPlayer")

        when (preferredPlayer) {
            PLAYER_SMARTTUBE -> {
                handleSmartTubeSelection(trailer, logPrefix)
            }
            PLAYER_SMARTTUBE_STABLE -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Stable (Preferred)")
                launchSmartTube(SMARTTUBE_STABLE_PACKAGE, trailer)
            }
            PLAYER_SMARTTUBE_BETA -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Beta (Preferred)")
                launchSmartTube(SMARTTUBE_BETA_PACKAGE, trailer)
            }
            PLAYER_YOUTUBE -> {
                Log.d(TAG, "$logPrefix: Launching YouTube (Preferred)")
                safeLaunchYoutube(youtubeIntent)
            }
            else -> { // PLAYER_ASK or nothing set
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    Log.d(TAG, "$logPrefix: Showing choice dialog (Ask)")
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                Log.d(TAG, "$logPrefix: Dialog (Ask): YouTube selected")
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                Log.d(TAG, "$logPrefix: Dialog (Ask): SmartTube selected")
                                // Qui, non salvare la preferenza per la versione SmartTube,
                                // ma chiedi quale usare se ci sono due installazioni.
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }.show()
                } else {
                    Log.d(TAG, "$logPrefix: SmartTube not found, launching YouTube directly")
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displayMobileItem(binding: ItemMovieMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                checkProviderAndRun {
                    val navController = findNavController()
                    val currentDestId = navController.currentDestination?.id

                    if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM) {
                        navController.navigate(
                            NavMainGraphMobileDirections.actionGlobalPlayer(
                                id = movie.id,
                                title = movie.title,
                                subtitle = movie.released?.format("yyyy") ?: "",
                                videoType = Video.Type.Movie(
                                    id = movie.id,
                                    title = movie.title,
                                    releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                    poster = movie.poster ?: "",
                                    imdbId = movie.imdbId
                                )
                            )
                        )
                    } else {
                        when (currentDestId) {
                            R.id.home -> navController.navigate(HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id))
                            R.id.movie -> navController.navigate(MovieMobileFragmentDirections.actionMovieToMovie(id = movie.id))
                            R.id.tv_show -> navController.navigate(TvShowMobileFragmentDirections.actionTvShowToMovie(id = movie.id))
                            else -> navController.navigate(NavMainGraphMobileDirections.actionGlobalMovie(id = movie.id))
                        }
                    }
                }
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, movie).show()
                true
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            dontAnimate()
        }

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        bindReminderButton(binding.ivMovieRemind, movie)

        binding.tvMovieReleasedYear.visibility = View.GONE

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvMovieTitle.text = movie.title
    }

    private fun displayTvItem(binding: ItemMovieTvBinding) {
        val adapter = binding.root.parent?.let { (it as? RecyclerView)?.adapter as? AppAdapter }
        binding.root.apply {
            isFocusable = true
            setOnClickListener {
                if (adapter?.isEditMode == true) {
                    movie.isSelected = !movie.isSelected
                    binding.ivSelectionCheck.isVisible = movie.isSelected
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> {
                            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM) {
                                findNavController().navigate(
                                    R.id.action_global_player,
                                    Bundle().apply {
                                        putString("id", movie.id)
                                        putString("title", movie.title)
                                        putString("subtitle", movie.released?.format("yyyy") ?: "")
                                        putSerializable(
                                            "videoType",
                                            Video.Type.Movie(
                                                id = movie.id,
                                                title = movie.title,
                                                releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                                poster = movie.poster ?: movie.banner ?: "",
                                                imdbId = movie.imdbId,
                                            )
                                        )
                                    }
                                )
                            } else {
                                findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                            }
                        }
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is MovieTvFragment -> findNavController().navigate(MovieTvFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowTvFragment -> findNavController().navigate(TvShowTvFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                    }
                }
            }


            setOnLongClickListener {
                ShowOptionsTvDialog(context, movie).show()
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
                            fragment.pinBackground(movie.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            centerCrop()
            dontAnimate()
        }
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)
        binding.tvMovieTitle.text = movie.title
        
        binding.ivSelectionCheck.isVisible = adapter?.isEditMode == true && movie.isSelected
    }

    private fun displayGridMobileItem(binding: ItemMovieGridMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is GenreMobileFragment -> findNavController().navigate(GenreMobileFragmentDirections.actionGenreToMovie(id = movie.id))
                        is MoviesMobileFragment -> findNavController().navigate(MoviesMobileFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is PeopleMobileFragment -> findNavController().navigate(PeopleMobileFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is SearchMobileFragment -> findNavController().navigate(SearchMobileFragmentDirections.actionSearchToMovie(id = movie.id))
                    }
                }
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, movie).show()
                true
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            dontAnimate()
        }

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        bindReminderButton(binding.ivMovieRemind, movie)

        binding.tvMovieReleasedYear.visibility = View.GONE

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvMovieTitle.text = movie.title
    }

    private fun bindReminderButton(remindView: ImageView, movie: Movie) {
        val qualityStr = movie.quality ?: ""
        val isUpcoming = qualityStr.contains(Regex("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Coming Soon|2026)", RegexOption.IGNORE_CASE))
            || (movie.released != null && movie.released!!.timeInMillis > System.currentTimeMillis())

        remindView.apply {
            isVisible = isUpcoming
            if (isUpcoming) {
                val isSet = ReminderManager.isReminderSet(context, movie.id)
                setBackgroundResource(if (isSet) R.drawable.bg_remind_badge_active else R.drawable.bg_remind_badge)
                setOnClickListener {
                    val updated = ReminderManager.toggleReminder(context, movie.id, movie.title)
                    setBackgroundResource(if (updated) R.drawable.bg_remind_badge_active else R.drawable.bg_remind_badge)
                }
            }
        }
    }

    private fun displayGridTvItem(binding: ItemMovieGridTvBinding) {
        val adapter = binding.root.parent?.let { (it as? RecyclerView)?.adapter as? AppAdapter }
        binding.root.apply {
            isFocusable = true
            setOnClickListener {
                if (adapter?.isEditMode == true) {
                    movie.isSelected = !movie.isSelected
                    binding.ivSelectionCheck.isVisible = movie.isSelected
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                    }
                }
            }

            setOnLongClickListener {
                ShowOptionsTvDialog(context, movie).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true
            }
        }
        binding.ivMoviePoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            centerCrop()
            dontAnimate()
        }
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)
        binding.tvMovieTitle.text = movie.title
        
        binding.ivSelectionCheck.isVisible = adapter?.isEditMode == true && movie.isSelected
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadMovieBanner(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }

        binding.tvSwiperTitle.text = movie.title

        binding.tvSwiperTvShowLastEpisode.text = context.getString(R.string.movie_item_type)

        binding.tvSwiperQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperRating.apply {
            text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.ivSwiperRatingIcon.visibility = binding.tvSwiperRating.visibility

        binding.tvSwiperOverview.apply {
            setOnClickListener {
                maxLines = when (maxLines) {
                    2 -> Int.MAX_VALUE
                    else -> 2
                }
            }

            text = movie.overview
        }

        binding.btnSwiperWatchNow.apply {
            setOnClickListener {
                findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToMovie(
                        id = movie.id,
                    )
                )
            }
        }

        binding.pbSwiperProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
    }


    private fun displayMovieMobile(binding: ContentMovieMobileBinding) {
        binding.ivMoviePoster.run {
            loadMoviePoster(movie) {
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = when {
                movie.poster.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieTitle.text = movie.title

        binding.tvMovieRating.text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"

        binding.tvMovieQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieRuntime.apply {
            text = movie.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(
                        R.string.movie_runtime_hours_minutes,
                        hours,
                        minutes
                    )
                    else -> context.getString(R.string.movie_runtime_minutes, minutes)
                }
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieGenres.apply {
            text = movie.genres.joinToString(", ") { it.name }
            visibility = when {
                movie.genres.isEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieOverview.text = movie.overview

        binding.btnMovieWatchNow.apply {
            setOnClickListener {
                // Este botón ya navega al reproductor, no a otra página de detalles.
                // Generalmente non necesita el cambio de proveedor, pero lo añadimos por seguridad.
                checkProviderAndRun {
                    findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                    ))
                }
            }
        }

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnMovieTrailer.apply {
            val trailer = movie.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer, "MovieMobile")
            }
            visibility = if (trailer != null) View.VISIBLE else View.GONE
        }

        binding.btnMovieDownload.apply {
            isVisible = Provider.supportsDownloads(UserPreferences.currentProvider)
            setOnClickListener {
                showDownloadDialog(Video.Type.Movie(movie.id, movie.title, movie.released?.format("yyyy-MM-dd") ?: "", movie.poster ?: "", movie.imdbId))
            }
        }

        binding.btnMovieFavorite.apply {

            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                        dao.upsertFavorite(resolvedMovie, newValue)

                        withContext(Dispatchers.Main) {
                            movie.poster = resolvedMovie.poster
                            movie.banner = resolvedMovie.banner
                            movie.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, movie.isFavorite.drawable())
            )
        }
    }

    private fun displayMovieTv(binding: ContentMovieTvBinding) {
        binding.ivMoviePoster.run {
            loadMoviePoster(movie) {
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = when {
                movie.poster.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieTitle.text = movie.title

        binding.tvMovieRating.text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"

        binding.tvMovieQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieRuntime.apply {
            text = movie.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(
                        R.string.movie_runtime_hours_minutes,
                        hours,
                        minutes
                    )
                    else -> context.getString(R.string.movie_runtime_minutes, minutes)
                }
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieGenres.apply {
            text = movie.genres.joinToString(", ") { it.name }
            visibility = when {
                movie.genres.isEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieOverview.text = movie.overview

        binding.btnMovieWatchNow.apply {
            setOnClickListener {
                checkProviderAndRun {
                    findNavController().navigate(MovieTvFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                    ))
                }
            }
        }

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnMovieTrailer.apply {
            val trailer = movie.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer, "MovieTv")
            }
            visibility = if (trailer != null) View.VISIBLE else View.GONE
        }

        binding.btnMovieDownload.apply {
            isVisible = false
            setOnClickListener {
                showDownloadDialog(Video.Type.Movie(movie.id, movie.title, movie.released?.format("yyyy-MM-dd") ?: "", movie.poster ?: "", movie.imdbId))
            }
        }

        binding.btnMovieFavorite.apply {

            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                        dao.upsertFavorite(resolvedMovie, newValue)

                        withContext(Dispatchers.Main) {
                            movie.poster = resolvedMovie.poster
                            movie.banner = resolvedMovie.banner
                            movie.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, movie.isFavorite.drawable())
            )
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun showDownloadDialog(videoType: Video.Type) {
        Log.d("DownloadDebug", "showDownloadDialog called for ${movie.title}")
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

        Toast.makeText(context, "Loading servers...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val servers = withContext(Dispatchers.IO) {
                    provider.getServers(movie.id, videoType)
                }

                if (servers.isEmpty()) {
                    Toast.makeText(context, "No servers found for download", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (activity != null) {
                    val bs = com.nexastream.app.ui.DownloadQualityBottomSheet.newInstance(
                        servers = servers,
                        mediaId = movie.id,
                        mediaTitle = movie.title,
                        mediaPoster = movie.poster,
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
                                        id = movie.id,
                                        title = movie.title,
                                        poster = movie.poster,
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

    private fun displayCastMobile(binding: ContentMovieCastMobileBinding) {
        binding.rvMovieCast.apply {
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(20.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentMovieCastTvBinding) {
        binding.hgvMovieCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(80)
        }
    }

    private fun displayDirectorsMobile(binding: ContentMovieDirectorsMobileBinding) {
        binding.rvMovieDirectors.text = movie.directors.joinToString (separator =", ") { it.name }
    }
    private fun displayDirectorsTv(binding: ContentMovieDirectorsTvBinding) {
        binding.rvMovieDirectors.text = movie.directors.joinToString (separator =", ") { it.name }
    }

    private fun displayRecommendationsMobile(binding: ContentMovieRecommendationsMobileBinding) {
        binding.rvMovieRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
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

    private fun displayRecommendationsTv(binding: ContentMovieRecommendationsTvBinding) {
        binding.hgvMovieRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
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
