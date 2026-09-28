package com.nexastream.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nexastream.app.models.Video
import com.nexastream.app.utils.DownloadManager
import com.nexastream.app.utils.DownloadQualityFormatter
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.media3.common.util.UnstableApi
@AndroidEntryPoint
class DownloadQualityBottomSheet : BottomSheetDialogFragment() {

    private var servers: List<Video.Server> = emptyList()
    private var mediaId: String = ""
    private var mediaTitle: String = ""
    private var mediaPoster: String? = null
    private var videoType: Video.Type? = null

    private val downloadManager: DownloadManager by lazy {
        val entryPoint = EntryPointAccessors.fromApplication(
            requireContext().applicationContext,
            DownloadManagerEntryPoint::class.java
        )
        entryPoint.downloadManager()
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface DownloadManagerEntryPoint {
        fun downloadManager(): DownloadManager
    }

    companion object {
        private const val ARG_SERVERS = "arg_servers"
        private const val ARG_MEDIA_ID = "arg_media_id"
        private const val ARG_MEDIA_TITLE = "arg_media_title"
        private const val ARG_MEDIA_POSTER = "arg_media_poster"
        private const val ARG_VIDEO_TYPE = "arg_video_type"

        fun newInstance(
            servers: List<Video.Server>,
            mediaId: String,
            mediaTitle: String,
            mediaPoster: String?,
            videoType: Video.Type
        ): DownloadQualityBottomSheet {
            return DownloadQualityBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_SERVERS, ArrayList(servers))
                    putString(ARG_MEDIA_ID, mediaId)
                    putString(ARG_MEDIA_TITLE, mediaTitle)
                    putString(ARG_MEDIA_POSTER, mediaPoster)
                    putSerializable(ARG_VIDEO_TYPE, videoType)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            servers = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    it.getSerializable(ARG_SERVERS, java.util.ArrayList::class.java) as? List<Video.Server>
                } else {
                    it.getSerializable(ARG_SERVERS) as? List<Video.Server>
                } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            mediaId = it.getString(ARG_MEDIA_ID, "")
            mediaTitle = it.getString(ARG_MEDIA_TITLE, "")
            mediaPoster = it.getString(ARG_MEDIA_POSTER)
            videoType = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    it.getSerializable(ARG_VIDEO_TYPE, Video.Type::class.java)
                } else {
                    it.getSerializable(ARG_VIDEO_TYPE) as? Video.Type
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val provider = remember { UserPreferences.currentProvider }
                var updatedServers by remember { mutableStateOf(servers) }
                val extractionStatus = remember { mutableStateMapOf<String, String>() }
                val serverStreamMeta = remember { mutableStateMapOf<String, DownloadQualityFormatter.StreamMetadata>() }

                LaunchedEffect(servers) {
                    if (provider != null) {
                        servers.forEach { server ->
                            if (server.video == null) {
                                launch(Dispatchers.IO) {
                                    withContext(Dispatchers.Main) {
                                        extractionStatus[server.id] = "loading"
                                    }
                                    try {
                                        val video = provider.getVideo(server)
                                        server.video = video
                                        val meta = DownloadQualityFormatter.inspectStreamMetadata(video)
                                        withContext(Dispatchers.Main) {
                                            serverStreamMeta[server.id] = meta
                                            updatedServers = updatedServers.map { if (it.id == server.id) it.copy().apply { this.video = video } else it }
                                            extractionStatus[server.id] = "done"
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            extractionStatus[server.id] = "failed"
                                        }
                                        android.util.Log.e("DownloadBS", "Background fetch failed for ${server.name}: ${e.message}")
                                    }
                                }
                            } else {
                                launch(Dispatchers.IO) {
                                    val meta = DownloadQualityFormatter.inspectStreamMetadata(server.video!!)
                                    withContext(Dispatchers.Main) {
                                        serverStreamMeta[server.id] = meta
                                        extractionStatus[server.id] = "done"
                                    }
                                }
                            }
                        }
                    }
                }

                var selectedResolution by remember { mutableStateOf("") }
                var selectedServerId by remember { mutableStateOf<String?>(null) }
                var showUnavailableSection by remember { mutableStateOf(false) }

                fun getSupportedResolutions(server: Video.Server): List<String> {
                    val meta = serverStreamMeta[server.id]
                    if (meta != null && meta.resolutionLabel.isNotBlank() && meta.resolutionLabel != "HD") {
                        return listOf(meta.resolutionLabel.uppercase())
                    }

                    val label = DownloadQualityFormatter.title(server).substringBefore(" - ")
                    if (label != "Unknown" && label.endsWith("p")) {
                        return listOf(label.uppercase())
                    }

                    val sourceUrl = server.video?.source.orEmpty()
                    if (sourceUrl.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
                        try {
                            val base64Data = sourceUrl.substringAfter("base64,")
                            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            val manifestContent = String(decodedBytes, Charsets.UTF_8)
                            val resolutions = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                                .findAll(manifestContent)
                                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                                .map { "${it}P" }
                                .toList()
                            if (resolutions.isNotEmpty()) {
                                return resolutions.distinct()
                            }
                        } catch (e: Exception) {}
                    }

                    val text = "${server.name} ${server.src} $sourceUrl".lowercase()
                    val numeric = Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240)p?(?!\d)""").find(text)?.groupValues?.getOrNull(1)
                    if (numeric != null) return listOf("${numeric}P")

                    if (text.contains("vixsrc") || text.contains("2embed") || text.contains("vidsrc") || text.contains("vidlink") || text.contains("vidflix")) {
                        return listOf("1080P", "720P", "480P", "360P")
                    }
                    return listOf("720P")
                }

                // Filter working/loading servers vs failed servers
                val validServers = remember(updatedServers, extractionStatus.toMap()) {
                    updatedServers.filter { extractionStatus[it.id] != "failed" }
                }
                val failedServers = remember(updatedServers, extractionStatus.toMap()) {
                    updatedServers.filter { extractionStatus[it.id] == "failed" }
                }

                // Compute ONLY available resolutions from working/loading servers
                val availableResolutions = remember(validServers, serverStreamMeta.toMap()) {
                    val candidates = validServers.ifEmpty { updatedServers }
                    candidates.flatMap { getSupportedResolutions(it) }.distinct().sortedByDescending {
                        it.removeSuffix("P").removeSuffix("p").toIntOrNull() ?: 0
                    }
                }

                val activeResolution = if (selectedResolution.isNotBlank() && selectedResolution in availableResolutions) {
                    selectedResolution
                } else {
                    availableResolutions.firstOrNull() ?: "720P"
                }

                val activeServers = remember(activeResolution, validServers, updatedServers, serverStreamMeta.toMap()) {
                    val pool = validServers.ifEmpty { updatedServers }
                    pool.filter { activeResolution in getSupportedResolutions(it) }
                }

                // Default selection
                LaunchedEffect(activeServers) {
                    if (selectedServerId == null || activeServers.none { it.id == selectedServerId }) {
                        selectedServerId = activeServers.firstOrNull()?.id
                    }
                }

                val currentSelectedServer = activeServers.firstOrNull { it.id == selectedServerId } ?: activeServers.firstOrNull()
                val currentMeta = currentSelectedServer?.let { serverStreamMeta[it.id] }
                val selectedSizeLabel = currentMeta?.exactSizeBytes?.let { DownloadQualityFormatter.formatSizeBytes(it) }
                    ?: currentSelectedServer?.let { DownloadQualityFormatter.estimatedSizeString(it, activeResolution) }
                    ?: "Unknown Size"

                Surface(
                    color = Color(0xFF121214),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 10.dp, bottom = 20.dp)
                    ) {
                        // Drag handle
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(Color(0xFF333338), RoundedCornerShape(2.dp))
                                .align(Alignment.CenterHorizontally)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (mediaTitle.isNotBlank()) mediaTitle else "Download",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            IconButton(
                                onClick = { dismiss() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.Gray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Available Quality Chips Bar
                        if (availableResolutions.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(availableResolutions) { res ->
                                    val isSelected = res == activeResolution
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (isSelected) Color(0xFF2A2E33) else Color(0xFF1E1E22),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) Color(0xFF00E5FF) else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedResolution = res }
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = res,
                                            color = if (isSelected) Color(0xFF00E5FF) else Color.LightGray,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Active Working Stream Options List
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(activeServers) { server ->
                                val status = extractionStatus[server.id] ?: "loading"
                                val isSelected = server.id == currentSelectedServer?.id
                                val meta = serverStreamMeta[server.id]

                                val sizeText = meta?.exactSizeBytes?.let { DownloadQualityFormatter.formatSizeBytes(it) }
                                    ?: DownloadQualityFormatter.estimatedSizeString(server, activeResolution)
                                val formatText = meta?.formatLabel ?: DownloadQualityFormatter.format(server.video?.type, server.video?.source ?: server.src)
                                val dimText = meta?.dimensions?.let { " ($it)" } ?: ""
                                val bwText = DownloadQualityFormatter.formatBitrate(meta?.bitrate)?.let { " • $it" } ?: ""

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            color = if (isSelected) Color(0xFF1A2228) else Color(0xFF18181B),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.6f) else Color(0xFF242428),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { selectedServerId = server.id }
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) Color(0xFF00E5FF) else Color.DarkGray,
                                            modifier = Modifier.size(22.dp)
                                        )

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${DownloadQualityFormatter.title(server)}$dimText",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "$sizeText$bwText | $formatText",
                                                color = Color.Gray,
                                                fontSize = 12.sp
                                            )
                                        }

                                        if (status == "loading") {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = Color(0xFF00E5FF)
                                            )
                                        }
                                    }
                                }
                            }

                            // Collapsible Unavailable Sources
                            if (failedServers.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showUnavailableSection = !showUnavailableSection }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Unavailable Sources (${failedServers.size})",
                                            color = Color.Gray,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Icon(
                                            imageVector = if (showUnavailableSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = Color.Gray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                if (showUnavailableSection) {
                                    items(failedServers) { failedServer ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFF141416), RoundedCornerShape(8.dp))
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = "${DownloadQualityFormatter.title(failedServer)} - Connection Failed",
                                                color = Color.DarkGray,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Reference-Matched Action Button: [ Download · 982.4 MB ]
                        Button(
                            onClick = {
                                currentSelectedServer?.let { server ->
                                    startExtractionAndDownload(server, activeResolution)
                                    dismiss()
                                }
                            },
                            enabled = currentSelectedServer != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color.Black,
                                disabledContainerColor = Color(0xFF222225),
                                disabledContentColor = Color.Gray
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Download · $selectedSizeLabel",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startExtractionAndDownload(server: Video.Server, selectedQuality: String) {
        val provider = UserPreferences.currentProvider ?: return
        val appContext = requireContext().applicationContext
        val lifecycleScope = requireActivity().lifecycleScope
        
        Toast.makeText(appContext, "Starting download...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                val video = withContext(Dispatchers.IO) {
                    server.video ?: provider.getVideo(server)
                }
                
                downloadManager.startDownload(
                    id = mediaId,
                    title = mediaTitle,
                    poster = mediaPoster,
                    url = video.source,
                    quality = "$selectedQuality - ${DownloadQualityFormatter.title(server).substringAfter(" - ")}",
                    headers = video.headers,
                    mimeType = video.type
                )
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Download started", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("DownloadBS", "Extraction failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Failed to get video: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
