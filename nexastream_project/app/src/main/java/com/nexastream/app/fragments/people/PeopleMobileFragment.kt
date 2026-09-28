package com.nexastream.app.fragments.people

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.databinding.FragmentPeopleMobileBinding
import com.nexastream.app.databinding.HeaderPeopleMobileBinding
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.TvShow
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.ui.components.SafeGridLayoutManager
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.format
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PeopleMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentPeopleMobileBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<PeopleMobileFragmentArgs>()
    private val viewModel: PeopleViewModel by viewModels()
    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPeopleMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePeople()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is PeopleViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is PeopleViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is PeopleViewModel.State.SuccessLoading -> {
                        displayPeople(state.people, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is PeopleViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            viewModel.getPeople()
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.getPeople() }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initializePeople() {
        binding.rvPeople.apply {
            layoutManager = SafeGridLayoutManager(context, 3).also {
                it.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val viewType = try {
                            appAdapter.getItemViewType(position)
                        } catch (_: Exception) {
                            return 1
                        }
                        return when (AppAdapter.Type.entries.getOrNull(viewType)) {
                            AppAdapter.Type.HEADER -> it.spanCount
                            else -> 1
                        }
                    }
                }
            }
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(SpacingItemDecoration(10.dp(requireContext())))
        }
    }

    private fun displayPeople(people: People, hasMore: Boolean) {
        appAdapter.setHeader(
            binding = { parent ->
                HeaderPeopleMobileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            },
            bind = { binding ->
                binding.ivPeopleImage.apply {
                    clipToOutline = true
                    Glide.with(context)
                        .load(people.image ?: args.image)
                        .placeholder(R.drawable.ic_person_placeholder)
                        .centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(this)
                }

                binding.tvPeopleName.text = people.name.takeIf { it.isNotEmpty() } ?: args.name
                binding.tvPeopleBirthday.text = people.birthday?.format("MMMM dd, yyyy")
                binding.gPeopleBirthday.visibility = if (binding.tvPeopleBirthday.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                binding.tvPeopleDeathday.text = people.deathday?.format("MMMM dd, yyyy")
                binding.gPeopleDeathday.visibility = if (binding.tvPeopleDeathday.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                binding.tvPeopleBirthplace.text = people.placeOfBirth
                binding.gPeopleBirthplace.visibility = if (binding.tvPeopleBirthplace.text.isNullOrEmpty()) View.GONE else View.VISIBLE

                binding.tvPeopleBiography.apply {
                    maxLines = 7
                    text = people.biography
                }

                binding.tvPeopleBiographyReadMore.apply {
                    setOnClickListener {
                        binding.tvPeopleBiography.maxLines = Int.MAX_VALUE
                        binding.tvPeopleBiographyReadMore.visibility = View.GONE
                    }
                    binding.tvPeopleBiography.post {
                        visibility = if (binding.tvPeopleBiography.lineCount > 7) View.VISIBLE else View.GONE
                    }
                }
                binding.gPeopleBiography.visibility = if (binding.tvPeopleBiography.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        )

        appAdapter.submitList(people.filmography.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            }
        })

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMorePeopleFilmography() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}
