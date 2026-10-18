package com.nexastream.app.fragments.player

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.databinding.DialogSportsServersBinding
import com.nexastream.app.databinding.ItemServerLinkBinding
import com.nexastream.app.models.Video
import com.nexastream.app.utils.DownloadQualityFormatter
import kotlinx.coroutines.launch

class SportsServersDialogFragment : DialogFragment() {

    private var _binding: DialogSportsServersBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SportsServersDialogFragmentArgs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.MyMaterialDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSportsServersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.rvServers.layoutManager = LinearLayoutManager(requireContext())
        loadServers()
    }

    private fun loadServers() {
        binding.pbLoading.isVisible = true
        binding.rvServers.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("SportsServers", "Loading servers for: ${args.id}")
                val provider = com.nexastream.app.utils.UserPreferences.currentProvider
                val videoType = args.videoType
                val servers = runCatching {
                    provider.getServers(args.id, videoType)
                }.getOrElse {
                    com.nexastream.app.providers.AkSportsLiveProvider.getServers(args.id, videoType)
                }
                
                if (servers.isEmpty()) {
                    Log.w("SportsServers", "No servers found for: ${args.id}")
                    if (isAdded) {
                        navigateToPlayer(null)
                    }
                    return@launch
                }

                if (!isAdded) return@launch
                Log.d("SportsServers", "Found ${servers.size} servers")
                binding.pbLoading.isVisible = false
                binding.rvServers.isVisible = true
                binding.rvServers.adapter = ServerAdapter(servers) { selectedServer ->
                    navigateToPlayer(selectedServer.id)
                }
            } catch (e: Exception) {
                Log.e("SportsServers", "Error loading servers", e)
                if (isAdded) {
                    navigateToPlayer(null)
                }
            }
        }
    }

    private fun navigateToPlayer(serverId: String?) {
        if (!isAdded || isRemoving || isDetached) return
        
        try {
            val navController = findNavController()
            navController.navigate(
                R.id.action_global_player,
                bundleOf(
                    "id" to args.id,
                    "title" to args.title,
                    "subtitle" to args.subtitle,
                    "videoType" to args.videoType,
                    "serverId" to serverId
                )
            )
            dismissAllowingStateLoss()
        } catch (e: Exception) {
            Log.e("SportsServers", "Navigation failed", e)
            if (isAdded) {
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class ServerAdapter(
        private val servers: List<Video.Server>,
        private val onServerSelected: (Video.Server) -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemServerLinkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val server = servers[position]
            holder.binding.tvServerName.text = DownloadQualityFormatter.anonymizeName(server.name)
            holder.binding.ivServerIcon.setImageResource(R.drawable.ic_player_settings_servers)
            holder.binding.root.setOnClickListener { onServerSelected(server) }
        }

        override fun getItemCount(): Int = servers.size

        inner class ViewHolder(val binding: ItemServerLinkBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
