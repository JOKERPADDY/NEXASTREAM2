package com.nexastream.app.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.tanasi.navigation.widget.setupWithNavController
import com.nexastream.app.BuildConfig
import com.nexastream.app.R
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.ActivityMainTvBinding
import com.nexastream.app.databinding.ContentHeaderMenuMainTvBinding
import com.nexastream.app.fragments.player.PlayerTvFragment
import com.nexastream.app.ui.UpdateAppTvDialog
import com.nexastream.app.providers.IptvProvider
import com.nexastream.app.providers.Provider
import com.nexastream.app.providers.FilmyOnlineCcProvider
import com.nexastream.app.utils.AppLanguageManager
import com.nexastream.app.utils.ThemeManager
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.getCurrentFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainTvActivity : FragmentActivity() {

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()

    private var updateAppDialog: UpdateAppTvDialog? = null
    private var navController: NavController? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)
        
        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        binding.ivSplashOverlay.visibility = View.GONE

        try {
            val navHostFragment = this.supportFragmentManager
                .findFragmentById(binding.navMainFragment.id) as? NavHostFragment
            navController = navHostFragment?.navController

            adjustLayoutDelta(null, null)

            if (savedInstanceState == null) {
                if (UserPreferences.currentProvider == null) {
                    navController?.navigate(R.id.providers)
                } else if (navController?.currentDestination?.id != R.id.home) {
                    runCatching {
                        val navOptions = androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.providers, true)
                            .build()
                        navController?.navigate(R.id.home, null, navOptions)
                    }
                }
            }

            navController?.let { controller ->
                binding.navMain.setupWithNavController(controller)
                updateNavigationVisibility()
                handleIntent(intent)

                controller.addOnDestinationChangedListener { _, destination, _ ->
                    binding.navMain.headerView?.apply {
                        val header = ContentHeaderMenuMainTvBinding.bind(this)

                        try {
                            Glide.with(context)
                                .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() } ?: R.drawable.ic_provider_default_logo)
                                .error(R.drawable.ic_provider_default_logo)
                                .into(header.ivNavigationHeaderIcon)
                        } catch (_: Exception) {
                            header.ivNavigationHeaderIcon.setImageResource(R.drawable.ic_provider_default_logo)
                        }
                        
                        header.tvNavigationHeaderTitle.text = UserPreferences.currentProvider?.name
                        header.tvNavigationHeaderSubtitle.text = getString(R.string.main_menu_change_provider)
                        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
                        header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
                        header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
                        setBackgroundColor(palette.tvNavBackground)

                        setOnOpenListener {
                            header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                            header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                        }
                        setOnCloseListener {
                            header.tvNavigationHeaderTitle.visibility = View.GONE
                            header.tvNavigationHeaderSubtitle.visibility = View.GONE
                        }

                        setOnClickListener {
                            controller.navigate(R.id.providers)
                        }
                    }

                    when (destination.id) {
                        R.id.search, R.id.providers, R.id.home, R.id.movies, R.id.tv_shows, R.id.live_guide, R.id.downloads, R.id.settings -> {
                            binding.navMain.visibility = View.VISIBLE
                            updateNavigationVisibility()
                        }
                        else -> {
                            binding.navMain.visibility = View.GONE
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                binding.navMainFragment.isFocusedByDefault = true
            }
        } catch (e: Exception) {
            Log.e("MainTvActivity", "Error during onCreate", e)
            Toast.makeText(this, "A navigation error occurred. Resetting...", Toast.LENGTH_LONG).show()
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        if (state.isForceUpdate) {
                            showUpdateDialog(state)
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        dismissUpdateDialog()
                    }
                    MainViewModel.State.InstallingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        updateAppDialog?.isLoading = false
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            @androidx.media3.common.util.UnstableApi
            override fun handleOnBackPressed() {
                val controller = navController ?: return
                when (controller.currentDestination?.id) {
                    R.id.home -> {
                        if (binding.navMain.hasFocus()) finish() else binding.navMain.requestFocus()
                    }
                    R.id.settings, R.id.search, R.id.providers, R.id.movies, R.id.tv_shows, R.id.live_guide, R.id.downloads -> {
                        navigateToProviderHome(controller)
                        binding.navMain.requestFocus()
                    }
                    else -> {
                        val handled = (getCurrentFragment() as? PlayerTvFragment)?.onBackPressed() ?: false
                        if (!handled && !controller.navigateUp()) finish()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkUpdate()
    }

    private fun showUpdateDialog(state: MainViewModel.State.SuccessCheckingUpdate) {
        if (isFinishing || isDestroyed) return

        dismissUpdateDialog()
        updateAppDialog = UpdateAppTvDialog(this, state.newReleases, state.isForceUpdate).also { dialog ->
            dialog.setOnUpdateClickListener {
                if (!dialog.isLoading) {
                    viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                }
            }
            dialog.show()
        }
    }

    private fun dismissUpdateDialog() {
        updateAppDialog?.takeIf { it.isShowing }?.dismiss()
        updateAppDialog = null
    }

    override fun onDestroy() {
        dismissUpdateDialog()
        _binding = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val navController = (supportFragmentManager.findFragmentById(binding.navMainFragment.id) as NavHostFragment).navController

        when (intent.action) {
            Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(android.app.SearchManager.QUERY)
                val args = Bundle().apply { putString("query", query) }
                navController.navigate(R.id.search, args)
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "nexastream" && data.host == "resolve") {
                    val id = data.getQueryParameter("id")
                    val type = data.getQueryParameter("type")
                    val episodeId = data.getQueryParameter("episodeId")
                    if (id != null) {
                        when (type) {
                            "movie" -> navController.navigate(R.id.movie, Bundle().apply { putString("id", id) })
                            "tv_show" -> {
                                val args = Bundle().apply {
                                    putString("id", id)
                                    if (episodeId != null) {
                                        putString("episodeId", episodeId)
                                    }
                                }
                                navController.navigate(R.id.tv_show, args)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        binding.navMain.setBackgroundColor(palette.tvNavBackground)
        binding.navMain.headerView?.let { headerView ->
            headerView.setBackgroundColor(palette.tvNavBackground)
            val header = ContentHeaderMenuMainTvBinding.bind(headerView)
            header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
            header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
        }
    }
    
    private fun updateNavigationVisibility() {
        UserPreferences.currentProvider?.let { provider ->
            val isIptv = provider is IptvProvider
            binding.navMain.menu.findItem(R.id.search)?.isVisible = true
            binding.navMain.menu.findItem(R.id.movies)?.isVisible = Provider.supportsMovies(provider)
            val tvShowsItem = binding.navMain.menu.findItem(R.id.tv_shows)
            tvShowsItem?.isVisible = Provider.supportsTvShows(provider)
            tvShowsItem?.title = if (isIptv)
                getString(R.string.main_menu_all_channels) else getString(R.string.main_menu_tv_shows)

            binding.navMain.menu.findItem(R.id.downloads)?.isVisible = false
            binding.navMain.menu.findItem(R.id.live_guide)?.isVisible = isIptv
        }
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        val uDeltaX = deltaX ?: UserPreferences.paddingX
        val uDeltaY = deltaY ?: UserPreferences.paddingY
        binding.root.setPadding(uDeltaX, uDeltaY, uDeltaX, uDeltaY)
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.providers) {
                        inclusive = true
                    }
                }
            )
        }
    }
}
