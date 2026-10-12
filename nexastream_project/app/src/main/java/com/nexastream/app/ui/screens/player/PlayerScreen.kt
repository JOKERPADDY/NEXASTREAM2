package com.nexastream.app.ui.screens.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.hilt.navigation.compose.hiltViewModel
import com.nexastream.app.models.Video
import com.nexastream.app.utils.DownloadQualityFormatter
import com.nexastream.app.utils.NetworkClient
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.setVideoDrmConfiguration
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50000, // Min buffer
                100000, // Max buffer
                1500, // Buffer for playback
                2000 // Buffer for playback after rebuffer
            ).build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(viewModel.dataSourceFactory))
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
            }
    }

    var isLocked by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(key1 = exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) {
                    viewModel.saveProgress(
                        position = exoPlayer.currentPosition,
                        duration = exoPlayer.duration,
                        hasStarted = exoPlayer.hasStarted(),
                        hasFinished = exoPlayer.hasFinished(),
                        hasReallyFinished = exoPlayer.hasReallyFinished()
                    )
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlayerScreen", "ExoPlayer Error: ${error.message}")
                viewModel.onPlayerError()
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                currentPosition = exoPlayer.currentPosition
            }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                playbackSpeed = parameters.speed
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            viewModel.saveProgress(
                position = exoPlayer.currentPosition,
                duration = exoPlayer.duration,
                hasStarted = exoPlayer.hasStarted(),
                hasFinished = exoPlayer.hasFinished(),
                hasReallyFinished = exoPlayer.hasReallyFinished()
            )
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(1000)
        }
    }

    LaunchedEffect(uiState.video) {
        uiState.video?.let { video ->
            val mediaItem = MediaItem.Builder()
                .setUri(video.source)
                .setMimeType(video.type)
                .setVideoDrmConfiguration(video.drmConfiguration)
                .build()
            exoPlayer.setMediaItem(mediaItem)
            val resumePosition = viewModel.resolveResumePosition()
            exoPlayer.seekTo(resumePosition)
            exoPlayer.prepare()
        }
    }

    LaunchedEffect(showControls) {
        if (showControls && !isLocked) {
            delay(5000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { if (!isLocked) showControls = !showControls }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Red
            )
        }

        if (uiState.error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Playback Error", color = Color.Red, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = uiState.error!!, color = Color.White, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.retry() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Retry")
                }
            }
        }

        // Custom Controls Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.movie?.title ?: uiState.tvShow?.title ?: "",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    
                    if (!isLocked) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AndroidView(
                                factory = { context ->
                                    MediaRouteButton(context).apply {
                                        CastButtonFactory.setUpMediaRouteButton(context, this)
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }
                    }
                }

                // Middle Controls
                if (!isLocked) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition - 10000) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Back 10s", tint = Color.White, modifier = Modifier.size(48.dp))
                        }

                        IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(64.dp)
                            )
                        }

                        IconButton(onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10000) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                    }
                }

                // Bottom Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    if (!isLocked) {
                        Slider(
                            value = currentPosition.toFloat(),
                            onValueChange = { exoPlayer.seekTo(it.toLong()) },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Red,
                                activeTrackColor = Color.Red,
                                inactiveTrackColor = Color.Gray
                            )
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = formatTime(currentPosition), color = Color.White, fontSize = 12.sp)
                            Text(text = formatTime(duration), color = Color.White, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                PlaybackSpeedSelector(
                                    currentSpeed = playbackSpeed,
                                    onSpeedSelected = { exoPlayer.setPlaybackSpeed(it) }
                                )

                                ServerSelector(
                                    currentServer = uiState.currentServer,
                                    servers = uiState.servers,
                                    onServerSelected = { viewModel.selectServer(it) }
                                )
                                
                                if (uiState.tvShow != null) {
                                    IconButton(onClick = { /* Show Episode Selector */ }) {
                                        Icon(Icons.Default.List, contentDescription = "Episodes", tint = Color.White)
                                    }
                                }
                            }

                            IconButton(onClick = { isLocked = !isLocked }) {
                                Icon(
                                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock",
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        // Only show lock button when locked
                        IconButton(
                            onClick = { isLocked = false },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaybackSpeedSelector(currentSpeed: Float, onSpeedSelected: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    Box {
        Text(
            text = "${currentSpeed}x",
            color = Color.White,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(8.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.DarkGray)
        ) {
            speeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text("${speed}x", color = Color.White) },
                    onClick = {
                        onSpeedSelected(speed)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun ServerSelector(
    currentServer: Video.Server?,
    servers: List<Video.Server>,
    onServerSelected: (Video.Server) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Dns, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = currentServer?.let { DownloadQualityFormatter.anonymizeName(it.name) } ?: "Servers",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.DarkGray)
        ) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (server == currentServer) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(DownloadQualityFormatter.anonymizeName(server.name), color = Color.White)
                        }
                    },
                    onClick = {
                        onServerSelected(server)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
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
