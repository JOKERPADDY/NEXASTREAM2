package com.nexastream.app.fragments.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.nexastream.app.R
import com.nexastream.app.activities.tools.BypassWebViewActivity
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.ContentExoControllerMobileBinding
import com.nexastream.app.databinding.FragmentPlayerMobileBinding
import com.nexastream.app.models.Download
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.models.LiveNowNext
import com.nexastream.app.models.LiveRecording
import com.nexastream.app.live.LiveDvrService
import com.nexastream.app.live.LiveTvRepository
import com.nexastream.app.providers.IptvOrgProvider
import com.nexastream.app.models.WatchItem
// import com.nexastream.app.providers.SerienStreamProvider
import com.nexastream.app.ui.PlayerMobileView
import com.nexastream.app.utils.MediaServer
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.WatchNextUtils
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.getFileName
import com.nexastream.app.utils.next
import com.nexastream.app.utils.plus
import com.nexastream.app.utils.setMediaServerId
import com.nexastream.app.utils.setMediaServers
import com.nexastream.app.utils.toSubtitleMimeType
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.core.net.toUri
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexastream.app.fragments.player.settings.PlayerSettingsView
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import androidx.navigation.NavOptions
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.utils.DnsResolver
import com.nexastream.app.utils.NetworkClient
import com.nexastream.app.utils.EpisodeManager
import com.nexastream.app.utils.PlayerGestureHelper
import com.nexastream.app.utils.UserDataCache.toEpisode
import com.nexastream.app.utils.UserDataCache.toMovie
import com.nexastream.app.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.internal.userAgent
import java.util.Locale
import com.nexastream.app.trakt.TraktManager
import com.nexastream.app.extractors.TokenManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.nexastream.app.cast.CastManager
import androidx.media3.cast.CastPlayer
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.CastSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@UnstableApi
@AndroidEntryPoint
class PlayerMobileFragment : Fragment() {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L
    }

    private var _binding: FragmentPlayerMobileBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerMobileBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PlayerViewModel(args.videoType, args.id, startImmediately = false) }
    private val offlineDataSourceFactory by lazy {
        EntryPointAccessors.fromApplication(
            requireContext().applicationContext,
            OfflineDataSourceEntryPoint::class.java,
        ).dataSourceFactory()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface OfflineDataSourceEntryPoint {
        fun dataSourceFactory(): DataSource.Factory
    }

    @Inject
    lateinit var castManager: CastManager

    private lateinit var localPlayer: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private var isCastSessionListenerRegistered = false
    private val player: Player
        get() = castPlayer?.takeIf { castManager.isCasting() } ?: localPlayer

    private lateinit var httpDataSource: HttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSession: MediaSession
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var gestureHelper: PlayerGestureHelper

    private var servers = listOf<Video.Server>()
    private var zoomToast: Toast? = null

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    private var liveChannelId: String? = null
    private var liveNowNext: LiveNowNext? = null
    private var activeLiveRecording: LiveRecording? = null
    private var playbackAttemptStartedAt = 0L
    private var reportedReadyServerId: String? = null
    private var isPlayingOfflineDownload = false
    private var isIgnoringPip = false
    private var waitingForBypass = false
    private var bypassDone = false
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeOverlayDismissed = false
    private var isNavigatingToEpisode = false

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            onCastSessionStarted()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            onCastSessionStarted()
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            onCastSessionEnded()
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private fun onCastSessionStarted() {
        val currentBinding = _binding ?: return
        val cp = castManager.castPlayer ?: return
        castPlayer = cp
        currentBinding.pvPlayer.player = cp
        currentBinding.settings.player = cp

        if (!::localPlayer.isInitialized) return
        if (currentVideo?.requiresCastHeaders() == true) {
            castPlayer = null
            currentBinding.pvPlayer.player = localPlayer
            currentBinding.settings.player = localPlayer
            localPlayer.play()
            Toast.makeText(context, "This IPTV stream requires local headers and cannot be cast", Toast.LENGTH_LONG).show()
            return
        }
        val mediaItem = localPlayer.currentMediaItem ?: return
        val position = localPlayer.currentPosition
        localPlayer.pause()

        try {
            cp.setMediaItem(mediaItem)
            cp.seekTo(position)
            cp.prepare()
            cp.play()
        } catch (error: RuntimeException) {
            Log.e("PlayerMobileFragment", "Unable to transfer playback to Cast", error)
            castPlayer = null
            currentBinding.pvPlayer.player = localPlayer
            currentBinding.settings.player = localPlayer
            localPlayer.play()
            Toast.makeText(context, R.string.player_cast_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun onCastSessionEnded() {
        val currentBinding = _binding ?: return
        val cp = castPlayer
        val position = cp?.currentPosition ?: 0L
        val mediaItem = cp?.currentMediaItem

        castPlayer = null
        if (!::localPlayer.isInitialized) return
        currentBinding.pvPlayer.player = localPlayer
        currentBinding.settings.player = localPlayer

        if (mediaItem != null) {
            localPlayer.setMediaItem(mediaItem)
            localPlayer.seekTo(position)
            localPlayer.prepare()
        }
        if (localPlayer.currentMediaItem != null) {
            localPlayer.play()
        }
    }

    private val bypassWebViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cookies =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()

            if (result.resultCode != android.app.Activity.RESULT_OK || cookies.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            val bypassUrl = servers.firstOrNull { isSerienStreamBypassUrl(it.id) }?.id
            if (bypassUrl.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            applyBypassCookies(bypassUrl, cookies)
            waitingForBypass = false
            bypassDone = true

            lifecycleScope.launch {
                delay(300)
                viewModel.reloadServersAfterBypass()
            }
        }

    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, android.content.ComponentName::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                Log.i("ExternalPlayer", "Mobile - App selezionata: ${clickedComponent?.packageName ?: "Sconosciuta"}")
            }
        }
    }

    private val pickLocalSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val fileName = uri.getFileName(requireContext()) ?: uri.toString()

        val currentPosition = player.currentPosition
        val currentSubtitleConfigurations =
            player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                MediaItem.SubtitleConfiguration.Builder(it.uri)
                    .setMimeType(it.mimeType)
                    .setLabel(it.label)
                    .setLanguage(it.language)
                    .setSelectionFlags(0)
                    .build()
            } ?: listOf()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                .setSubtitleConfigurations(
                    currentSubtitleConfigurations
                            + MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(fileName.toSubtitleMimeType())
                        .setLabel(fileName)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
                .setMediaMetadata(player.mediaMetadata)
                .build()
        )
        player.seekTo(currentPosition)
        player.play()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            
            val window = requireActivity().window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }
        isIgnoringPip = false
        if (::localPlayer.isInitialized) {
            binding.pvPlayer.useController = true
            // Resume playback after returning from bypass or any pause
            if (!player.isPlaying) {
                player.play()
            }
        }
        
        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN")
            ContextCompat.registerReceiver(
                requireContext(),
                chooserReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ignored: Exception) {}

        castManager.castContext?.sessionManager?.let { sessionManager ->
            if (!isCastSessionListenerRegistered) {
                sessionManager.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
                isCastSessionListenerRegistered = true
            }
        }
        if (::localPlayer.isInitialized && castPlayer == null && castManager.isCasting()) {
            onCastSessionStarted()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePlayer(false)
        initializeVideo()
        loadInitialVideo()
        setupLiveTvFeatures()
        gestureHelper = PlayerGestureHelper(
            requireContext(), 
            binding.pvPlayer, 
            binding.llBrightness, 
            binding.pbBrightness, 
            binding.tvBrightnessPercentage,
            binding.llVolume, 
            binding.pbVolume, 
            binding.tvVolumePercentage
        )

        // Stato Video
        viewLifecycleOwner.lifecycleScope.launch { 
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                if (isPlayingOfflineDownload) return@collect

                when (state) {
                    PlayerViewModel.State.LoadingServers -> {}
                    is PlayerViewModel.State.SuccessLoadingServers -> {
                        servers = state.servers
                        val sToServer = servers.firstOrNull {
                            isSerienStreamBypassUrl(it.id)
                        }

                        if (sToServer != null && !waitingForBypass && !bypassDone) {
                            val bypassUrl = buildSerienStreamBypassUrl()
                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(requireContext(), "Unable to open s.to bypass page.", Toast.LENGTH_SHORT).show()
                                return@collect
                            }

                            waitingForBypass = true
                            bypassWebViewLauncher.launch(
                                Intent(requireContext(), BypassWebViewActivity::class.java)
                                    .putExtra(BypassWebViewActivity.EXTRA_URL, bypassUrl)
                            )
                        } else {
                            val providerName = UserPreferences.currentProvider?.name ?: ""
                            val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                            val isAD = providerName.contains("AfterDark", ignoreCase = true)

                            if (servers.isEmpty()) {
                                val message = if (isTmdb || isAD) {
                                    val langCode = providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                    if (isTmdb) getString(R.string.player_not_available_lang_message, langDisplayName)
                                    else getString(R.string.player_retry_later_message)
                                } else {
                                    "No servers found for this content."
                                }
                                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                                findNavController().navigateUp()
                                return@collect
                            }

                            player.playlistMetadata = MediaMetadata.Builder()
                                .setTitle(state.toString())
                                .setMediaServers(state.servers.map {
                                    MediaServer(
                                        id = it.id,
                                        name = it.name,
                                    )
                                })
                                .build()
                            // Manually refresh server settings after metadata update
                            binding.settings.player = player
                            binding.settings.setOnServerSelectedListener { server ->
                                viewModel.getVideo(state.servers.find { server.id == it.id }!!)
                            }

                            // If we have a preselected server, use it. Otherwise use the first one.
                            val initialServer = if (!args.serverId.isNullOrBlank()) {
                                state.servers.find { it.id == args.serverId } ?: state.servers.first()
                            } else {
                                state.servers.first()
                            }
                            
                            viewModel.getVideo(initialServer)
                        }

                    }

                    is PlayerViewModel.State.FailedLoadingServers -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigateUp()
                    }

                    is PlayerViewModel.State.LoadingVideo -> {
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri("".toUri())
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setMediaServerId(state.server.id)
                                        .build()
                                )
                                .build()
                        )
                    }

                    is PlayerViewModel.State.SuccessLoadingVideo -> {
                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                        displayVideo(state.video, state.server)
                    }

                    is PlayerViewModel.State.FailedLoadingVideo -> {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            LiveTvRepository.recordPlaybackFailure(state.server.id, state.error)
                        }
                        val nextServer = servers.getOrNull(servers.indexOf(state.server) + 1)
                        if (nextServer != null) {
                            viewModel.getVideo(nextServer)
                        } else {
                            val providerName = UserPreferences.currentProvider?.name ?: ""
                            val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                            val isAD = providerName.contains("AfterDark", ignoreCase = true)

                            val message = if (isTmdb || isAD) {
                                val langCode = providerName.substringAfter("(").substringBefore(")")
                                val locale = Locale.forLanguageTag(langCode)
                                val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                                if (isTmdb) getString(R.string.player_not_available_lang_message, langDisplayName)
                                else getString(R.string.player_retry_later_message)
                            } else {
                                "All servers failed to load the video."
                            }
                            
                            Toast.makeText(
                                requireContext(),
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                            findNavController().navigateUp()
                        }
                    }
                }
            }
        }

        // Stato Sottotitoli
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.SubtitleState.Loading -> {}
                    is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                        binding.settings.openSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(fileName)
                                        .setLanguage(state.subtitle.languageName)
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.languageName ?: fileName).substringBefore(" ")
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.subFileName}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }

                    is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                        binding.settings.subDLSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(state.subtitle.releaseName ?: state.subtitle.name ?: fileName)
                                        .setLanguage(state.subtitle.lang ?: state.subtitle.language ?: "Unknown")
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.releaseName ?: state.subtitle.name ?: fileName).substringBefore(" ")
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.name}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                    if (_binding == null || !isAdded) return@collect
                    releasePlayer()
                    isSetupDone = false
                    val action = PlayerMobileFragmentDirections
                        .actionPlayerMobileFragmentSelf(
                            id = nextEpisode.id,
                            videoType = nextEpisode,
                            title = nextEpisode.tvShow.title,
                            subtitle = "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                        )

                    hideNextEpisodeOverlay()
                    runCatching {
                        findNavController().navigate(action)
                    }.onFailure { e ->
                        Log.e("PlayerMobileFragment", "Failed to navigate to next episode", e)
                        isNavigatingToEpisode = false
                    }
                }
            }
        }


    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        binding.pvPlayer.useController = !isInPictureInPictureMode
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    fun onUserLeaveHint() {
        if (!isIgnoringPip && ::localPlayer.isInitialized && player.isPlaying) {
            enterPIPMode()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::localPlayer.isInitialized) {
            localPlayer.pause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        nextEpisodePrefetchJob?.cancel()
        if (isCastSessionListenerRegistered) {
            castManager.castContext?.sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
            isCastSessionListenerRegistered = false
        }
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        WindowCompat.getInsetsController(
            window,
            window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        releasePlayer()
        try {
            requireContext().unregisterReceiver(chooserReceiver)
        } catch (ignored: Exception) {}
        _binding = null
        isSetupDone = false
    }

    fun onBackPressed(): Boolean = when {
        binding.pvPlayer.isManualZoomEnabled -> {
            binding.pvPlayer.exitManualZoomMode()
            true
        }
        binding.settings.isVisible -> {
            binding.settings.onBackPressed()
        }
        else -> false
    }


    private fun initializeVideo() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        when (val type = args.videoType) {
            is Video.Type.Episode -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                if (EpisodeManager.listIsEmpty(type)) {
                    EpisodeManager.clearEpisodes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        EpisodeManager.addEpisodesFromDb(type, database)
                        withContext(Dispatchers.Main) {
                            EpisodeManager.setCurrentEpisode(type)
                            updatePlayerHeader(type)
                            setupEpisodeNavigationButtons()
                            refreshEpisodeNavigation(type)
                        }
                    }
                } else {
                    EpisodeManager.setCurrentEpisode(type)
                    setupEpisodeNavigationButtons()
                    refreshEpisodeNavigation(type)
                }
            }
            is Video.Type.Movie -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                EpisodeManager.clearEpisodes()
                hideNextEpisodeOverlay()
            }
        }


        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }
        binding.settings.setOnSoftwareDecoderSelectedListener { useSoftware ->
            currentSoftwareDecoder = useSoftware
            displayVideo(
                currentVideo ?: return@setOnSoftwareDecoderSelectedListener,
                currentServer ?: return@setOnSoftwareDecoderSelectedListener
            )
        }
        binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
        binding.pvPlayer.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
            setStyle(UserPreferences.captionStyle)
            setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context))
        }
        setupEpisodeNavigationButtons()

        binding.settings.setOnSubtitleOffsetSelectedListener { offset ->
            // Note: setSubtitleOffsetUs is not available in Media3 1.5.1
            // (player as? ExoPlayer)?.setSubtitleOffsetUs(offset.value * 1000L)
        }

        binding.pvPlayer.controller.binding.btnExoBack.setOnClickListener {
            findNavController().navigateUp()
        }

        updatePlayerHeader()

        binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.player_external_player_error_video),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.pvPlayer.controller.binding.exoReplay.setOnClickListener {
            player.seekTo(0)
        }

        binding.pvPlayer.controller.binding.btnExoLock.setOnClickListener {
            binding.pvPlayer.controller.binding.gControlsLock.isGone = true
            binding.pvPlayer.controller.binding.btnExoUnlock.isVisible = true
        }

        binding.pvPlayer.controller.binding.btnExoUnlock.setOnClickListener {
            binding.pvPlayer.controller.binding.gControlsLock.isVisible = true
            binding.pvPlayer.controller.binding.btnExoUnlock.isGone = true
        }

        binding.pvPlayer.controller.binding.btnExoPictureInPicture.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.player_picture_in_picture_not_supported),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                enterPIPMode()
            }
        }

        binding.pvPlayer.controller.binding.btnExoAspectRatio.setOnClickListener {
            val newResize = UserPreferences.playerResize.next()
            zoomToast?.cancel()
            zoomToast = Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
            zoomToast?.show()

            UserPreferences.playerResize = newResize
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            updatePlayerScale()
        }

        binding.pvPlayer.controller.binding.exoSettings.setOnClickListener {
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            binding.settings.show()
        }

        binding.settings.setOnLocalSubtitlesClickedListener {
            isIgnoringPip = true
            pickLocalSubtitle.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        }

        binding.settings.setOnOpenSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubtitle(subtitle.openSubtitle)
        }

        binding.settings.setOnSubDLSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubDLSubtitle(subtitle.subDLSubtitle)
        }

        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }

        binding.pvPlayer.controller.binding.btnSkipIntro.setOnClickListener {
            player.seekTo(player.currentPosition + 85000)
            it.isGone = true
        }

        binding.btnNextEpisodeAction.setOnClickListener {
            hideNextEpisodeOverlay()
            playNextEpisodeAcrossSeasons()
        }
        binding.btnNextEpisodeDismiss.setOnClickListener {
            nextEpisodeOverlayDismissed = true
            hideNextEpisodeOverlay()
        }

        binding.settings.onManualZoomClicked = {
            binding.settings.hide()
            binding.pvPlayer.hideController()
            binding.pvPlayer.enterManualZoomMode()
        }

        CastButtonFactory.setUpMediaRouteButton(requireContext(), binding.pvPlayer.controller.binding.btnExoCast)
    }

    private fun loadInitialVideo() {
        lifecycleScope.launch {
            val download = withContext(Dispatchers.IO) {
                database.downloadDao().getById(args.id)
            }

            if (download?.isPlayableOffline == true) {
                playOfflineDownload(download)
            } else {
                viewModel.start()
            }
        }
    }

    private fun playOfflineDownload(download: Download) {
        isPlayingOfflineDownload = true

        val offlineServer = Video.Server(
            id = download.id,
            name = download.quality ?: getString(R.string.main_menu_downloads),
        )
        val offlineVideo = Video(
            source = download.url,
            headers = download.headers,
            type = download.mimeType,
        )
        servers = listOf(offlineServer)

        initializePlayer(currentExtraBuffering, currentSoftwareDecoder)
        player.playlistMetadata = MediaMetadata.Builder()
            .setTitle(download.title)
            .setMediaServers(
                servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                }
            )
            .build()
        binding.settings.player = player
        binding.settings.setOnServerSelectedListener {}
        displayVideo(offlineVideo, offlineServer)
    }

 private fun updatePlayerScale() {
        val videoSurfaceView = binding.pvPlayer.videoSurfaceView
        val playerResize = UserPreferences.playerResize 

        binding.pvPlayer.resizeMode = playerResize.resizeMode 

        when (playerResize) { 
            UserPreferences.PlayerResize.Stretch43 -> {
                val scale = 1.33f 
                videoSurfaceView?.scaleX = scale
                videoSurfaceView?.scaleY = 1f
            }
            UserPreferences.PlayerResize.StretchVertical -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1.25f
            }
            UserPreferences.PlayerResize.SuperZoom -> {
                videoSurfaceView?.scaleX = 1.5f
                videoSurfaceView?.scaleY = 1.5f
            }
            else -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1f
            }
        }
    }

    fun setupEpisodeNavigationButtons() {
        val btnPrevious = binding.pvPlayer.controller.binding.btnCustomPrev
        val btnNext = binding.pvPlayer.controller.binding.btnCustomNext

        fun handleNavigationButton(
            button: ImageView,
            hasEpisode: () -> Boolean,
            playEpisode: () -> Unit
        ) {
            if (!hasEpisode()) {
                button.isGone = true
                return
            }

            button.isGone = false
            button.setOnClickListener listener@{
                if (!hasEpisode() || isNavigatingToEpisode) return@listener

                val finished = if (::localPlayer.isInitialized) player.hasFinished() else false
                val reallyFinished = if (::localPlayer.isInitialized) player.hasReallyFinished() else false

                isNavigatingToEpisode = true

                lifecycleScope.launch(Dispatchers.IO) {
                    val videoType = args.videoType

                    val watchItem: WatchItem? = when (videoType) {
                        is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                        is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                    }

                    when (videoType) {
                        is Video.Type.Movie -> {
                            val provider = UserPreferences.currentProvider ?: return@launch
                            val movie = watchItem as? Movie
                            movie?.let { database.movieDao().update(it) }
                            movie?.let { UserDataCache.addMovieToContinueWatching(requireContext(), provider, it) }
                        }

                        is Video.Type.Episode -> {
                            val provider = UserPreferences.currentProvider ?: return@launch
                            val episode = watchItem as? Episode
                            episode?.let {
                                if (finished) {
                                    database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                    UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, it.id)
                                }
                                database.episodeDao().update(it)

                                if (!finished) {
                                    UserDataCache.addEpisodeToContinueWatching(requireContext(), provider, it)
                                }

                                it.tvShow?.let { tvShow ->
                                    database.tvShowDao().getById(tvShow.id)
                                }?.let { tvShow ->
                                    val episodeDao = database.episodeDao()
                                    val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                    database.tvShowDao().save(tvShow.copy().apply {
                                        merge(tvShow)
                                        isWatching = !reallyFinished || isStillWatching
                                    })
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        playEpisode()
                    }
                }
            }
        }

        handleNavigationButton(
            btnPrevious,
            EpisodeManager::hasPreviousEpisode,
            viewModel::playPreviousEpisode
        )
        handleNavigationButton(btnNext, EpisodeManager::hasNextEpisode, ::playNextEpisodeAcrossSeasons)
    }

    private fun refreshEpisodeNavigation(type: Video.Type.Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            EpisodeManager.ensureNextEpisodeAvailable(type, database)
            withContext(Dispatchers.Main) {
                setupEpisodeNavigationButtons()
            }
        }
    }

    private fun playNextEpisodeAcrossSeasons(autoplay: Boolean = false) {
        val type = args.videoType as? Video.Type.Episode ?: run {
            isNavigatingToEpisode = false
            return
        }

        lifecycleScope.launch {
            val hasNextEpisode = withContext(Dispatchers.IO) {
                EpisodeManager.ensureNextEpisodeAvailable(type, database)
            }

            setupEpisodeNavigationButtons()

            if (!hasNextEpisode) {
                isNavigatingToEpisode = false
                return@launch
            }
            if (autoplay && !UserPreferences.autoplay) {
                isNavigatingToEpisode = false
                return@launch
            }

            viewModel.playNextEpisode()
        }
    }

    private fun decodeBase64Uri(uri: String): String? {
        return try {
            val parts = uri.split(",")
            if (parts.size == 2 && parts[0].contains(";base64")) {
                val base64Data = parts[1]
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                String(decodedBytes, Charsets.UTF_8)
            } else {
                null
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun extractUrlFromPlaylist(playlist: String): String? {
        return try {
            val lines = playlist.lines().map { it.trim() }

            // If it's a master manifest, don't extract a single URL.
            // We want to keep all quality and audio track information.
            if (lines.any { it.contains("#EXT-X-STREAM-INF") || it.contains("#EXT-X-MEDIA") }) {
                return null
            }

            lines.firstOrNull { it.startsWith("http") }
                ?: lines.firstNotNullOfOrNull { line ->
                    val regex = """URI=["'](http[^"']+)["']""".toRegex()
                    regex.find(line)?.groupValues?.get(1)
                }
        } catch (ignored: Exception) {
            null
        }
    }


    private fun displayVideo(video: Video, server: Video.Server) {
        currentVideo = video
        currentServer = server
        playbackAttemptStartedAt = SystemClock.elapsedRealtime()
        reportedReadyServerId = null

        // Capture token if needed
        if (video.maintainToken) {
            val uri = Uri.parse(video.source)
            val query = uri.encodedQuery
            if (!query.isNullOrBlank()) {
                com.nexastream.app.extractors.TokenManager.latestQuery = query
                Log.e("TokenManager", "[PLAYER] Captured token from query: $query")
            } else {
                // Try to extract token from last path segment (common for IPTV links)
                val lastSegment = uri.pathSegments.lastOrNull()
                if (lastSegment != null && lastSegment.length > 10 && !lastSegment.contains(".")) {
                    val inferredQuery = "token=$lastSegment"
                    com.nexastream.app.extractors.TokenManager.latestQuery = inferredQuery
                    Log.e("TokenManager", "[PLAYER] Captured token from path segment: $inferredQuery")
                } else {
                    Log.e("TokenManager", "[PLAYER] No token found in URL: ${video.source}")
                }
            }
        }

        updatePlayerHeader()
        updateCastAvailability(video)

        if (server.name.contains("480p", ignoreCase = true) || server.id.contains("480p", ignoreCase = true)) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(854, 480)
                .build()
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .build()
        }

        if (UserPreferences.autoDownloadSubtitles && !isPlayingOfflineDownload) {
            viewModel.autoDownloadSubtitles(args.videoType)
        }

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled

        val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled
        val needsReinit =
            extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder
        if (needsReinit) {
            initializePlayer(extraBuffering, softwareDecoder)
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                })
                .build()
        }

        val currentPosition = player.currentPosition

        httpDataSource.setDefaultRequestProperties(
            mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
            ) + (video.headers ?: emptyMap())
        )

        // Handle data: URIs (base64-encoded playlists) by decoding them to temporary files
        val videoUri = if (video.source.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
            val playlistContent = decodeBase64Uri(video.source)
            val extractedUrl = if (playlistContent != null) extractUrlFromPlaylist(playlistContent) else null

            if (extractedUrl != null) {
                extractedUrl.toUri()
            } else {
                try {
                    val file = File(requireContext().cacheDir, "stream.m3u8")
                    FileOutputStream(file).use { it.write(playlistContent?.toByteArray() ?: ByteArray(0)) }
                    FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
                } catch (ignored: Exception) {
                    video.source.toUri()
                }
            }
        } else {
            video.source.toUri()
        }

        player.setMediaItem(
            MediaItem.Builder()
                .setUri(videoUri)
                .setMimeType(video.type)
                .setSubtitleConfigurations(video.subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(subtitle.file.toUri())
                        .setMimeType(subtitle.file.toSubtitleMimeType())
                        .setLabel(subtitle.label)
                        .setSelectionFlags(if (subtitle.default) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                })
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setMediaServerId(server.id)
                        .build()
                )
                .build()
        )

        binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
            isIgnoringPip = true
            
            val videoTitle = when (val type = args.videoType) {
                is Video.Type.Movie -> type.title
                is Video.Type.Episode -> "${type.tvShow.title} • S${type.season.number} E${type.number}"
            }
            
            var sourceUri: Uri
            val mimeType = "video/*"
            
            val initialSource = video.source

            if (initialSource.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
                val playlistContent = decodeBase64Uri(initialSource)
                val extractedUrl = if (playlistContent != null) extractUrlFromPlaylist(playlistContent) else null
                
                if (extractedUrl != null) {
                    sourceUri = extractedUrl.toUri()
                    Log.i("ExternalPlayer", "Link reale estratto: $sourceUri")
                } else {
                    try {
                        val file = File(requireContext().cacheDir, "stream.m3u8")
                        FileOutputStream(file).use { it.write(playlistContent?.toByteArray() ?: ByteArray(0)) }
                        sourceUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
                    } catch (ignored: Exception) {
                        sourceUri = initialSource.toUri()
                    }
                }
            } else {
                sourceUri = initialSource.toUri()
            }

            Log.i("ExternalPlayer", "Avvio intent con URI: $sourceUri e MIME: $mimeType")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(sourceUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                
                putExtra("title", videoTitle)
                putExtra("position", player.currentPosition.toInt())
                putExtra("return_result", true)
                
                putExtra("extra_headers", video.headers?.map { "${it.key}: ${it.value}" }?.toTypedArray())
                
                if (video.headers != null) {
                    val headersArray = video.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                    putExtra("headers", headersArray)
                }
            }

            try {
                val receiverIntent = Intent("ACTION_PLAYER_CHOSEN").apply {
                    setPackage(requireContext().packageName)
                }
                
                val pendingIntent = PendingIntent.getBroadcast(
                    requireContext(), 0, receiverIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    startActivity(
                        Intent.createChooser(
                            intent,
                            getString(R.string.player_external_player_title),
                            pendingIntent.intentSender
                        )
                    )
                } else {
                    startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title)))
                }
            } catch (e: Exception) {
                Log.e("ExternalPlayer", "Errore selettore app", e)
                startActivity(Intent.createChooser(intent, getString(R.string.player_external_player_title)))
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                updateLiveControls()
                if (playbackState == Player.STATE_READY) {
                    reportCurrentStreamHealthy()
                    stopBufferingWatchdog()
                }
                
                if (playbackState == Player.STATE_BUFFERING) {
                    val isLive = currentVideo?.source?.contains("ronaldo.tvfor.pro") == true || 
                                currentVideo?.source?.contains(".m3u8") == true ||
                                currentServer?.id?.contains("ronaldo.tvfor.pro") == true
                    if (isLive) startBufferingWatchdog()
                }

                // Auto-restart for Live streams that reach EOS unexpectedly
                if (playbackState == Player.STATE_ENDED) {
                    stopBufferingWatchdog()
                    val isLive = currentVideo?.source?.contains("ronaldo.tvfor.pro") == true || 
                                currentVideo?.source?.contains(".m3u8") == true ||
                                currentServer?.id?.contains("ronaldo.tvfor.pro") == true
                    
                    if (isLive) {
                        Log.e("PlayerMobileFragment", "Live stream ended unexpectedly, retrying in 2s...")
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(2000)
                            player.seekToDefaultPosition()
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                super.onTimelineChanged(timeline, reason)
                updateLiveControls()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                binding.pvPlayer.keepScreenOn = isPlaying || UserPreferences.keepScreenOnWhenPaused

                if (isPlaying) {
                    startProgressHandler()
                } else {
                    stopProgressHandler()
                }

                val hasUri = player.currentMediaItem?.localConfiguration?.uri
                    ?.toString()?.isNotEmpty()
                    ?: false

                if (!isPlaying && hasUri) {
                    val videoType = args.videoType
                    val hasStarted = player.hasStarted()
                    val hasFinished = player.hasFinished()
                    val hasReallyFinished = player.hasReallyFinished()
                    val playbackPosition = player.currentPosition
                    val playbackDuration = player.duration

                    lifecycleScope.launch(Dispatchers.IO) {
                        persistPlaybackState(
                            videoType = videoType,
                            hasStarted = hasStarted,
                            hasFinished = hasFinished,
                            hasReallyFinished = hasReallyFinished,
                            playbackPosition = playbackPosition,
                            playbackDuration = playbackDuration,
                        )
                    }

                    if (hasReallyFinished) {
                        if (UserPreferences.autoplay) {
                            playNextEpisodeAcrossSeasons(autoplay = true)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                Log.e("PlayerMobileFragment", "onPlayerError: ", error)

                if (
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                    player.isCurrentMediaItemLive
                ) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }

                currentServer?.let { failedServer ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        LiveTvRepository.recordPlaybackFailure(failedServer.id, error)
                    }
                }
                
                val nextServer = servers.getOrNull(servers.indexOf(currentServer) + 1)
                if (nextServer != null) {
                    Log.i("PlayerMobileFragment", "Playback failed, trying next server: ${nextServer.name}")
                    Toast.makeText(context, "Playback failed, trying: ${nextServer.name}", Toast.LENGTH_SHORT).show()
                    viewModel.getVideo(nextServer)
                } else {
                    Log.e("PlayerMobileFragment", "All servers failed.")
                    Toast.makeText(context, "All servers failed to play this content.", Toast.LENGTH_LONG).show()
                }
            }
        })

        fun startPlayback(positionMs: Long) {
            player.seekTo(positionMs)
            player.prepare()
            // Note: setSubtitleOffsetUs is not available in Media3 1.5.1
            // (player as? ExoPlayer)?.setSubtitleOffsetUs(UserPreferences.subtitleOffset * 1000L)
            player.play()
        }

        val isLive = server.id.startsWith("http") && (video.source.contains(".m3u8") || video.source.contains("ronaldo.tvfor.pro"))
        
        if (currentPosition == 0L && !isLive) {
            lifecycleScope.launch {
                val resumePosition = withContext(Dispatchers.IO) {
                    resolveResumePosition(args.videoType, UserPreferences.currentProvider)
                }
                startPlayback(resumePosition)
            }
        } else {
            // For live streams or manual re-init, always start from current position or live edge
            startPlayback(if (isLive) C.TIME_UNSET else currentPosition)
        }
    }

    private suspend fun persistPlaybackState(
        videoType: Video.Type,
        hasStarted: Boolean,
        hasFinished: Boolean,
        hasReallyFinished: Boolean,
        playbackPosition: Long,
        playbackDuration: Long,
    ) {
        val watchItem: WatchItem? = when (videoType) {
            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
        }

        when {
            hasStarted && !hasFinished -> {
                watchItem?.isWatched = false
                watchItem?.watchedDate = null
                watchItem?.watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = playbackPosition,
                    durationMillis = playbackDuration,
                )
            }

            hasFinished -> {
                watchItem?.isWatched = true
                watchItem?.watchedDate = Calendar.getInstance()
                watchItem?.watchHistory = null

                val tmdbId = when (videoType) {
                    is Video.Type.Movie -> videoType.id.toIntOrNull()
                    is Video.Type.Episode -> videoType.tvShow.id.toIntOrNull()
                }
                tmdbId?.let { id ->
                    if (videoType is Video.Type.Movie) TraktManager.syncMovieToHistory(id)
                    else TraktManager.syncEpisodeToHistory(id)
                }
            }
        }

        when (videoType) {
            is Video.Type.Movie -> {
                val provider = UserPreferences.currentProvider ?: return
                val movie = watchItem as? Movie
                movie?.let {
                    database.movieDao().update(it)
                    UserDataCache.syncMovieToCache(requireContext(), provider, it)
                }
            }

            is Video.Type.Episode -> {
                val provider = UserPreferences.currentProvider ?: return
                val episode = watchItem as? Episode
                episode?.let {
                    if (hasFinished) {
                        database.episodeDao().resetProgressionFromEpisode(videoType.id)
                        UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, it.id)
                        queueNextEpisodeForContinueWatching(provider)
                    }
                    database.episodeDao().update(it)
                    if (!hasFinished) {
                        UserDataCache.syncEpisodeToCache(requireContext(), provider, it)
                    }

                    it.tvShow?.let { tvShow ->
                        database.tvShowDao().getById(tvShow.id)
                    }?.let { tvShow ->
                        val episodeDao = database.episodeDao()
                        val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                        database.tvShowDao().save(tvShow.copy().apply {
                            merge(tvShow)
                            isWatching = !hasReallyFinished || isStillWatching
                        })
                    }
                }
            }
        }
    }

    private suspend fun resolveResumePosition(videoType: Video.Type, provider: com.nexastream.app.providers.Provider?): Long = withContext(Dispatchers.IO) {
        val watchItem: WatchItem? = when (videoType) {
            is Video.Type.Movie -> {
                val movie = provider?.let {
                    UserDataCache.read(requireContext(), it)?.continueWatchingMovies
                        ?.find { cachedMovie -> cachedMovie.id == videoType.id }?.toMovie()
                }
                movie ?: database.movieDao().getById(videoType.id)
            }
            is Video.Type.Episode -> {
                val episode = provider?.let {
                    UserDataCache.read(requireContext(), it)?.continueWatchingEpisodes
                        ?.find { cachedEpisode -> cachedEpisode.id == videoType.id }?.toEpisode()
                }
                episode ?: database.episodeDao().getById(videoType.id)
            }
        }

        watchItem?.watchHistory
            ?.let { it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds }
            ?.coerceAtLeast(0L)
            ?: 0L
    }

    private fun enterPIPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.pvPlayer.useController = false
            requireActivity().enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .build()
            )
        }
    }


    private fun Player.hasStarted(): Boolean {
        return (this.currentPosition > (this.duration * 0.005) || this.currentPosition > 20.seconds.inWholeMilliseconds)
    }

    private fun Player.hasFinished(): Boolean {
        return (this.currentPosition > (this.duration * 0.90))
    }

    private fun Player.hasReallyFinished(): Boolean {
        return this.duration > 0 &&
                this.currentPosition >= (this.duration - UserPreferences.autoplayBuffer * 1000)
    }

    private fun updateCastAvailability(video: Video) {
        val button = binding.pvPlayer.controller.binding.btnExoCast
        val available = !video.requiresCastHeaders()
        button.isEnabled = available
        button.alpha = if (available) 1f else 0.45f
        button.contentDescription = if (available) "Cast" else "Casting unavailable for header-protected IPTV stream"
    }

    private fun Video.requiresCastHeaders(): Boolean = headers?.keys?.any {
        it.equals("Referer", ignoreCase = true) || it.equals("Origin", ignoreCase = true)
    } == true

    private fun setupLiveTvFeatures() {
        liveChannelId = IptvOrgProvider.channelIdFromEncodedId(args.id)
        val controls = binding.pvPlayer.controller.binding
        val channelId = liveChannelId
        if (channelId == null) {
            controls.btnExoLive.isGone = true
            controls.btnExoRecord.isGone = true
            return
        }

        controls.btnExoLive.setOnClickListener {
            if (player.isCurrentMediaItemDynamic) {
                player.seekToDefaultPosition()
                player.play()
                updateLiveControls()
            }
        }
        controls.btnExoRecord.setOnClickListener {
            if (activeLiveRecording != null) {
                LiveDvrService.stop(requireContext())
            } else {
                val video = currentVideo
                if (video == null || !LiveDvrService.start(requireContext(), channelId, resolvePlayerTitle(), video)) {
                    Toast.makeText(
                        requireContext(),
                        "DVR is available for dynamic HLS live streams.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            LiveTvRepository.observeNowNext(channelId)
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect {
                    liveNowNext = it
                    updatePlayerHeader()
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            LiveTvRepository.observeActiveRecording(channelId)
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect {
                    activeLiveRecording = it
                    updateLiveControls()
                }
        }
    }

    private fun updateLiveControls() {
        if (liveChannelId == null || !::localPlayer.isInitialized) return
        val controls = binding.pvPlayer.controller.binding
        val isLive = player.isCurrentMediaItemLive
        val hasSeekableWindow = isLive && player.isCurrentMediaItemDynamic
        val liveOffset = player.currentLiveOffset
        controls.btnExoLive.isVisible = isLive
        controls.btnExoLive.isEnabled = hasSeekableWindow
        controls.btnExoLive.alpha = if (hasSeekableWindow) 1f else 0.55f
        controls.btnExoLive.text = when {
            !hasSeekableWindow -> "LIVE"
            liveOffset == C.TIME_UNSET || liveOffset < 5_000L -> "LIVE"
            else -> "-${(liveOffset / 60_000L).coerceAtLeast(1L)}m · ${getString(R.string.player_go_live)}"
        }
        val video = currentVideo
        val canRecord = hasSeekableWindow && !castManager.isCasting() && video != null &&
            (video.source.contains(".m3u8", ignoreCase = true) || video.type == MimeTypes.APPLICATION_M3U8)
        controls.btnExoRecord.isVisible = canRecord || activeLiveRecording != null
        controls.btnExoRecord.text = if (activeLiveRecording != null) {
            getString(R.string.player_stop_recording)
        } else {
            getString(R.string.player_start_recording)
        }
    }

    private fun reportCurrentStreamHealthy() {
        val server = currentServer ?: return
        if (reportedReadyServerId == server.id) return
        reportedReadyServerId = server.id
        val latency = (SystemClock.elapsedRealtime() - playbackAttemptStartedAt).takeIf { it >= 0L }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            LiveTvRepository.recordPlaybackSuccess(server.id, latency)
        }
    }

    private fun currentVideoTypeForUi(): Video.Type = when (val type = args.videoType) {
        is Video.Type.Episode -> EpisodeManager.getCurrentEpisode()
            ?.takeIf { currentEpisode -> currentEpisode.id == type.id }
            ?: type
        is Video.Type.Movie -> type
    }

    private fun resolvePlayerTitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title.ifBlank { args.title }
        }
    }

    private fun resolvePlayerSubtitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        if (liveChannelId != null) {
            val nowTitle = liveNowNext?.now?.title
            val nextTitle = liveNowNext?.next?.title
            return when {
                nowTitle != null && nextTitle != null -> "Now: $nowTitle  ·  Next: $nextTitle"
                nowTitle != null -> "Now: $nowTitle"
                nextTitle != null -> "Next: $nextTitle"
                else -> args.subtitle
            }
        }
        return when (videoType) {
            is Video.Type.Movie -> args.subtitle
            is Video.Type.Episode -> {
                val episodeTitle = videoType.title?.takeUnless { it.isBlank() } ?: args.subtitle
                "S${videoType.season.number} E${videoType.number}  •  $episodeTitle"
            }
        }
    }

    private fun updatePlayerHeader(videoType: Video.Type = currentVideoTypeForUi()) {
        binding.pvPlayer.controller.binding.tvExoTitle.text = resolvePlayerTitle(videoType)
        binding.pvPlayer.controller.binding.tvExoSubtitle.text = resolvePlayerSubtitle(videoType)
    }

    private suspend fun queueNextEpisodeForContinueWatching(provider: com.nexastream.app.providers.Provider) {
        val nextEpisode = EpisodeManager.peekNextEpisode() ?: return
        val episodeDao = database.episodeDao()

        val persistedNextEpisode = episodeDao.getById(nextEpisode.id)?.apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        } ?: Episode(
            id = nextEpisode.id,
            number = nextEpisode.number,
            title = nextEpisode.title,
            poster = nextEpisode.poster,
            overview = nextEpisode.overview,
            tvShow = database.tvShowDao().getById(nextEpisode.tvShow.id) ?: TvShow(
                id = nextEpisode.tvShow.id,
                title = nextEpisode.tvShow.title,
                poster = nextEpisode.tvShow.poster,
                banner = nextEpisode.tvShow.banner,
            ),
            season = Season(
                number = nextEpisode.season.number,
                title = nextEpisode.season.title,
            ),
        ).apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        }

        episodeDao.save(persistedNextEpisode)
        UserDataCache.syncEpisodeToCache(requireContext(), provider, persistedNextEpisode)
        WatchNextUtils.updateWatchNext(requireContext(), persistedNextEpisode)
    }
    private var bufferingWatchdogJob: Job? = null
    private fun startBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(20_000)
            if (player.playbackState == Player.STATE_BUFFERING) {
                val nextServer = servers.getOrNull(servers.indexOf(currentServer) + 1)
                if (nextServer != null) {
                    Log.i("PlayerMobileFragment", "Stuck in buffering, trying next server: ${nextServer.name}")
                    Toast.makeText(context, "Connection slow, trying mirror...", Toast.LENGTH_SHORT).show()
                    viewModel.getVideo(nextServer)
                }
            }
        }
    }

    private fun stopBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
    }

    private fun startProgressHandler() {
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        progressRunnable = Runnable {
            if (player.isPlaying) {
                val show = player.currentPosition in 3000..120000
                showSkipIntroButton(show)
                updateNextEpisodeOverlay()
            }
            updateLiveControls()
            progressHandler.postDelayed(progressRunnable, 1000)
        }
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressHandler() {
        if (::progressHandler.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
    }

    private fun updateNextEpisodeOverlay() {
        val currentEpisode = currentVideoTypeForUi() as? Video.Type.Episode ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val duration = player.duration.takeIf { it > 0 } ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)

        if (nextEpisodeOverlayDismissed) {
            hideNextEpisodeOverlay()
            return
        }

        if (remainingMs <= NEXT_EPISODE_PREFETCH_THRESHOLD_MS) {
            ensureNextEpisodePrepared(currentEpisode)
        }

        val nextEpisode = EpisodeManager.peekNextEpisode()
        val overlayThresholdMs = maxOf(
            NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS,
            UserPreferences.autoplayBuffer * 1000L
        )
        if (nextEpisode == null || remainingMs == 0L || remainingMs > overlayThresholdMs) {
            hideNextEpisodeOverlay()
            return
        }

        showNextEpisodeOverlay(nextEpisode, remainingMs)
    }

    private fun ensureNextEpisodePrepared(currentEpisode: Video.Type.Episode) {
        if (EpisodeManager.peekNextEpisode() != null) return
        if (nextEpisodePrefetchTargetId == currentEpisode.id && nextEpisodePrefetchJob?.isActive == true) {
            return
        }

        nextEpisodePrefetchTargetId = currentEpisode.id
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val loaded = EpisodeManager.ensureNextEpisodeAvailable(currentEpisode, database)
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                setupEpisodeNavigationButtons()
                if (loaded && player.isPlaying) {
                    updateNextEpisodeOverlay()
                }
            }
        }
    }

    private fun showNextEpisodeOverlay(nextEpisode: Video.Type.Episode, remainingMs: Long) {
        binding.tvNextEpisodeMeta.text = getString(
            R.string.tv_show_item_season_number_episode_number,
            nextEpisode.season.number,
            nextEpisode.number
        )
        binding.tvNextEpisodeTitle.text = nextEpisode.title
            ?: getString(R.string.episode_number, nextEpisode.number)
        binding.tvNextEpisodeCountdown.text = if (UserPreferences.autoplay) {
            getString(
                R.string.player_next_episode_autoplay_in,
                ((remainingMs + 999L) / 1000L).toInt()
            )
        } else {
            getString(R.string.player_next_episode_ready)
        }

        Glide.with(this)
            .load(nextEpisode.poster ?: nextEpisode.tvShow.poster)
            .error(R.drawable.glide_fallback_cover)
            .fallback(R.drawable.glide_fallback_cover)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivNextEpisodePoster)

        if (binding.layoutNextEpisodeOverlay.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
            binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
            binding.layoutNextEpisodeOverlay.isVisible = true
        }
    }

    private fun hideNextEpisodeOverlay() {
        if (_binding == null) return
        if (binding.layoutNextEpisodeOverlay.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
            binding.layoutNextEpisodeOverlay.startAnimation(fadeOut)
            binding.layoutNextEpisodeOverlay.isGone = true
        }
    }

    private fun showSkipIntroButton(show: Boolean) {
        val btnSkipIntro = binding.pvPlayer.controller.binding.btnSkipIntro
        if (show && btnSkipIntro.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
            btnSkipIntro.startAnimation(fadeIn)
            btnSkipIntro.isVisible = true
        } else if (!show && btnSkipIntro.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
            btnSkipIntro.startAnimation(fadeOut)
            btnSkipIntro.isGone = true
        }
    }



    override fun onPause() {
        super.onPause()
        stopProgressHandler()
        hideNextEpisodeOverlay()
    }

    private var currentExtraBuffering = false
    private var currentSoftwareDecoder = false

    private fun buildPlayer(extraBuffering: Boolean, isLive: Boolean): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isLive) 30_000 else DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                if (extraBuffering) 300_000 else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                if (isLive) 10_000 else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                if (isLive) 15_000 else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val baseBuilder = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && !currentSoftwareDecoder) {
            ExoPlayer.Builder(requireContext())
        } else {
            val renderersFactory = DefaultRenderersFactory(requireContext()).apply {
                setEnableDecoderFallback(true)
                if (currentSoftwareDecoder) {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                }
            }
            ExoPlayer.Builder(requireContext(), renderersFactory)
        }

        return baseBuilder
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }

    private fun initializePlayer(extraBuffering: Boolean, softwareDecoder: Boolean = currentSoftwareDecoder) {
        releasePlayer()
        currentExtraBuffering = extraBuffering
        currentSoftwareDecoder = softwareDecoder

        var tokenLogged = false
        val baseClient = if (currentVideo?.maintainToken == true) NetworkClient.minimal else NetworkClient.default
        val okHttpClient = baseClient.newBuilder()
            .addInterceptor { chain ->
                var request = chain.request()
                val video = currentVideo

                // Inject specific video headers if provided
                if (video != null && !video.headers.isNullOrEmpty()) {
                    val rb = request.newBuilder()
                    video.headers.forEach { (k, v) ->
                        if (request.header(k) == null) rb.header(k, v)
                    }
                    request = rb.build()
                }
                
                if (video?.maintainToken == true) {
                    val latestQuery = TokenManager.latestQuery
                    if (latestQuery != null) {
                        val origHttpUrl = request.url
                        
                        // Extract token key/value
                        val tokenKey = latestQuery.substringBefore("=")
                        val tokenValue = latestQuery.substringAfter("=")

                        // Check if the parameter is already present
                        val updatedHttpUrl = if (origHttpUrl.queryParameter(tokenKey) != null) {
                            origHttpUrl
                        } else {
                            origHttpUrl.newBuilder()
                                .addQueryParameter(tokenKey, tokenValue)
                                .build()
                        }
                        
                        val rb = request.newBuilder().url(updatedHttpUrl)
                        
                        // Ensure critical headers for IPTV stability
                        if (request.header("Accept-Encoding") == null) rb.header("Accept-Encoding", "identity")
                        
                        request = rb.build()
                        if (!tokenLogged) {
                            android.util.Log.e("TokenManager", "[MOBILE-INTERCEPTOR] Token successfully injected into: ${request.url.host}")
                            tokenLogged = true
                        }
                    } else {
                        android.util.Log.e("TokenManager", "[MOBILE-INTERCEPTOR] maintainToken=true but latestQuery is null for host: ${request.url.host}")
                    }
                }
                
                chain.proceed(request)
            }
            .build()
        
        httpDataSource = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
        
        val useDefaultDataSource = currentVideo?.source?.contains("ronaldo.tvfor.pro") == true || 
                                currentServer?.id?.contains("ronaldo.tvfor.pro") == true
        
        dataSourceFactory = if (isPlayingOfflineDownload) {
            offlineDataSourceFactory
        } else if (useDefaultDataSource) {
            val defaultHttpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(NetworkClient.USER_AGENT)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
            
            val headers = mutableMapOf<String, String>()
            currentVideo?.headers?.forEach { (k, v) -> headers[k] = v }
            headers["Accept-Encoding"] = "identity"
            headers["Icy-MetaData"] = "1"
            headers["Connection"] = "keep-alive"
            
            TokenManager.latestQuery?.let { query ->
                val k = query.substringBefore("=")
                val v = query.substringAfter("=")
                // Note: DefaultHttpDataSource handles query params via the URI, 
                // but we might need to ensure the URI has the token.
            }
            
            defaultHttpDataSourceFactory.setDefaultRequestProperties(headers)
            DefaultDataSource.Factory(requireContext(), defaultHttpDataSourceFactory)
        } else {
            DefaultDataSource.Factory(requireContext(), httpDataSource)
        }

        val isLive = currentVideo?.source?.contains("ronaldo.tvfor.pro") == true || 
                    currentVideo?.source?.contains(".m3u8") == true ||
                    currentServer?.id?.contains("ronaldo.tvfor.pro") == true

        localPlayer = buildPlayer(extraBuffering, isLive).also { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )

                val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                if (lang == "es") {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguage("spa")
                        .build()
                } else {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguages("en", "eng")
                        .build()
                }

                mediaSession = MediaSession.Builder(requireContext(), player)
                    .build()
            }

        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView
        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
    }

    private fun releasePlayer() {
        stopProgressHandler()
        binding.pvPlayer.player = null
        binding.settings.player = null
        binding.settings.subtitleView = null
        if (::localPlayer.isInitialized) {
            localPlayer.release()
        }
        if (::mediaSession.isInitialized) {
            mediaSession.release()
        }
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return runCatching {
            Uri.parse(url).host.equals("serienstream.to", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun buildSerienStreamBypassUrl(): String? {
        // val provider = UserPreferences.currentProvider ?: return null
        // if (provider != SerienStreamProvider) return null
        return null

        // val episodeId = when (val type = args.videoType) {
        //     is Video.Type.Episode -> type.id
        //     is Video.Type.Movie -> return null
        // }

        // return "${SerienStreamProvider.baseUrl}serie/$episodeId"
    }

    private fun applyBypassCookies(url: String, cookieHeader: String) {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val targets = linkedSetOf<String>().apply {
            add(url)
            if (host.isNotBlank()) {
                add("https://$host/")
                add("http://$host/")
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieHeader.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { cookie ->
                targets.forEach { target ->
                    cookieManager.setCookie(target, cookie)
                }
            }
        cookieManager.flush()
    }
}
