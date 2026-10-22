package com.nexastream.app.fragments.downloads

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Download
import com.nexastream.app.models.Video
import com.nexastream.app.ui.screens.downloads.DownloadsScreen
import com.nexastream.app.ui.theme.Nexastream2Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class DownloadsFragment : Fragment() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        view?.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        view?.visibility = View.GONE
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            setContent {
                Nexastream2Theme {
                    DownloadsScreen(
                        onPlayClick = { download ->
                            findNavController().navigate(
                                DownloadsFragmentDirections.actionDownloadsToPlayer(
                                    id = download.id,
                                    title = download.title,
                                    subtitle = download.quality ?: "",
                                    videoType = Video.Type.Movie(
                                        download.id,
                                        download.title,
                                        "",
                                        download.poster ?: "",
                                        null
                                    )
                                )
                            )
                        },
                        onPlayLocalVideo = { localVideo ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val downloadEntity = Download(
                                    id = "local_${localVideo.id}",
                                    title = localVideo.title,
                                    poster = null,
                                    cacheKey = localVideo.contentUri,
                                    url = localVideo.contentUri,
                                    status = Download.Status.COMPLETED,
                                    quality = localVideo.folderName ?: "Local",
                                    mimeType = localVideo.mimeType ?: "video/*",
                                    totalSize = localVideo.size
                                )
                                AppDatabase.getInstance(requireContext()).downloadDao().insert(downloadEntity)

                                withContext(Dispatchers.Main) {
                                    findNavController().navigate(
                                        DownloadsFragmentDirections.actionDownloadsToPlayer(
                                            id = downloadEntity.id,
                                            title = downloadEntity.title,
                                            subtitle = downloadEntity.quality ?: "",
                                            videoType = Video.Type.Movie(
                                                downloadEntity.id,
                                                downloadEntity.title,
                                                "",
                                                "",
                                                null
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
