package com.nexastream.app.activities.main

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.setupWithNavController
import com.nexastream.app.BuildConfig
import com.nexastream.app.R
import com.nexastream.app.activities.tools.BypassWebViewActivity
import com.nexastream.app.databinding.ActivityMainMobileBinding
import com.nexastream.app.fragments.home.HomeMobileFragment
import com.nexastream.app.fragments.player.PlayerMobileFragment
import com.nexastream.app.providers.FilmyOnlineCcProvider
import com.nexastream.app.providers.IptvProvider
import com.nexastream.app.providers.Provider
import com.nexastream.app.ui.UpdateAppMobileDialog
import com.nexastream.app.utils.AppLanguageManager
import com.nexastream.app.utils.ProviderChangeNotifier
import com.nexastream.app.utils.ThemeManager
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.getCurrentFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Base64
import kotlin.coroutines.resume
import javax.inject.Inject
import com.nexastream.app.utils.DownloadManager as AppDownloadManager

@AndroidEntryPoint
class MainMobileActivity : AppCompatActivity() {

    private companion object {
        const val RESOLVER_TIMEOUT_MS = 12_000L
    }

    private data class ResolverPayload(
        val url: String,
    )

    private var _binding: ActivityMainMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()
    private val resolverWebSocketClient by lazy { OkHttpClient() }
    private val bypassWebViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val wsUrl = pendingWs
            val token = pendingToken
            val cookies =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()

            clearResolverState()

            if (result.resultCode != Activity.RESULT_OK || wsUrl.isNullOrBlank() || token.isNullOrBlank()) {
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                sendWebSocketDone(wsUrl, token, cookies)
                showPostBypassCloseDialog()
            }
        }

    private var pendingWs: String? = null
    private var pendingToken: String? = null

    private var updateAppDialog: UpdateAppMobileDialog? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    @androidx.media3.common.util.UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))

        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar

        _binding = ActivityMainMobileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        setSupportActionBar(binding.toolbarMain)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbarMain.title = null
        title = ""

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment

            val isPlayer = currentFragment is PlayerMobileFragment
            val isHome = currentFragment is HomeMobileFragment
            val isBottomNavVisible = binding.bnvMain.visibility == View.VISIBLE

            val bottomPadding = if (isPlayer || isBottomNavVisible) 0 else insets.bottom
            val topPadding = if (isPlayer || isHome) 0 else insets.top

            view.setPadding(insets.left, topPadding, insets.right, bottomPadding)
            windowInsets
        }


        updateImmersiveMode()

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as NavHostFragment
        val navController = navHost.navController

        if (savedInstanceState == null) {
            UserPreferences.currentProvider?.let {
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

        viewModel.checkUpdate()

        binding.bnvMain.setupWithNavController(navController)
        updateNavigationVisibility()
        updateBottomNavigationVisibility(navController.currentDestination?.id)
        updateToolbarLogo()

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateNavigationVisibility(destination.id)
            updateBottomNavigationVisibility(destination.id)
            binding.ablMain.visibility = if (isTopLevelProviderDestination(destination.id) && destination.id != R.id.home && destination.id != R.id.search) View.VISIBLE else View.GONE
            updateToolbarLogo()

            val params = binding.navMainFragment.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (destination.id == R.id.home || destination.id == R.id.search) {
                binding.ablMain.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.toolbarMain.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.ablMain.elevation = 0f

                params.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            } else {
                val palette = ThemeManager.palette(UserPreferences.selectedTheme)
                binding.ablMain.setBackgroundColor(palette.systemBar)
                binding.toolbarMain.setBackgroundColor(palette.systemBar)

                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.topToBottom = binding.ablMain.id
            }
            binding.navMainFragment.layoutParams = params

            binding.mainContent.post { binding.mainContent.requestApplyInsets() }
        }

        lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect {
                    updateNavigationVisibility(navController.currentDestination?.id)
                    updateToolbarLogo()
                }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        showUpdateDialog(state)
                    }

                    MainViewModel.State.DownloadingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainMobileActivity, state.apk)
                        dismissUpdateDialog()
                    }

                    MainViewModel.State.InstallingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        updateAppDialog?.isLoading = false
                        // Silent failure for background check
                    }

                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val handled =
                    (getCurrentFragment() as? PlayerMobileFragment)?.onBackPressed() ?: false
                if (handled) return

                val currentDestinationId = navController.currentDestination?.id

                if (currentDestinationId == R.id.settings) {
                    navigateToProviderHome(navController)
                    return
                }

                if (UserPreferences.currentProvider != null && currentDestinationId == R.id.home) {
                    closeTask()
                    return
                }

                if (UserPreferences.currentProvider != null &&
                    isTopLevelProviderDestination(currentDestinationId)
                ) {
                    navigateToProviderHome(navController)
                    return
                }

                if (!navController.navigateUp()) finish()
            }
        })

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main_toolbar_mobile, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as NavHostFragment
        return when (item.itemId) {
            R.id.search -> {
                navHost.navController.navigate(R.id.search)
                true
            }
            R.id.change_provider -> {
                navHost.navController.navigate(R.id.providers)
                true
            }
            R.id.settings -> {
                navHost.navController.navigate(R.id.settings)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        dismissUpdateDialog()
        _binding = null
        super.onDestroy()
    }

    private fun clearResolverState() {
        pendingWs = null
        pendingToken = null
    }

    private fun showUpdateDialog(state: MainViewModel.State.SuccessCheckingUpdate) {
        if (isFinishing || isDestroyed) return

        dismissUpdateDialog()
        updateAppDialog = UpdateAppMobileDialog(this, state.newReleases, state.isForceUpdate).also { dialog ->
            dialog.setOnUpdateClickListener {
                if (!dialog.isLoading) {
                    viewModel.downloadUpdate(this@MainMobileActivity, state.asset)
                }
            }
            dialog.show()
        }
    }

    private fun dismissUpdateDialog() {
        updateAppDialog?.takeIf { it.isShowing }?.dismiss()
        updateAppDialog = null
    }

    private fun updateBottomNavigationVisibility(destinationId: Int?) {
        val showBottomNav =
            UserPreferences.currentProvider != null && isTopLevelProviderDestination(destinationId)
        binding.bnvMain.visibility = if (showBottomNav) View.VISIBLE else View.GONE
    }

    private fun updateNavigationVisibility(currentDestinationId: Int? = null) {
        val provider = UserPreferences.currentProvider ?: return
        val supportsMovies = Provider.supportsMovies(provider)
        val supportsTvShows = Provider.supportsTvShows(provider)
        val isIptv = provider is IptvProvider

        binding.bnvMain.menu.findItem(R.id.search)?.isVisible = true
        binding.bnvMain.menu.findItem(R.id.movies)?.isVisible = supportsMovies
        binding.bnvMain.menu.findItem(R.id.tv_shows)?.apply {
            isVisible = supportsTvShows
            title = if (isIptv) {
                getString(R.string.main_menu_all_channels)
            } else {
                getString(R.string.main_menu_tv_shows)
            }
        }

        binding.bnvMain.menu.findItem(R.id.downloads)?.isVisible = Provider.supportsDownloads(provider)
        binding.bnvMain.menu.findItem(R.id.live_guide)?.isVisible = isIptv

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val navController = navHost?.navController ?: return
        when {
            currentDestinationId == R.id.movies && !supportsMovies -> {
                navController.navigate(R.id.tv_shows)
            }

            currentDestinationId == R.id.tv_shows && !supportsTvShows -> {
                navController.navigate(R.id.home)
            }

            currentDestinationId == R.id.downloads && !Provider.supportsDownloads(provider) -> {
                navController.navigate(R.id.home)
            }

            currentDestinationId == R.id.live_guide && !isIptv -> {
                navController.navigate(R.id.home)
            }
        }
    }

    private fun isTopLevelProviderDestination(destinationId: Int?): Boolean {
        return destinationId in setOf(
            R.id.search,
            R.id.home,
            R.id.movies,
            R.id.tv_shows,
            R.id.downloads,
            R.id.live_guide,
            R.id.settings,
        )
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

    private fun closeTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finishAffinity()
        }
    }

    private suspend fun requestResolverPayload(wsUrl: String, token: String): ResolverPayload? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()

                    val socket =
                        resolverWebSocketClient.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.d("ResolverWS", "Connected -> requesting URL")
                                webSocket.send("resolve:$token")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                when {
                                    text.startsWith("payload:") -> {
                                        val payload = text.substringAfter("payload:").trim()
                                        val parsed = runCatching {
                                            val json = JSONObject(payload)
                                            ResolverPayload(
                                                url = json.optString("url"),
                                            )
                                        }.getOrNull()

                                        if (continuation.isActive) {
                                            continuation.resume(
                                                parsed?.takeUnless {
                                                    it.url.isBlank() || it.url.equals("null", ignoreCase = true)
                                                }
                                            )
                                        }
                                        webSocket.close(1000, null)
                                    }

                                    text.startsWith("url:") -> {
                                        val url = text.substringAfter("url:").trim()
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                url.takeUnless {
                                                    it.isEmpty() || it.equals("null", ignoreCase = true)
                                                }?.let { ResolverPayload(url = it) }
                                            )
                                        }
                                        webSocket.close(1000, null)
                                    }

                                    text.startsWith("error:") -> {
                                        Log.e("ResolverWS", "Resolver returned error: $text")
                                        if (continuation.isActive) {
                                            continuation.resume(null)
                                        }
                                        webSocket.close(1000, null)
                                    }
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                if (!continuation.isActive) {
                                    Log.d("ResolverWS", "WS resolve cancelled or timed out")
                                    return
                                }
                                Log.e("ResolverWS", "WS resolve failed", t)
                                continuation.resume(null)
                            }
                        })

                    continuation.invokeOnCancellation {
                        socket.cancel()
                    }
                }
            }
        }

    private suspend fun sendWebSocketDone(wsUrl: String, token: String, cookies: String?) {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()

                    val socket =
                        resolverWebSocketClient.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.d("ResolverWS", "Connected -> sending DONE")
                                val encodedCookies = cookies
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        Base64.getEncoder().encodeToString(
                                            it.toByteArray(Charsets.UTF_8)
                                        )
                                    }
                                val message = if (encodedCookies.isNullOrBlank()) {
                                    "done:$token"
                                } else {
                                    "done:$token:$encodedCookies"
                                }
                                webSocket.send(message)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text == "ack:$token" && continuation.isActive) {
                                    continuation.resume(Unit)
                                    webSocket.close(1000, null)
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                if (!continuation.isActive) {
                                    Log.d("ResolverWS", "WS done cancelled or timed out")
                                    return
                                }
                                Log.e("ResolverWS", "WS failed", t)
                                continuation.resume(Unit)
                            }
                        })

                    continuation.invokeOnCancellation {
                        socket.cancel()
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent): Boolean {
        val data = intent.data ?: return false

        if (data.scheme == "nexastream" && data.host == "resolve") {
            val ws = data.getQueryParameter("ws") ?: return false
            val token = data.getQueryParameter("token") ?: return false

            Log.d("ResolverWS", "WS: $ws")

            resolve(ws, token)
            return true
        }

        return false
    }

    private fun resolve(ws: String, token: String) {
        pendingWs = ws
        pendingToken = token

        lifecycleScope.launch {
            val payload = requestResolverPayload(ws, token)
            if (payload == null) {
                showResolverConnectionErrorDialog(ws, token)
                return@launch
            }

            bypassWebViewLauncher.launch(
                Intent(this@MainMobileActivity, BypassWebViewActivity::class.java)
                    .putExtra(BypassWebViewActivity.EXTRA_URL, payload.url)
            )
        }
    }

    private fun showResolverConnectionErrorDialog(ws: String, token: String) {
        if (isFinishing || isDestroyed) return

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage("Unable to reach the TV bypass websocket. Retry?")
            .setPositiveButton("Retry") { _, _ ->
                resolve(ws, token)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                clearResolverState()
            }
            .setOnCancelListener {
                clearResolverState()
            }
            .show()
    }

    private fun showPostBypassCloseDialog() {
        if (isFinishing || isDestroyed) return

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage("Bypass completed. Do you want to close the app?")
            .setPositiveButton("Close app") { _, _ ->
                closeTask()
            }
            .setNegativeButton("Keep open", null)
            .setOnCancelListener(null)
            .show()
    }

    @androidx.media3.common.util.UnstableApi
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        (getCurrentFragment() as? PlayerMobileFragment)?.onUserLeaveHint()
    }

    fun updateImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (UserPreferences.immersiveMode) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateToolbarLogo() {
        binding.ivToolbarLogo.visibility = View.GONE
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        val navColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(
                palette.mobileNavActive,
                palette.mobileNavInactive,
            )
        )

        binding.bnvMain.setBackgroundColor(palette.mobileNavBackground)
        binding.bnvMain.itemIconTintList = navColors
        binding.bnvMain.itemTextColor = navColors

        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
