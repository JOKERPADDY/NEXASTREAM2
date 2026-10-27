package com.nexastream.app.fragments.genre

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.databinding.FragmentGenreTvBinding
import com.nexastream.app.models.Category
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.setupFocusUpToTopNav
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

data class SubcategoryItem(
    val name: String,
    val categoryId: String
)

class SubcategoryAdapter(
    private val items: List<SubcategoryItem>,
    private val onSubcategorySelected: (SubcategoryItem) -> Unit
) : RecyclerView.Adapter<SubcategoryAdapter.ViewHolder>() {

    private var selectedPosition = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subcategory_chip_tv, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, position == selectedPosition)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_chip_name)

        fun bind(item: SubcategoryItem, isSelected: Boolean) {
            tvName.text = item.name
            itemView.isSelected = isSelected
            itemView.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && selectedPosition != currentPos) {
                    val previous = selectedPosition
                    selectedPosition = currentPos
                    notifyItemChanged(previous)
                    notifyItemChanged(selectedPosition)
                    onSubcategorySelected(item)
                }
            }
        }
    }
}

@AndroidEntryPoint
open class GenreTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentGenreTvBinding? = null
    protected val binding get() = _binding!!

    protected val args by lazy {
        try {
            navArgs<GenreTvFragmentArgs>().value
        } catch (_: Exception) {
            val defaultId = when (this) {
                is KidsTvFragment -> "kids"
                is AnimeTvFragment -> "anime"
                is ExploreTvFragment -> "all_content"
                else -> "all_content"
            }
            val defaultName = when (this) {
                is KidsTvFragment -> "Kids"
                is AnimeTvFragment -> "Anime"
                is ExploreTvFragment -> "Explore"
                else -> "Explore"
            }
            GenreTvFragmentArgs(id = defaultId, name = defaultName)
        }
    }
    protected val viewModel: GenreViewModel by viewModels()

    private val appAdapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val currentId = arguments?.getString("id")
        if (currentId.isNullOrBlank()) {
            val defaultId = when (this) {
                is KidsTvFragment -> "kids"
                is AnimeTvFragment -> "anime"
                is ExploreTvFragment -> "all_content"
                else -> ""
            }
            val defaultName = when (this) {
                is KidsTvFragment -> "Kids"
                is AnimeTvFragment -> "Anime"
                is ExploreTvFragment -> "Explore"
                else -> ""
            }
            if (defaultId.isNotEmpty()) {
                arguments = (arguments ?: Bundle()).apply {
                    putString("id", defaultId)
                    putString("name", defaultName)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGenreTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeGenre()
        setupSubcategories()
        setupBackToTopButton()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    GenreViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    GenreViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is GenreViewModel.State.SuccessLoading -> {
                        displayGenre(state.genre, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is GenreViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.getGenre()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.getGenre() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                    viewModel.getGenre()
                                }
                                btnIsLoadingRetry.requestFocus()
                            }
                        }
                    }
                }
            }
        }
    }

    open fun setupSubcategories() {
        // Overridden in child fragments that display subcategory chips (e.g. KidsTvFragment)
    }

    protected fun showSubcategories(items: List<SubcategoryItem>) {
        binding.tvGenreName.text = args.name.takeIf { it.isNotEmpty() } ?: "Kids"
        binding.tvGenreName.visibility = View.VISIBLE
        binding.hgvSubcategories.visibility = View.VISIBLE
        binding.hgvSubcategories.adapter = SubcategoryAdapter(items) { selected ->
            viewModel.loadGenreForId(selected.categoryId)
        }
    }

    private fun setupBackToTopButton() {
        binding.btnBackToTop.setOnClickListener {
            binding.vgvGenre.setSelectedPosition(0)
            binding.btnBackToTop.visibility = View.GONE
        }

        binding.vgvGenre.setOnChildViewHolderSelectedListener(object : androidx.leanback.widget.OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: RecyclerView,
                child: RecyclerView.ViewHolder?,
                position: Int,
                subposition: Int
            ) {
                super.onChildViewHolderSelected(parent, child, position, subposition)
                if (position > 1) {
                    if (binding.btnBackToTop.visibility != View.VISIBLE) {
                        binding.btnBackToTop.visibility = View.VISIBLE
                    }
                } else {
                    if (binding.btnBackToTop.visibility != View.GONE) {
                        binding.btnBackToTop.visibility = View.GONE
                    }
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun isHeroLayoutGenre(): Boolean {
        return args.id == "kids" || args.id == "anime" || this is KidsTvFragment || this is AnimeTvFragment
    }

    private fun initializeGenre() {
        appAdapter.onMovieClickListener = { movie ->
            findNavController().navigate(
                GenreTvFragmentDirections.actionGlobalMovie(id = movie.id)
            )
        }
        appAdapter.onTvShowClickListener = { tvShow ->
            findNavController().navigate(
                GenreTvFragmentDirections.actionGlobalTvShow(
                    id = tvShow.id,
                    poster = tvShow.poster,
                    banner = tvShow.banner
                )
            )
        }
        binding.vgvGenre.apply {
            setupFocusUpToTopNav(activity)
            setNumColumns(6)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(requireContext().resources.getDimension(R.dimen.genre_spacing).toInt())
        }
    }

    private fun displayGenre(genre: Genre, hasMore: Boolean) {
        val headerName = genre.name.takeIf { it.isNotEmpty() } ?: args.name

        val categories = genre.shows.filterIsInstance<Category>()
        if (categories.isNotEmpty()) {
            binding.tvGenreName.visibility = View.GONE
            binding.hgvSubcategories.visibility = View.GONE
            binding.vgvGenre.setNumColumns(1)

            categories.forEach { category ->
                category.itemSpacing = requireContext().resources.getDimension(R.dimen.genre_spacing).toInt()
                category.itemType = if (category.name == Category.FEATURED) {
                    AppAdapter.Type.CATEGORY_TV_COMPOSITE_HERO
                } else {
                    AppAdapter.Type.CATEGORY_TV_ITEM
                }
                category.list.forEach { show ->
                    when (show) {
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                }
            }
            appAdapter.submitList(categories)
        } else if (isHeroLayoutGenre() && binding.hgvSubcategories.visibility != View.VISIBLE) {
            binding.tvGenreName.visibility = View.GONE
            genre.shows.forEach { show ->
                when (show) {
                    is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            }
            val featuredList = genre.shows.take(8)
            val featuredCategory = Category(Category.FEATURED, featuredList).apply {
                itemType = AppAdapter.Type.CATEGORY_TV_COMPOSITE_HERO
            }
            val allCategory = Category("All $headerName", genre.shows).apply {
                itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
            binding.vgvGenre.setNumColumns(1)
            appAdapter.submitList(listOf(featuredCategory, allCategory))
        } else {
            if (binding.hgvSubcategories.visibility != View.VISIBLE) {
                binding.tvGenreName.visibility = View.VISIBLE
                binding.tvGenreName.text = if (headerName.contains("Movies and TV Shows", ignoreCase = true)) {
                    headerName
                } else {
                    getString(R.string.genre_header_name, headerName)
                }
            }

            val isSingleColumnList = args.id == "cdn_sports" || 
                    args.id == "upcoming_matches" || 
                    args.id == "cdn_all_channels" || 
                    args.id.startsWith("cdn_")

            binding.vgvGenre.setNumColumns(if (isSingleColumnList) 1 else 6)

            appAdapter.submitList(genre.shows.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
                    is Category -> {
                        it.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
                        it.itemSpacing = requireContext().resources.getDimension(R.dimen.genre_spacing).toInt()
                    }
                }
            })
        }

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}
