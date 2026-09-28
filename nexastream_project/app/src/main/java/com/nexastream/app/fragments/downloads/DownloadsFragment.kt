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
import androidx.navigation.fragment.findNavController
import com.nexastream.app.ui.screens.downloads.DownloadsScreen
import com.nexastream.app.ui.theme.Nexastream2Theme
import dagger.hilt.android.AndroidEntryPoint

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                Nexastream2Theme {
                    DownloadsScreen(
                        onPlayClick = { download ->
                            findNavController().navigate(
                                DownloadsFragmentDirections.actionDownloadsToPlayer(
                                    id = download.id,
                                    title = download.title,
                                    subtitle = download.quality ?: "",
                                    videoType = com.nexastream.app.models.Video.Type.Movie(
                                        download.id,
                                        download.title,
                                        "",
                                        download.poster ?: "",
                                        null
                                    )
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}
