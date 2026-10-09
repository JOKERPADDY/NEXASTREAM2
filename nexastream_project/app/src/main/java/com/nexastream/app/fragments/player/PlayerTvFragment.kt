package com.nexastream.app.fragments.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.R
import com.nexastream.app.fragments.player.settings.PlayerSettingsView
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.ContentExoControllerTvBinding
import com.nexastream.app.databinding.FragmentPlayerTvBinding
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Download
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
import com.nexastream.app.providers.IptvProvider
import com.nexastream.app.ui.PlayerTvView
import com.nexastream.app.utils.DnsResolver
import com.nexastream.app.utils.NetworkClient
import com.nexastream.app.utils.EpisodeManager
import com.nexastream.app.utils.MediaServer
import com.nexastream.app.utils.PlayerGestureHelper
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.SubtitleHelper
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.getFileName
import com.nexastream.app.utils.WatchNextUtils
import com.nexastream.app.utils.next
import com.nexastream.app.utils.plus
import com.nexastream.app.utils.setMediaServerId
import com.nexastream.app.utils.setMediaServers
import com.nexastream.app.utils.toSubtitleMimeType
import com.nexastream.app.utils.viewModelsFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.internal.userAgent
import android.util.Base64
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.nexastream.app.utils.BypassWebSocketServer
import com.nexastream.app.utils.BypassWebSocketEndpointHelper
import com.nexastream.app.utils.QrUtils
import com.nexastream.app.utils.UserDataCache.toEpisode
import com.nexastream.app.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import com.nexastream.app.trakt.TraktManager
import com.nexastream.app.extractors.TokenManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.nexastream.app.cast.CastManager
import androidx.media3.cast.CastPlayer
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.CastSession

@UnstableApi
@AndroidEntryPoint
class PlayerTvFragment : Fragment() {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L
        private const val NEXT_EPISODE_OVERLAY_ALPHA_UNFOCUSED = 0.72f
        private const val NEXT_EPISODE_OVERLAY_ALPHA_FOCUSED = 0.96f
    }

    private data class BypassSession(
        val token: String,
        val serverUrl: String,
        val bypassUrl: String,
    )

    private var _binding: FragmentPlayerTvBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerTvBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory {
        PlayerViewModel(args.videoType, args.id, startImmediately = false)
    }
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
        get() = castPlayer?.takeIf { castManager.isCasting() } ?: if (::localPlayer.isInitialized) localPlayer else error("Local player not initialized")

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
    private var waitingForBypass = false
    private var bypassDone = false
    private var activeBypassSession: BypassSession? = null
    private var qrDialog: androidx.appcompat.app.AlertDialog? = null
    private var wsServer: BypassWebSocketServer? = null
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
        if (_binding == null) return
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
            Log.e("PlayerTvFragment", "Unable to transfer playback to Cast", error)
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

    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(
                        Intent.EXTRA_CHOSEN_COMPONENT,
                        android.content.ComponentName::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                Log.i(
                    "ExternalPlayer",
                    "TV - App selezionata: ${clickedComponent?.packageName ?: "Sconosciuta"}"
                )
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

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = requireActivity().window
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }

        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN_TV")
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupChannelPicker()
        initializePlayer(false)
        initializeVideo()
        loadInitialVideo()
        setupLiveTvFeatures()
        binding.pvPlayer.onMediaPreviousClicked = ::handleMediaPrevious
        binding.pvPlayer.onMediaNextClicked = ::handleMediaNext
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
                            waitingForBypass = true

                            val bypassUrl = buildSerienStreamBypassUrl()
                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(
                                    requireContext(),
                                    "Unable to prepare TV bypass page.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }

                            val session = BypassSession(
                                token = UUID.randomUUID().toString(),
                                serverUrl = sToServer.id,
                                bypassUrl = bypassUrl,
                            )
                            activeBypassSession = session

                            val actualPort = startWebSocketServer()
                            if (actualPort == -1) {
                                clearBypassSession()
                                Toast.makeText(
                                    requireContext(),
                                    "Unable to start TV bypass. Please try again.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }

                            val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(actualPort)
                                ?: return@collect

                            val qrContent = "nexastream://resolve?ws=${Uri.encode(wsUrl)}&token=${Uri.encode(session.token)}"

                            wsServer?.registerSession(
                                session.token,
                                JSONObject()
                                    .put("url", session.bypassUrl)
                                    .toString()
                            )
                            requireActivity().runOnUiThread {
                                showQrDialog(qrContent)
                                Log.d("Bypass", "Advertised WS URL: $wsUrl")
                            }

                            return@collect
                        }



                        val providerName = UserPreferences.currentProvider?.name ?: ""
                        val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                        val isAD = providerName.contains("AfterDark", ignoreCase = true)

                        if (servers.isEmpty()) {
                            val message = if (isTmdb || isAD) {
                                val langCode = providerName.substringAfter("(").substringBefore(")")
                                val locale = Locale.forLanguageTag(langCode)
                                val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                if (isTmdb) getString(
                                    R.string.player_not_available_lang_message,
                                    langDisplayName
                                )
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
                                    val langCode =
                                        providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName =
                                        locale.getDisplayLanguage(Locale.getDefault())
                                            .replaceFirstChar {
                                                if (it.isLowerCase()) it.titlecase(
                                                    Locale.getDefault()
                                                ) else it.toString()
                                            }

                                    if (isTmdb) getString(
                                        R.string.player_not_available_lang_message,
                                        langDisplayName
                                    )
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
                viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
                    .collect { state ->
                        when (state) {
                            PlayerViewModel.SubtitleState.Loading -> {}
                            is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                                binding.settings.openSubtitles = state.subtitles
                            }

                            is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                            PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                            is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                                val fileName =
                                    state.uri.getFileName(requireContext()) ?: state.uri.toString()
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
                                                    + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                                .setMimeType(fileName.toSubtitleMimeType())
                                                .setLabel(fileName)
                                                .setLanguage(state.subtitle.languageName)
                                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                                .build()
                                        )
                                        .setMediaMetadata(player.mediaMetadata)
                                        .build()
                                )
                                UserPreferences.subtitleName =
                                    (state.subtitle.languageName ?: fileName).substringBefore(" ")
                                player.seekTo(currentPosition)
                                player.play()
                            }

                            is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                                Toast.makeText(
                                    requireContext(),
                                    "${state.subtitle.subFileName}: ${state.error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                                binding.settings.subDLSubtitles = state.subtitles
                            }

                            is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                            PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                            is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                                val fileName =
                                    state.uri.getFileName(requireContext()) ?: state.uri.toString()
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
                                                    + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                                .setMimeType(fileName.toSubtitleMimeType())
                                                .setLabel(
                                                    state.subtitle.releaseName
                                                        ?: state.subtitle.name ?: fileName
                                                )
                                                .setLanguage(
                                                    state.subtitle.lang ?: state.subtitle.language
                                                    ?: "Unknown"
                                                )
                                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                                .build()
                                        )
                                        .setMediaMetadata(player.mediaMetadata)
                                        .build()
                                )
                                UserPreferences.subtitleName =
                                    (state.subtitle.releaseName ?: state.subtitle.name
                                    ?: fileName).substringBefore(" ")
                                player.seekTo(currentPosition)
                                player.play()
                            }

                            is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                                Toast.makeText(
                                    requireContext(),
                                    "${state.subtitle.name}: ${state.error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
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

                        val args = Bundle().apply {
                            putString("id", nextEpisode.id)
                            putSerializable("videoType", nextEpisode)
                            putString("title", nextEpisode.tvShow.title)
                            putString(
                                "subtitle",
                                "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                            )
                        }

                        hideNextEpisodeOverlay()
                        runCatching {
                            findNavController().navigate(
                                R.id.action_playerTvFragment_self,
                                args
                            )
                        }.onFailure { e ->
                            Log.e("PlayerTvFragment", "Failed to navigate to next episode", e)
                            isNavigatingToEpisode = false
                        }
                    }
                }
            }


        }

    override fun onPause() {
        super.onPause()

        if (::localPlayer.isInitialized) {
            try {
                localPlayer.pause()
            } catch (e: Exception) {
                Log.w("Player", "pause() ignored, player already released")
            }
        }

        if (_binding != null) {
            stopProgressHandler()
            hideNextEpisodeOverlay()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        nextEpisodePrefetchJob?.cancel()
        clearBypassSession(dismissDialog = true)
        if (isCastSessionListenerRegistered) {
            castManager.castContext?.sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
            isCastSessionListenerRegistered = false
        }
        releasePlayer()
        try {
            requireContext().unregisterReceiver(chooserReceiver)
        } catch (ignored: Exception) {
        }
        _binding = null
        isSetupDone = false
    }

    fun onBackPressed(): Boolean = when {
        binding.channelPicker.isVisible -> {
            binding.channelPicker.hide()
            true
        }

        (binding.pvPlayer as? PlayerTvView)?.isManualZoomEnabled == true -> {
            (binding.pvPlayer as? PlayerTvView)?.exitManualZoomMode()
            true
        }

        binding.settings.isVisible -> {
            binding.settings.onBackPressed()
        }

        binding.pvPlayer.controller.isVisible -> {
            binding.pvPlayer.hideController()
            true
        }

        else -> false
    }

    private fun handleMediaPrevious(): Boolean {
        return when (args.videoType) {
            is Video.Type.Episode -> {
                if (!EpisodeManager.hasPreviousEpisode()) return false
                viewModel.playPreviousEpisode()
                true
            }
            is Video.Type.Movie -> false
        }
    }

    private fun handleMediaNext(): Boolean {
        return when (args.videoType) {
            is Video.Type.Episode -> {
                playNextEpisodeAcrossSeasons()
                true
            }
            is Video.Type.Movie -> false
        }
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


    private fun updatePlayerScale() {
        val videoSurfaceView = binding.pvPlayer.videoSurfaceView
        val playerResize = UserPreferences.playerResize

        binding.pvPlayer.resizeMode = playerResize.resizeMode

        videoSurfaceView?.apply {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            pivotX = width / 2f
            pivotY = height / 2f

            (layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                if (
                    params.width != FrameLayout.LayoutParams.MATCH_PARENT ||
                    params.height != FrameLayout.LayoutParams.MATCH_PARENT ||
                    params.gravity != Gravity.CENTER
                ) {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                }
            }

            requestLayout()
        }
        binding.pvPlayer.requestLayout()
    }

    private fun reloadCurrentVideoForQualityChange() {
        val video = currentVideo ?: return
        val server = currentServer ?: return
        val resumePosition = player.currentPosition
        val shouldPlay = player.isPlaying || player.playWhenReady

        initializePlayer(currentExtraBuffering, currentSoftwareDecoder)
        player.playlistMetadata = MediaMetadata.Builder()
            .setTitle(resolvePlayerTitle())
            .setMediaServers(servers.map {
                MediaServer(
                    id = it.id,
                    name = it.name,
                )
            })
            .build()

        displayVideo(
            video = video,
            server = server,
            startPositionMs = resumePosition,
            shouldPlay = shouldPlay,
        )
    }

    private fun initializeVideo() {
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
        setupEpisodeNavigationButtons()
        binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
        binding.pvPlayer.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
            setStyle(UserPreferences.captionStyle)
            setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context))
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

        binding.pvPlayer.controller.binding.exoProgress.setKeyTimeIncrement(10_000)

        binding.pvPlayer.controller.binding.btnExoAspectRatio.setOnClickListener {
            val newResize = UserPreferences.playerResize.next()
            zoomToast?.cancel()
            zoomToast =
                Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
            zoomToast?.show()

            UserPreferences.playerResize = newResize
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            updatePlayerScale()
        }

        binding.pvPlayer.controller.binding.exoSettings.setOnClickListener {
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            binding.settings.show()
        }

        binding.pvPlayer.controller.binding.btnSkipIntro.setOnClickListener {
            player.seekTo(player.currentPosition + 85000)
            it.visibility = View.GONE
        }

        binding.btnNextEpisodeAction.setOnClickListener {
            hideNextEpisodeOverlay()
            playNextEpisodeAcrossSeasons()
        }
        binding.btnNextEpisodeDismiss.setOnClickListener {
            nextEpisodeOverlayDismissed = true
            hideNextEpisodeOverlay()
        }
        binding.btnNextEpisodeAction.setOnFocusChangeListener { _, hasFocus ->
            updateNextEpisodeOverlayAlpha(hasFocus || binding.btnNextEpisodeDismiss.hasFocus())
        }
        binding.btnNextEpisodeDismiss.setOnFocusChangeListener { _, hasFocus ->
            updateNextEpisodeOverlayAlpha(hasFocus || binding.btnNextEpisodeAction.hasFocus())
        }

        binding.settings.setOnLocalSubtitlesClickedListener {
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
        binding.settings.setOnSubtitleOffsetSelectedListener { offset ->
            // Note: setSubtitleOffsetUs is not available in Media3 1.5.1
            // localPlayer.setSubtitleOffsetUs(offset.value * 1000L)
        }
        binding.settings.setOnSecondarySubtitleSelectedListener { 
            updateSecondarySubtitles()
        }
        binding.settings.setOnBilingualSelectedListener {
            updateSecondarySubtitles()
        }
        binding.settings.setOnQualitySelectedListener {
            reloadCurrentVideoForQualityChange()
        }
        binding.settings.onManualZoomClicked = {
            binding.settings.hide()
            binding.pvPlayer.hideController()
            (binding.pvPlayer as? PlayerTvView)?.enterManualZoomMode()
            binding.pvPlayer.requestFocus()
        }

        binding.pvPlayer.setControllerVisibilityListener(PlayerTvView.ControllerVisibilityListener { visibility ->
            val binding = _binding ?: return@ControllerVisibilityListener
            binding.pvPlayer.findViewById<View>(R.id.layout_sticky_header)?.visibility = visibility
            if (visibility == View.VISIBLE) {
                binding.pvPlayer.controller.binding.exoPlayPause.requestFocus()
                updateStickyHeader(binding)
                updateQuickControls(binding)
            }
        })

        binding.pvPlayer.controller.binding.tvQuickQuality.setOnClickListener {
            binding.settings.show()
            // We use a small delay to ensure the settings view is initialized before switching
            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                (binding.settings as? com.nexastream.app.fragments.player.settings.PlayerSettingsTvView)?.displaySettings(com.nexastream.app.fragments.player.settings.PlayerSettingsView.Setting.QUALITY)
            }
        }

        binding.pvPlayer.controller.binding.tvQuickSpeed.setOnClickListener {
            binding.settings.show()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                (binding.settings as? com.nexastream.app.fragments.player.settings.PlayerSettingsTvView)?.displaySettings(com.nexastream.app.fragments.player.settings.PlayerSettingsView.Setting.SPEED)
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
                button.visibility = View.GONE
                return
            }

            button.visibility = View.VISIBLE
            button.setOnClickListener {
                if (!hasEpisode() || isNavigatingToEpisode) return@setOnClickListener

                val currentPos = if (::localPlayer.isInitialized) player.currentPosition else 0L
                val currentDur = if (::localPlayer.isInitialized) player.duration else 0L
                val finished = if (::localPlayer.isInitialized) player.hasFinished() else false

                isNavigatingToEpisode = true

                lifecycleScope.launch(Dispatchers.IO) {
                    val videoType = args.videoType
                    val watchItem: WatchItem? = when (videoType) {
                        is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                        is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                    }

                    watchItem?.apply {
                        isWatched = false
                        watchedDate = null
                        watchHistory = WatchItem.WatchHistory(
                            lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                            lastPlaybackPositionMillis = currentPos,
                            durationMillis = currentDur
                        )
                    }

                    when (videoType) {
                        is Video.Type.Movie -> {
                            val provider = UserPreferences.currentProvider ?: return@launch
                            (watchItem as? Movie)?.let { database.movieDao().update(it) }
                            (watchItem as? Movie)?.let { UserDataCache.addMovieToContinueWatching(requireContext(), provider, it) }
                        }

                        is Video.Type.Episode -> {
                            val provider = UserPreferences.currentProvider ?: return@launch
                            (watchItem as? Episode)?.let { episode ->
                                if (finished) {
                                    episode.isWatched = true
                                    episode.watchedDate = java.util.Calendar.getInstance()
                                    episode.watchHistory = null
                                    database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                    UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, episode.id)
                                }

                                database.episodeDao().update(episode)
                                if (!finished) {
                                    (watchItem as? Episode)?.let { UserDataCache.addEpisodeToContinueWatching(requireContext(), provider, it) }
                                }

                                episode.tvShow?.let { tvShow ->
                                    database.tvShowDao().getById(tvShow.id)
                                }?.let { tvShow ->

                                    val isWatchingValue = if (finished) {
                                        database.episodeDao().hasAnyWatchHistoryForTvShow(tvShow.id)
                                    } else {
                                        true
                                    }

                                    database.tvShowDao().save(tvShow.copy().apply {
                                        merge(tvShow)
                                        isWatching = isWatchingValue
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
        handleNavigationButton(
            btnNext,
            EpisodeManager::hasNextEpisode,
            ::playNextEpisodeAcrossSeasons
        )
    }

    private fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (binding.channelPicker.isVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                binding.channelPicker.hide()
                return true
            }
            return false
        }

        if (binding.settings.isVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                binding.settings.hide()
                return true
            }
            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!binding.pvPlayer.controller.isVisible && UserPreferences.currentProvider is IptvProvider) {
                    binding.channelPicker.show()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun setupChannelPicker() {
        binding.channelPicker.setOnChannelSelectedListener { channel ->
            playChannel(channel)
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val channels = runCatching { IptvOrgProvider.getGuideChannels() }.getOrDefault(emptyList())
            val ids = channels.mapNotNull { it.liveMetadata?.channelId }
            val now = System.currentTimeMillis()

            LiveTvRepository.observePrograms(
                channelIds = ids,
                startMillis = now - 1 * 60 * 60 * 1000L,
                endMillis = now + 12 * 60 * 60 * 1000L
            ).collect { programs ->
                withContext(Dispatchers.Main) {
                    binding.channelPicker.setData(channels, programs)
                }
            }
        }
        
        binding.pvPlayer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                onKeyDown(keyCode, event)
            } else {
                false
            }
        }
    }

    private fun playChannel(channel: TvShow) {
        channel.liveMetadata?.channelId?.let { channelId ->
            LiveTvRepository.markChannelWatched(channelId, channel.id, channel.title)
        }

        val videoType = Video.Type.Episode(
            id = channel.id,
            number = 1,
            title = channel.title,
            poster = channel.poster,
            overview = channel.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = channel.id,
                title = channel.title,
                poster = channel.poster,
                banner = channel.banner,
                releaseDate = null,
                imdbId = null,
            ),
            season = Video.Type.Episode.Season(number = 1, title = "Live"),
        )

        currentVideo = null
        currentServer = null

        viewModel.playEpisode(videoType)
        updatePlayerHeader(videoType)
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

    private fun displayVideo(
        video: Video,
        server: Video.Server,
        startPositionMs: Long? = null,
        shouldPlay: Boolean = true,
    ) {
        currentVideo = video
        currentServer = server
        playbackAttemptStartedAt = SystemClock.elapsedRealtime()
        reportedReadyServerId = null
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

        val currentPosition = startPositionMs ?: player.currentPosition

        httpDataSource.setDefaultRequestProperties(
            mapOf(
                "User-Agent" to userAgent,
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

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                if (playbackState == Player.STATE_READY) {
                    binding.pvPlayer.controller.binding.exoPlayPause.nextFocusDownId = -1
                    updatePlayerScale()
                    reportCurrentStreamHealthy()
                    stopBufferingWatchdog()
                }
                
                if (playbackState == Player.STATE_BUFFERING) {
                    val isLive = currentVideo?.source?.contains("ronaldo.tvfor.pro") == true || 
                                currentVideo?.source?.contains(".m3u8") == true ||
                                currentServer?.id?.contains("ronaldo.tvfor.pro") == true
                    if (isLive) startBufferingWatchdog()
                }

                if (playbackState == Player.STATE_ENDED) {
                    stopBufferingWatchdog()
                }
                
                updateLiveControls()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                super.onTimelineChanged(timeline, reason)
                updateLiveControls()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                super.onTracksChanged(tracks)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                super.onVideoSizeChanged(videoSize)
                updatePlayerScale()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.pvPlayer.keepScreenOn = isPlaying

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
                Log.e("PlayerTvFragment", "onPlayerError: ", error)

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
                    Log.i("PlayerTvFragment", "Playback failed, trying next server: ${nextServer.name}")
                    Toast.makeText(requireContext(), "Playback failed, trying: ${nextServer.name}", Toast.LENGTH_SHORT).show()
                    viewModel.getVideo(nextServer)
                } else {
                    Log.e("PlayerTvFragment", "All servers failed.")
                    Toast.makeText(requireContext(), "All servers failed to play this content.", Toast.LENGTH_LONG).show()
                }
            }
        })

        fun startPlayback(positionMs: Long) {
            player.seekTo(positionMs)
            player.prepare()
            // Note: setSubtitleOffsetUs is not available in Media3 1.5.1
            // (player as? ExoPlayer)?.setSubtitleOffsetUs(UserPreferences.subtitleOffset * 1000L)
            player.playWhenReady = shouldPlay
        }

        if (startPositionMs != null) {
            startPlayback(startPositionMs)
        } else if (currentPosition == 0L) {
            lifecycleScope.launch {
                val resumePosition = withContext(Dispatchers.IO) {
                    resolveResumePosition(args.videoType, UserPreferences.currentProvider)
                }
                startPlayback(resumePosition)
            }
        } else {
            startPlayback(currentPosition)
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
                    WatchNextUtils.updateWatchNext(requireContext(), it)
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
                    WatchNextUtils.updateWatchNext(requireContext(), it)

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

    private fun updateCastAvailability(video: Video) {
    }

    private fun Video.requiresCastHeaders(): Boolean = headers?.keys?.any {
        it.equals("Referer", ignoreCase = true) || it.equals("Origin", ignoreCase = true)
    } == true

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

    private var secondarySubtitleJob: Job? = null

    private fun updateSecondarySubtitles() {
        val lang = UserPreferences.secondarySubtitle ?: run {
            binding.secondarySubtitleView.isVisible = false
            secondarySubtitleJob?.cancel()
            SubtitleHelper.clear()
            return
        }

        if (!UserPreferences.bilingualSubtitles) {
            binding.secondarySubtitleView.isVisible = false
            return
        }

        val video = currentVideo ?: return
        val sub = video.subtitles.find { (it.language ?: it.label).substringBefore(" ") == lang } ?: return

        secondarySubtitleJob?.cancel()
        secondarySubtitleJob = lifecycleScope.launch {
            SubtitleHelper.downloadAndParse(sub.file)
            withContext(Dispatchers.Main) {
                binding.secondarySubtitleView.isVisible = true
            }
        }
    }

    private var bufferingWatchdogJob: Job? = null
    private fun startBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(20_000)
            if (player.playbackState == Player.STATE_BUFFERING) {
                val nextServer = servers.getOrNull(servers.indexOf(currentServer) + 1)
                if (nextServer != null) {
                    Log.i("PlayerTvFragment", "Stuck in buffering, trying next server: ${nextServer.name}")
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
        if (_binding == null) return
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        progressRunnable = Runnable {
            if (_binding == null) return@Runnable
            if (player.isPlaying) {
                val show = player.currentPosition in 3000..120000
                showSkipIntroButton(show)
                updateNextEpisodeOverlay()

                val secondaryCue = SubtitleHelper.getCueAt(player.currentPosition)
                if (secondaryCue != null) {
                    binding.secondarySubtitleView.setCues(
                        listOf(
                            Cue.Builder()
                                .setText(secondaryCue)
                                .build()
                        )
                    )
                } else {
                    binding.secondarySubtitleView.setCues(emptyList())
                }
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
        updateNextEpisodeOverlayFocusBindings(true)
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
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                requireContext(),
                R.anim.fade_in
            )
            updateNextEpisodeOverlayAlpha(
                binding.btnNextEpisodeAction.hasFocus() || binding.btnNextEpisodeDismiss.hasFocus()
            )
            binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
            binding.layoutNextEpisodeOverlay.isVisible = true
            binding.btnNextEpisodeAction.post {
                if (_binding == null || !binding.layoutNextEpisodeOverlay.isVisible) return@post
                binding.btnNextEpisodeAction.requestFocus()
            }
        }
    }

    private fun hideNextEpisodeOverlay() {
        if (_binding == null) return
        updateNextEpisodeOverlayFocusBindings(false)
        if (binding.layoutNextEpisodeOverlay.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                requireContext(),
                R.anim.fade_out
            )
            binding.layoutNextEpisodeOverlay.startAnimation(fadeOut)
            binding.layoutNextEpisodeOverlay.isGone = true
        }
    }

    private fun updateNextEpisodeOverlayAlpha(hasFocus: Boolean) {
        if (_binding == null) return
        binding.layoutNextEpisodeOverlay.alpha =
            if (hasFocus) NEXT_EPISODE_OVERLAY_ALPHA_FOCUSED
            else NEXT_EPISODE_OVERLAY_ALPHA_UNFOCUSED
    }

    private fun updateNextEpisodeOverlayFocusBindings(overlayVisible: Boolean) {
        val controllerBinding = binding.pvPlayer.controller.binding
        val overlayActionId = binding.btnNextEpisodeAction.id
        val overlayDismissId = binding.btnNextEpisodeDismiss.id

        controllerBinding.exoSettings.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
        controllerBinding.btnExoAspectRatio.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
        controllerBinding.exoProgress.nextFocusUpId = View.NO_ID
        controllerBinding.btnCustomNext.nextFocusDownId = R.id.exo_progress
        controllerBinding.exoPlayPause.nextFocusDownId = R.id.exo_progress

        controllerBinding.btnSkipIntro.nextFocusLeftId = if (overlayVisible) overlayActionId else View.NO_ID
        controllerBinding.btnSkipIntro.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
        controllerBinding.btnSkipIntro.nextFocusDownId = if (overlayVisible) overlayActionId else View.NO_ID

        binding.btnNextEpisodeAction.nextFocusLeftId = overlayDismissId
        binding.btnNextEpisodeAction.nextFocusRightId = overlayDismissId
        binding.btnNextEpisodeAction.nextFocusUpId = controllerBinding.exoPlayPause.id
        binding.btnNextEpisodeAction.nextFocusDownId =
            if (controllerBinding.btnSkipIntro.isVisible) controllerBinding.btnSkipIntro.id
            else controllerBinding.exoSettings.id

        binding.btnNextEpisodeDismiss.nextFocusLeftId = overlayActionId
        binding.btnNextEpisodeDismiss.nextFocusRightId = overlayActionId
        binding.btnNextEpisodeDismiss.nextFocusUpId = controllerBinding.exoPlayPause.id
        binding.btnNextEpisodeDismiss.nextFocusDownId =
            if (controllerBinding.btnSkipIntro.isVisible) controllerBinding.btnSkipIntro.id
            else controllerBinding.exoSettings.id
    }

    private fun showSkipIntroButton(show: Boolean) {
        val btnSkipIntro = binding.pvPlayer.controller.binding.btnSkipIntro
        if (show && btnSkipIntro.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                requireContext(),
                R.anim.fade_in
            )
            btnSkipIntro.startAnimation(fadeIn)
            btnSkipIntro.isVisible = true
            if (binding.layoutNextEpisodeOverlay.isVisible) {
                updateNextEpisodeOverlayFocusBindings(true)
            }
        } else if (!show && btnSkipIntro.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                requireContext(),
                R.anim.fade_out
            )
            btnSkipIntro.startAnimation(fadeOut)
            btnSkipIntro.isGone = true
            if (binding.layoutNextEpisodeOverlay.isVisible) {
                updateNextEpisodeOverlayFocusBindings(true)
            }
        }
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
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }

    private fun initializePlayer(extraBuffering: Boolean, softwareDecoder: Boolean = currentSoftwareDecoder) {
        releasePlayer()
        currentExtraBuffering = extraBuffering
        currentSoftwareDecoder = softwareDecoder

        var tokenLogged = false
        val okHttpClient = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .addInterceptor { chain ->
                var request = chain.request()

                if (currentVideo?.maintainToken == true) {
                    val latestQuery = TokenManager.latestQuery
                    if (latestQuery != null) {
                        val origHttpUrl = request.url
                        val updatedHttpUrl = origHttpUrl.newBuilder().query(latestQuery).build()
                        request = request.newBuilder().url(updatedHttpUrl).build()
                        if (!tokenLogged) {
                            android.util.Log.d("TokenManager", "[TV-INTERCEPTOR] Token successfully injected (applied to all segments)")
                            tokenLogged = true
                        }
                    } else {
                        android.util.Log.w("TokenManager", "[TV-INTERCEPTOR] maintainToken=true but latestQuery is null! URL: ${request.url.host}")
                    }
                }

                chain.proceed(request)
            }
            .build()
        httpDataSource = OkHttpDataSource.Factory(okHttpClient)

        dataSourceFactory = if (isPlayingOfflineDownload) {
            offlineDataSourceFactory
        } else {
            DefaultDataSource.Factory(requireContext(), httpDataSource)
        }

        val isLive = currentVideo?.source?.contains("ronaldo.tvfor.pro") == true || 
                    currentVideo?.source?.contains(".m3u8") == true ||
                    currentServer?.id?.contains("ronaldo.tvfor.pro") == true

        localPlayer = buildPlayer(extraBuffering, isLive).also { p ->
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )

                val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                if (lang == "es") {
                    p.trackSelectionParameters =
                        p.trackSelectionParameters.buildUpon()
                            .setPreferredAudioLanguage("spa")
                            .build()
                } else {
                    p.trackSelectionParameters =
                        p.trackSelectionParameters.buildUpon()
                            .setPreferredAudioLanguages("en", "eng")
                            .build()
                }

                mediaSession = MediaSession.Builder(requireContext(), p)
                    .build()
            }

        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView
        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
        binding.settings.setOnSecondarySubtitleSelectedListener { 
            updateSecondarySubtitles()
        }
        binding.settings.setOnBilingualSelectedListener {
            updateSecondarySubtitles()
        }
        binding.settings.setOnQualitySelectedListener {
            reloadCurrentVideoForQualityChange()
        }
        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }
        binding.settings.onManualZoomClicked = {
            binding.settings.hide()
            binding.pvPlayer.hideController()
            (binding.pvPlayer as? PlayerTvView)?.enterManualZoomMode()
            binding.pvPlayer.requestFocus()
        }

        binding.pvPlayer.setControllerVisibilityListener(PlayerTvView.ControllerVisibilityListener { visibility ->
            val binding = _binding ?: return@ControllerVisibilityListener
            binding.pvPlayer.findViewById<View>(R.id.layout_sticky_header)?.visibility = visibility
            if (visibility == View.VISIBLE) {
                binding.pvPlayer.controller.binding.exoPlayPause.requestFocus()
                updateStickyHeader(binding)
                updateQuickControls(binding)
            }
        })

        binding.pvPlayer.controller.binding.tvQuickQuality.setOnClickListener {
            binding.settings.show()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                (binding.settings as? com.nexastream.app.fragments.player.settings.PlayerSettingsTvView)?.displaySettings(com.nexastream.app.fragments.player.settings.PlayerSettingsView.Setting.QUALITY)
            }
        }

        binding.pvPlayer.controller.binding.tvQuickSpeed.setOnClickListener {
            binding.settings.show()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                (binding.settings as? com.nexastream.app.fragments.player.settings.PlayerSettingsTvView)?.displaySettings(com.nexastream.app.fragments.player.settings.PlayerSettingsView.Setting.SPEED)
            }
        }
    }

    private fun updateQuickControls(binding: FragmentPlayerTvBinding) {
        val controller = binding.pvPlayer.controller.binding
        
        val currentQuality = com.nexastream.app.fragments.player.settings.PlayerSettingsView.Settings.Quality.selected
        controller.tvQuickQuality.text = when (currentQuality) {
            is com.nexastream.app.fragments.player.settings.PlayerSettingsView.Settings.Quality.Auto -> {
                val track = currentQuality.currentTrack
                if (track != null) "Auto (${track.height}p)" else "Auto"
            }
            is com.nexastream.app.fragments.player.settings.PlayerSettingsView.Settings.Quality.VideoTrackInformation -> "${currentQuality.height}p"
            else -> "Quality"
        }

        val currentSpeed = com.nexastream.app.fragments.player.settings.PlayerSettingsView.Settings.Speed.selected
        controller.tvQuickSpeed.text = if (currentSpeed.value == 1f) "Normal" else "${currentSpeed.value}x"
    }

    private fun updateStickyHeader(binding: FragmentPlayerTvBinding) {
        val sticky = binding.pvPlayer.findViewById<ViewGroup>(R.id.layout_sticky_header) ?: return
        
        sticky.findViewById<TextView>(R.id.tv_exo_title)?.text = resolvePlayerTitle()
        sticky.findViewById<TextView>(R.id.tv_exo_subtitle)?.text = resolvePlayerSubtitle()
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

    private fun showQrDialog(content: String) {
        val displayMetrics: DisplayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val dialogWidth = (displayMetrics.widthPixels * 0.72f).toInt()
        val qrSize = minOf(
            (dialogWidth - (density * 64).toInt()).coerceAtLeast((density * 240).toInt()),
            (displayMetrics.heightPixels * 0.45f).toInt().coerceAtLeast((density * 240).toInt()),
        )
        val bitmap = QrUtils.generate(content, qrSize) ?: return

        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        val instructionsView = TextView(requireContext()).apply {
            text = buildString {
                append("Solve captcha on phone")
                if (BypassWebSocketEndpointHelper.isProbablyEmulator()) {
                    append("\n\nEmulator note: set 'Bypass advertised host' in TV settings to your PC LAN IP and forward TCP 8081 to the emulator.")
                }
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (density * 24).toInt(),
                (density * 16).toInt(),
                (density * 24).toInt(),
                (density * 12).toInt(),
            )
        }
        container.addView(
            imageView,
            LinearLayout.LayoutParams(qrSize, qrSize)
        )
        container.addView(
            instructionsView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )

        val scrollView = ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(container)
        }

        qrDialog = androidx.appcompat.app.AlertDialog.Builder(requireActivity())
            .setTitle("Scan with phone")
            .setView(scrollView)
            .setCancelable(true)
            .setOnCancelListener {
                Log.d("Bypass", "QR dialog cancelled")
                clearBypassSession(dismissDialog = false)
            }
            .create()

        qrDialog?.show()
        qrDialog?.window?.setLayout(dialogWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return runCatching {
            Uri.parse(url).host.equals("serienstream.to", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun buildSerienStreamBypassUrl(): String? {
        return null
    }

    private fun startWebSocketServer(): Int {
        if (wsServer != null) return wsServer?.address?.port ?: 8081

        val ports = listOf(8081, 8082, 8887, 0)
        for (port in ports) {
            val server = BypassWebSocketServer(port) { token, cookies ->
                requireActivity().runOnUiThread {
                    Log.d("BypassWS", "DONE received for token: $token")
                    onBypassCompleted(token, cookies)
                }
            }
            wsServer = server
            try {
                server.start()
                if (server.awaitStart(5_000)) {
                    val actualPort = server.address.port
                    Log.d("BypassWS", "WebSocket server started on port $actualPort")
                    return actualPort
                } else {
                    val error = server.getStartError()
                    Log.e("BypassWS", "Server failed to start on port $port: ${error?.message}")
                    stopWebSocketServer()
                }
            } catch (e: Exception) {
                Log.e("BypassWS", "Failed to start on port $port", e)
                stopWebSocketServer()
            }
        }
        return -1
    }

    private fun stopWebSocketServer() {
        try {
            wsServer?.stop()
        } catch (_: Exception) {}
        wsServer = null
    }

    private fun clearBypassSession(
        dismissDialog: Boolean = true,
        resetBypassDone: Boolean = false,
    ) {
        activeBypassSession?.let { session ->
            wsServer?.clearSession(session.token)
        }
        activeBypassSession = null
        waitingForBypass = false
        if (resetBypassDone) {
            bypassDone = false
        }
        if (dismissDialog) {
            qrDialog?.dismiss()
        }
        qrDialog = null
        stopWebSocketServer()
    }


    private fun onBypassCompleted(token: String, cookies: String?) {
        val session = activeBypassSession
        if (session == null || session.token != token) {
            Log.w("BypassWS", "Ignoring bypass completion for stale token: $token")
            return
        }

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
        currentExtraBuffering = extraBuffering

        val okHttpClient = NetworkClient.default
        httpDataSource = OkHttpDataSource.Factory(okHttpClient)

        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

        val isLive = currentVideo?.source?.contains("ronaldo.tvfor.pro") == true || 
                    currentVideo?.source?.contains(".m3u8") == true ||
                    currentServer?.id?.contains("ronaldo.tvfor.pro") == true

        localPlayer = buildPlayer(extraBuffering, isLive).also { p ->
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
            }

        // Bind new player to UI view
        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView

        bypassDone = true
        waitingForBypass = false
        activeBypassSession = null

        clearBypassSession(dismissDialog = true)
        applyBypassCookies(session.serverUrl, cookies)

        lifecycleScope.launch {
            delay(300)

            // restore episode context BEFORE reload
            when (val type = args.videoType) {
                is Video.Type.Episode -> {
                    EpisodeManager.setCurrentEpisode(type)
                }
                else -> {}
            }

            viewModel.reloadServersAfterBypass()
        }
    }

    private fun applyBypassCookies(url: String, cookieHeader: String?) {
        val cookies = cookieHeader?.trim().orEmpty()
        if (cookies.isBlank()) return

        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val targets = linkedSetOf<String>().apply {
            if (url.isNotBlank()) add(url)
            if (host.isNotBlank()) {
                add("https://$host/")
                add("http://$host/")
            }
        }
        if (targets.isEmpty()) return

        val cookieManager = CookieManager.getInstance()
        cookies.split(";")
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
