package com.nexastream.app.ui.screens.downloads

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.scheduler.Requirements
import coil.compose.AsyncImage
import com.nexastream.app.models.Download
import com.nexastream.app.models.LocalVideoFile

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onPlayClick: (Download) -> Unit,
    onPlayLocalVideo: (LocalVideoFile) -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val downloads by viewModel.downloads.collectAsState(initial = emptyList())
    val localVideos by viewModel.localVideos.collectAsState()
    val isScanningLocal by viewModel.isScanningLocal.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    var showClearAllConfirmation by remember { mutableStateOf(false) }
    var downloadToDelete by remember { mutableStateOf<Download?>(null) }

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            viewModel.scanLocalVideos(context)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val fileName = getFileNameFromUri(context, it) ?: "Selected Video"
            val localFile = LocalVideoFile(
                id = System.currentTimeMillis(),
                title = fileName,
                fileName = fileName,
                path = it.toString(),
                contentUri = it.toString(),
                mimeType = "video/*"
            )
            onPlayLocalVideo(localFile)
        }
    }

    LaunchedEffect(selectedTab, hasPermission) {
        if (selectedTab == 1 && hasPermission) {
            viewModel.scanLocalVideos(context)
        }
    }

    if (showClearAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmation = false },
            title = { Text("Clear all downloads?") },
            text = { Text("This will permanently remove all your offline content. Are you sure?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllDownloads()
                        showClearAllConfirmation = false
                    }
                ) {
                    Text("Delete All", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmation = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }

    if (downloadToDelete != null) {
        AlertDialog(
            onDismissRequest = { downloadToDelete = null },
            title = { Text("Delete this download?") },
            text = { Text("Are you sure you want to remove '${downloadToDelete?.title}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        downloadToDelete?.let { viewModel.deleteDownload(it.id) }
                        downloadToDelete = null
                    }
                ) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color.Black)) {
                TopAppBar(
                    title = { Text("Downloads & Storage", color = Color.White) },
                    actions = {
                        if (selectedTab == 0 && downloads.isNotEmpty()) {
                            IconButton(onClick = { showClearAllConfirmation = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All", tint = Color.White)
                            }
                        } else if (selectedTab == 1) {
                            IconButton(onClick = { filePickerLauncher.launch("video/*") }) {
                                Icon(Icons.Default.FileOpen, contentDescription = "Pick File", tint = Color.White)
                            }
                            if (hasPermission) {
                                IconButton(onClick = { viewModel.scanLocalVideos(context) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Black,
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFFE50914)
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { viewModel.setSelectedTab(0) },
                        text = {
                            Text(
                                "App Downloads (${downloads.size})",
                                color = if (selectedTab == 0) Color.White else Color.Gray,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { viewModel.setSelectedTab(1) },
                        text = {
                            Text(
                                "Phone Videos",
                                color = if (selectedTab == 1) Color.White else Color.Gray,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        },
        containerColor = Color.Black
    ) { padding ->
        if (selectedTab == 0) {
            // APP DOWNLOADS TAB
            if (downloads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("No app downloads yet", color = Color.Gray)
                }
            } else {
                val isWaitingForWifi = downloads.any { download ->
                    (download.status == Download.Status.QUEUED || download.status == Download.Status.DOWNLOADING) &&
                            (download.waitingReason and Requirements.NETWORK_UNMETERED) != 0
                }

                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (isWaitingForWifi) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2200)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Downloads paused (Wi-Fi required)",
                                        color = Color(0xFFFFD54F),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Wi-Fi Only mode is enabled in Settings.",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { viewModel.toggleAllowMobileData(true) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFC107),
                                        contentColor = Color.Black
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Allow Mobile Data",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
                        items(downloads, key = { it.id }) { download ->
                            DownloadItem(
                                download = download,
                                onPlay = { onPlayClick(download) },
                                onPause = { viewModel.pauseDownload(download.id) },
                                onResume = { viewModel.resumeDownload(download.id) },
                                onRetry = { viewModel.retryDownload(download.id) },
                                onDelete = { downloadToDelete = download }
                            )
                        }
                    }
                }
            }
        } else {
            // PHONE VIDEOS TAB
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (!hasPermission) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Storage Permission Required",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Grant storage permission to allow NexaStream to scan and play video files stored on your phone.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { permissionLauncher.launch(requiredPermission) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
                        ) {
                            Text("Grant Permission", color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch("video/*") }
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Or Pick a File directly", color = Color.White)
                        }
                    }
                } else if (isScanningLocal) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFFE50914))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Scanning phone videos...", color = Color.Gray)
                    }
                } else if (localVideos.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("No video files found on phone storage", color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { filePickerLauncher.launch("video/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open File Picker", color = Color.White)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${localVideos.size} video files found",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                                TextButton(onClick = { filePickerLauncher.launch("video/*") }) {
                                    Text("Pick File", color = Color(0xFF1E88E5), fontSize = 12.sp)
                                }
                            }
                        }

                        items(localVideos, key = { it.contentUri }) { localVideo ->
                            LocalVideoItem(
                                localVideo = localVideo,
                                onPlay = { onPlayLocalVideo(localVideo) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocalVideoItem(
    localVideo: LocalVideoFile,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clickable { onPlay() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1F1F1F)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = localVideo.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!localVideo.folderName.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = localVideo.folderName,
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Text(" • ", color = Color.Gray, fontSize = 11.sp)
                }

                val durationText = formatDurationMs(localVideo.duration)
                val sizeText = formatBytes(localVideo.size)
                Text(
                    text = "$durationText • $sizeText",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        }

        IconButton(onClick = onPlay) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color.White
            )
        }
    }
}

@UnstableApi
@Composable
fun DownloadItem(
    download: Download,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable(enabled = download.status == Download.Status.COMPLETED) { onPlay() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = download.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(100.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.DarkGray)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = download.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f, fill = false)
                )

                val qualityText = download.quality
                if (!qualityText.isNullOrBlank()) {
                    val resolution = qualityText.substringBefore(" - ")
                    Surface(
                        color = when {
                            resolution.contains("2160p") || resolution.contains("4K") || resolution.contains("UHD") -> Color(0xFFE50914)
                            resolution.contains("1080p") || resolution.contains("FHD") -> Color(0xFF1E88E5)
                            resolution.contains("720p") || resolution.contains("HD") -> Color(0xFF43A047)
                            resolution.contains("CAM") -> Color(0xFFFFB300)
                            else -> Color(0xFF424242)
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = resolution,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (download.status == Download.Status.DOWNLOADING) {
                val hasKnownProgress = download.progress > 0
                if (hasKnownProgress) {
                    LinearProgressIndicator(
                        progress = { download.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = Color.Red,
                        trackColor = Color.DarkGray
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = Color.Red,
                        trackColor = Color.DarkGray
                    )
                }
                Text(
                    text = if (download.waitingReason != 0) {
                        when {
                            (download.waitingReason and Requirements.NETWORK_UNMETERED) != 0 -> "Waiting for Wi-Fi..."
                            (download.waitingReason and Requirements.NETWORK) != 0 -> "Waiting for network..."
                            else -> "Waiting..."
                        }
                    } else if (hasKnownProgress) {
                        "Downloading... ${download.progress}%"
                    } else {
                        "Downloading..."
                    },
                    color = if (download.waitingReason != 0) Color(0xFFFFC107) else Color.Gray,
                    fontSize = 12.sp
                )
                DownloadMetadata(download)
            } else {
                Text(
                    text = statusLabel(download),
                    color = statusColor(download),
                    fontSize = 12.sp
                )
                if (download.status == Download.Status.PAUSED || download.status == Download.Status.COMPLETED || download.status == Download.Status.QUEUED) {
                    DownloadMetadata(download)
                }
                if (download.status == Download.Status.FAILED && !download.errorMessage.isNullOrEmpty()) {
                    Text(
                        text = download.errorMessage,
                        color = Color.Red.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            when (download.status) {
                Download.Status.DOWNLOADING -> {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.White)
                    }
                }
                Download.Status.PAUSED, Download.Status.QUEUED -> {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White)
                    }
                }
                Download.Status.FAILED -> {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color.White)
                    }
                }
                Download.Status.COMPLETED -> {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
            }
        }
    }
}

@Composable
private fun DownloadMetadata(download: Download) {
    val parts = listOfNotNull(
        formatSizeProgress(download).takeIf { it.isNotBlank() },
        formatSpeed(download.downloadSpeed).takeIf { download.status == Download.Status.DOWNLOADING && download.downloadSpeed > 0L },
        formatEta(download.etaSeconds).takeIf { download.status == Download.Status.DOWNLOADING && download.etaSeconds != null }
    )

    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString(" • "),
            color = Color.Gray,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@UnstableApi
private fun statusLabel(download: Download): String {
    return when (download.status) {
        Download.Status.FAILED -> "Failed"
        Download.Status.PAUSED -> "Paused"
        Download.Status.COMPLETED -> "Ready to watch"
        Download.Status.QUEUED -> {
            if (download.waitingReason != 0) {
                when {
                    (download.waitingReason and Requirements.NETWORK_UNMETERED) != 0 -> "Waiting for Wi-Fi..."
                    (download.waitingReason and Requirements.NETWORK) != 0 -> "Waiting for network..."
                    else -> "Queued"
                }
            } else "Queued"
        }
        Download.Status.DOWNLOADING -> "Downloading"
    }
}

private fun statusColor(download: Download): Color {
    return when (download.status) {
        Download.Status.COMPLETED -> Color.Green
        Download.Status.FAILED -> Color.Red
        Download.Status.PAUSED -> Color(0xFFFFC107)
        Download.Status.QUEUED -> if (download.waitingReason != 0) Color(0xFFFFC107) else Color.Gray
        else -> Color.Gray
    }
}

private fun formatSizeProgress(download: Download): String {
    return when {
        download.downloadedSize > 0L && download.totalSize > 0L -> {
            "${formatBytes(download.downloadedSize)} of ${formatBytes(download.totalSize)}"
        }
        download.downloadedSize > 0L -> "${formatBytes(download.downloadedSize)} cached"
        download.totalSize > 0L -> formatBytes(download.totalSize)
        else -> ""
    }
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return "${formatBytes(bytesPerSecond)}/s"
}

private fun formatEta(seconds: Long?): String? {
    if (seconds == null || seconds <= 0L) return null

    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60

    return when {
        hours > 0L -> "${hours}h ${minutes}m left"
        minutes > 0L -> "${minutes}m ${remainingSeconds}s left"
        else -> "${remainingSeconds}s left"
    }
}

private fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    val hours = (ms / (1000 * 60 * 60))
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return if (value >= 10 || unitIndex == 0) {
        "%.0f %s".format(value, units[unitIndex])
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
}

private fun getFileNameFromUri(context: Context, uri: android.net.Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    result = cursor.getString(nameIndex)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}
