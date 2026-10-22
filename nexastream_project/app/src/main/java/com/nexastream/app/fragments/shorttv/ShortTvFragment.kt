package com.nexastream.app.fragments.shorttv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.nexastream.app.NexastreamApp
import com.nexastream.app.R
import com.nexastream.app.models.ShortTvItem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ShortTvFragment : Fragment() {

    private val viewModel: ShortTvViewModel by viewModels()
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: ShortTvAdapter

    private val categories = listOf("All Shorts", "Short Dramas", "Asian Dramas", "Mini Series")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_short_tv, container, false)
    }

    @OptIn(UnstableApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager = view.findViewById(R.id.short_tv_view_pager)
        tabLayout = view.findViewById(R.id.short_tv_tab_layout)
        progressBar = view.findViewById(R.id.short_tv_progress)

        categories.forEach { title ->
            tabLayout.addTab(tabLayout.newTab().setText(title))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let { position ->
                    viewModel.loadShortReels(categoryIndex = position)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        adapter = ShortTvAdapter(
            onWatchFullClick = { item ->
                Toast.makeText(
                    requireContext(),
                    "Opening full drama: ${item.title}",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onLikeClick = { item ->
                viewModel.toggleLike(item.id)
            },
            onSpeedClick = { item ->
                viewModel.cycleSpeed(item.id)
            },
            onEpisodesClick = { item ->
                showEpisodeSelector(item)
            },
            onDownloadClick = { item ->
                try {
                    NexastreamApp.instance.appDownloadManagerProvider.get().startDownload(
                        id = item.id,
                        title = "${item.title} - EP ${item.episodeNumber}",
                        poster = item.posterUrl,
                        url = item.videoUrl,
                        quality = "1080p",
                        headers = item.headers
                    )
                    Toast.makeText(
                        requireContext(),
                        "Starting download for ${item.title}",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "Download failed: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        viewPager.adapter = adapter
        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                adapter.setActivePage(position)
            }
        })

        observeViewModel()
    }

    private fun showEpisodeSelector(item: ShortTvItem) {
        val total = item.totalEpisodes
        val episodes = (1..total).map { "Episode $it" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("${item.title} - Select Episode")
            .setItems(episodes) { dialog, which ->
                val selectedEp = which + 1
                Toast.makeText(requireContext(), "Loading Episode $selectedEp...", Toast.LENGTH_SHORT).show()
                viewModel.selectEpisode(item.id, selectedEp)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        adapter.pauseAll()
    }

    override fun onStop() {
        super.onStop()
        adapter.pauseAll()
    }

    override fun onDestroyView() {
        adapter.releaseAll()
        super.onDestroyView()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ShortTvViewModel.UiState.Loading -> {
                            progressBar.visibility = View.VISIBLE
                        }
                        is ShortTvViewModel.UiState.Success -> {
                            progressBar.visibility = View.VISIBLE
                            progressBar.visibility = View.GONE
                            adapter.submitList(state.items)
                        }
                        is ShortTvViewModel.UiState.Error -> {
                            progressBar.visibility = View.GONE
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
