@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nexastream.app.utils

import androidx.annotation.OptIn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import com.nexastream.app.NexastreamApp
import com.nexastream.app.BuildConfig
import com.nexastream.app.R
import com.nexastream.app.fragments.player.settings.PlayerSettingsView
import com.nexastream.app.providers.Provider
import com.nexastream.app.providers.Provider.Companion.providers
import com.nexastream.app.providers.TmdbProvider
import com.nexastream.app.providers.NexaHomeProvider
import androidx.core.content.edit
import com.nexastream.app.database.AppDatabase
import org.json.JSONObject

object UserPreferences {

    private const val TAG = "UserPrefsDebug"

    private lateinit var prefs: SharedPreferences

    // Default DoH Provider URL (Cloudflare)
    private const val DEFAULT_DOH_PROVIDER_URL = "https://cloudflare-dns.com/dns-query"
    const val DOH_DISABLED_VALUE = "" // Value to represent DoH being disabled
    private const val DEFAULT_SERIENSTREAM_DOMAIN = "s.to"
    private const val DEFAULT_MOFLIX_DOMAIN = "moflix-stream.xyz"
    private const val DEFAULT_STREAMINGCOMMUNITY_DOMAIN = "streamingunity.dog"
    private const val DEFAULT_CUEVANA_DOMAIN = "cuevana.gs"
    private const val DEFAULT_POSEIDON_DOMAIN = "www.poseidonhd2.co"

    const val PROVIDER_URL = "URL"
    const val PROVIDER_LOGO = "LOGO"
    const val PROVIDER_PORTAL_URL = "PORTAL_URL"
    const val PROVIDER_AUTOUPDATE = "AUTOUPDATE_URL"
    const val PROVIDER_NEW_INTERFACE = "NEW_INTERFACE"
    const val PROVIDER_PREFERRED_SERVER = "PREFERRED_SERVER"

    lateinit var providerCache: JSONObject

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message())
        }
    }

    fun setup(context: Context) {
        if (::prefs.isInitialized) return
        val prefsName = "${BuildConfig.APPLICATION_ID}.preferences"
        prefs = context.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE,
        )
        debugLog { "prefs initialized: ${prefs.hashCode()}" }

        val jsonString = Key.PROVIDER_CACHE.getString() ?: "{}"
        providerCache = runCatching { JSONObject(jsonString) }.getOrDefault(JSONObject())
        
        // Force home provider as default provider every time app opens
        Key.CURRENT_PROVIDER.setString(NexaHomeProvider.name)
        cachedProvider = NexaHomeProvider
    }

    @Volatile
    private var cachedProvider: Provider? = null

    var currentProvider: Provider?
        get() {
            cachedProvider?.let { return it }
            val providerName = Key.CURRENT_PROVIDER.getString() ?: run {
                cachedProvider = NexaHomeProvider
                return NexaHomeProvider
            }
            val resolved = when {
                providerName.startsWith("TMDb (") && providerName.endsWith(")") -> {
                    val lang = providerName.substringAfter("TMDb (").substringBefore(")")
                    TmdbProvider(lang)
                }
                providerName == "HOME" -> NexaHomeProvider
                else -> Provider.providers.keys.find { it.name == providerName } ?: NexaHomeProvider
            }
            cachedProvider = resolved
            return resolved
        }
        set(value) {
            setCurrentProvider(value, notify = true)
        }

    fun setCurrentProvider(value: Provider?, notify: Boolean) {
        if (value?.name == currentProvider?.name) return
        cachedProvider = value
        Key.CURRENT_PROVIDER.setString(value?.name)
        runCatching {
            ArtworkRepairScheduler.schedule(NexastreamApp.instance, value)
        }

        if (notify) {
            // Notify all ViewModels that the provider has changed
            ProviderChangeNotifier.notifyProviderChanged()
        }
    }

    fun getProviderCache(provider: Provider, key: String): String {
        return providerCache
            .optJSONObject(provider.name)
            ?.optString(key)
            .orEmpty()
    }

    fun setProviderCache(provider: Provider?, key: String, value: String) {
        val providerName = provider?.name ?: currentProvider?.name ?: return
        val innerJson = providerCache.optJSONObject(providerName)
            ?: JSONObject().also { providerCache.put(providerName, it) }
        
        val oldValue = innerJson.optString(key)
        if (oldValue == value) return

        innerJson.put(key, value)
        Key.PROVIDER_CACHE.setString(providerCache.toString())

        if (key == PROVIDER_LOGO && providerName == currentProvider?.name) {
            ProviderChangeNotifier.notifyProviderChanged()
        }
    }

    fun clearProviderCache(providerName: String) {
        if (providerCache.has(providerName)) {
            debugLog { "CACHE: removing stored data for $providerName" }
            providerCache.remove(providerName)
            Key.PROVIDER_CACHE.setString(providerCache.toString())
        }
    }

    var currentLanguage: String?
        get() = Key.CURRENT_LANGUAGE.getString()
        set(value) = Key.CURRENT_LANGUAGE.setString(value)

    var providerLanguage: String?
        get() = Key.PROVIDER_LANGUAGE.getString()
        set(value) = Key.PROVIDER_LANGUAGE.setString(value)

    var captionTextSize: Float
        get() = Key.CAPTION_TEXT_SIZE.getFloat()
            ?: PlayerSettingsView.Settings.Subtitle.Style.TextSize.DEFAULT.value
        set(value) {
            Key.CAPTION_TEXT_SIZE.setFloat(value)
        }

    var autoplay: Boolean
        get() = Key.AUTOPLAY.getBoolean() ?: true
        set(value) {
            Key.AUTOPLAY.setBoolean(value)
        }

    var autoPlayTrailers: Boolean
        get() = Key.AUTOPLAY_TRAILERS.getBoolean() ?: true
        set(value) {
            Key.AUTOPLAY_TRAILERS.setBoolean(value)
        }

    var keepScreenOnWhenPaused: Boolean
        get() = Key.KEEP_SCREEN_ON_WHEN_PAUSED.getBoolean() ?: false
        set(value) {
            Key.KEEP_SCREEN_ON_WHEN_PAUSED.setBoolean(value)
        }

    var playerGestures: Boolean
        get() = Key.PLAYER_GESTURES.getBoolean() ?: true
        set(value) {
            Key.PLAYER_GESTURES.setBoolean(value)
        }

    var immersiveMode: Boolean
        get() = Key.IMMERSIVE_MODE.getBoolean() ?: false // Default changed to false
        set(value) {
            Key.IMMERSIVE_MODE.setBoolean(value)
        }

    var forceExtraBuffering: Boolean
        get() = Key.FORCE_EXTRA_BUFFERING.getBoolean() ?: false
        set(value) {
            Key.FORCE_EXTRA_BUFFERING.setBoolean(value)
        }

    var autoplayBuffer: Long
        get() = Key.AUTOPLAY_BUFFER.getLong() ?: 3L
        set(value) {
            Key.AUTOPLAY_BUFFER.setLong(value)
        }

    var serverAutoSubtitlesDisabled: Boolean
        get() = Key.SERVER_AUTO_SUBTITLES_DISABLED.getBoolean() ?: true
        set(value) {
            Key.SERVER_AUTO_SUBTITLES_DISABLED.setBoolean(value)
        }

    var selectedTheme: String
        get() = Key.SELECTED_THEME.getString() ?: "default"
        set(value) = Key.SELECTED_THEME.setString(value)

    var tmdbApiKey: String
        get() = Key.TMDB_API_KEY.getString() ?: ""
        set(value) {
            Key.TMDB_API_KEY.setString(value)
            TMDb3.rebuildService()
        }
    var enableTmdb: Boolean
        get() = Key.ENABLE_TMDB.getBoolean() ?: true
        set(value) {
            Key.ENABLE_TMDB.setBoolean(value)
            TMDb3.rebuildService()
            if (value) {
                runCatching {
                    ArtworkRepairScheduler.schedule(NexastreamApp.instance, currentProvider)
                }
            }
        }

    var parentalControlPin: String
        get() = Key.PARENTAL_CONTROL_PIN.getString() ?: ""
        set(value) {
            Key.PARENTAL_CONTROL_PIN.setString(value.trim())
        }

    var parentalControlAdminPin: String
        get() = Key.PARENTAL_CONTROL_ADMIN_PIN.getString() ?: ""
        set(value) {
            Key.PARENTAL_CONTROL_ADMIN_PIN.setString(value.trim())
        }

    var parentalControlMaxAge: Int?
        get() = Key.PARENTAL_CONTROL_MAX_AGE.getInt()
        set(value) {
            Key.PARENTAL_CONTROL_MAX_AGE.setInt(value)
        }

    var parentalControlFailedAttempts: Int
        get() = Key.PARENTAL_CONTROL_FAILED_ATTEMPTS.getInt() ?: 0
        set(value) {
            Key.PARENTAL_CONTROL_FAILED_ATTEMPTS.setInt(value)
        }

    var parentalControlLockedUntilMillis: Long
        get() = Key.PARENTAL_CONTROL_LOCKED_UNTIL.getLong() ?: 0L
        set(value) {
            Key.PARENTAL_CONTROL_LOCKED_UNTIL.setLong(value)
        }

    var parentalControlHardLocked: Boolean
        get() = Key.PARENTAL_CONTROL_HARD_LOCKED.getBoolean() ?: false
        set(value) {
            Key.PARENTAL_CONTROL_HARD_LOCKED.setBoolean(value)
        }

    val isParentalControlActive: Boolean
        get() = enableTmdb && parentalControlPin.isNotBlank() && parentalControlMaxAge != null

    val isParentalControlTemporarilyLocked: Boolean
        get() = parentalControlLockedUntilMillis > System.currentTimeMillis()

    val parentalControlLockRemainingMillis: Long
        get() = (parentalControlLockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

    fun registerParentalPinSuccess() {
        parentalControlFailedAttempts = 0
        parentalControlLockedUntilMillis = 0L
        parentalControlHardLocked = false
    }

    fun registerParentalPinFailure(nowMillis: Long = System.currentTimeMillis()) {
        val attempts = parentalControlFailedAttempts + 1
        parentalControlFailedAttempts = attempts

        when {
            attempts >= 7 && parentalControlAdminPin.isNotBlank() -> {
                parentalControlHardLocked = true
                parentalControlLockedUntilMillis = 0L
            }
            attempts >= 7 -> {
                parentalControlLockedUntilMillis = nowMillis + 24L * 60L * 60L * 1000L
            }
            attempts >= 5 -> {
                parentalControlLockedUntilMillis = nowMillis + 30L * 60L * 1000L
            }
            attempts >= 3 -> {
                parentalControlLockedUntilMillis = nowMillis + 5L * 60L * 1000L
            }
        }
    }

    var updateCheckEnabled: Boolean
        get() = Key.UPDATE_CHECK_ENABLED.getBoolean() ?: true
        set(value) {
            Key.UPDATE_CHECK_ENABLED.setBoolean(value)
        }

    fun unlockParentalControls() {
        parentalControlFailedAttempts = 0
        parentalControlLockedUntilMillis = 0L
        parentalControlHardLocked = false
    }

    var subdlApiKey: String
        get() = Key.SUBDL_API_KEY.getString() ?: ""
        set(value) {
            Key.SUBDL_API_KEY.setString(value)
        }

    var autoDownloadSubtitles: Boolean
        get() = Key.AUTO_DOWNLOAD_SUBTITLES.getBoolean() ?: true
        set(value) {
            Key.AUTO_DOWNLOAD_SUBTITLES.setBoolean(value)
        }

    var bypassWsAdvertisedHost: String
        get() = Key.BYPASS_WS_ADVERTISED_HOST.getString() ?: ""
        set(value) {
            Key.BYPASS_WS_ADVERTISED_HOST.setString(value.trim())
        }

    var realDebridToken: String
        get() = Key.REAL_DEBRID_TOKEN.getString() ?: ""
        set(value) {
            Key.REAL_DEBRID_TOKEN.setString(value.trim())
        }

    var traktToken: String
        get() = Key.TRAKT_TOKEN.getString() ?: ""
        set(value) {
            Key.TRAKT_TOKEN.setString(value.trim())
        }

    var currentProfileId: Int
        get() = Key.CURRENT_PROFILE_ID.getInt() ?: 0
        set(value) {
            AppDatabase.resetInstance()
            Key.CURRENT_PROFILE_ID.setInt(value)
        }

    enum class PlayerResize(
        val stringRes: Int,
        val resizeMode: Int,
    ) {
        Fit(R.string.player_aspect_ratio_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        Fill(R.string.player_aspect_ratio_fill, AspectRatioFrameLayout.RESIZE_MODE_FILL),
        Zoom(R.string.player_aspect_ratio_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        Stretch43(R.string.player_aspect_ratio_zoom_4_3, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        StretchVertical(R.string.player_aspect_ratio_stretch_vertical, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        SuperZoom(R.string.player_aspect_ratio_super_zoom, AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    var playerResize: PlayerResize
        get() = PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() && it.name == Key.PLAYER_RESIZE_NAME.getString() }
            ?: PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() }
            ?: PlayerResize.Fit
        set(value) {
            Key.PLAYER_RESIZE.setInt(value.resizeMode)
            Key.PLAYER_RESIZE_NAME.setString(value.name)
        }

    var captionStyle: CaptionStyleCompat
        get() = CaptionStyleCompat(
            Key.CAPTION_STYLE_FONT_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.foregroundColor,
            Key.CAPTION_STYLE_BACKGROUND_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.backgroundColor,
            Key.CAPTION_STYLE_WINDOW_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.windowColor,
            Key.CAPTION_STYLE_EDGE_TYPE.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeType,
            Key.CAPTION_STYLE_EDGE_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeColor,
            PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.typeface
        )
        set(value) {
            Key.CAPTION_STYLE_FONT_COLOR.setInt(value.foregroundColor)
            Key.CAPTION_STYLE_BACKGROUND_COLOR.setInt(value.backgroundColor)
            Key.CAPTION_STYLE_WINDOW_COLOR.setInt(value.windowColor)
            Key.CAPTION_STYLE_EDGE_TYPE.setInt(value.edgeType)
            Key.CAPTION_STYLE_EDGE_COLOR.setInt(value.edgeColor)
        }

    var captionMargin: Int
        get() = Key.CAPTION_STYLE_MARGIN.getInt() ?: 24
        set(value) {
            Key.CAPTION_STYLE_MARGIN.setInt(value)
        }

    var qualityHeight: Int?
        get() = Key.QUALITY_HEIGHT.getInt()
        set(value) {
            Key.QUALITY_HEIGHT.setInt(value)
        }

    var downloadWifiOnly: Boolean
        get() = Key.DOWNLOAD_WIFI_ONLY.getBoolean() ?: true
        set(value) {
            Key.DOWNLOAD_WIFI_ONLY.setBoolean(value)
        }

    var maxParallelDownloads: Int
        get() = Key.MAX_PARALLEL_DOWNLOADS.getInt() ?: 3
        set(value) {
            Key.MAX_PARALLEL_DOWNLOADS.setInt(value)
        }

    var preferredDownloadQuality: String?
        get() = Key.PREFERRED_DOWNLOAD_QUALITY.getString()
        set(value) = Key.PREFERRED_DOWNLOAD_QUALITY.setString(value)

    var subtitleName: String?
        get() = Key.SUBTITLE_NAME.getString()
        set(value) = Key.SUBTITLE_NAME.setString(value)

    var subtitleOffset: Long
        get() = Key.SUBTITLE_OFFSET.getLong() ?: 0L
        set(value) = Key.SUBTITLE_OFFSET.setLong(value)

    var streamingcommunityDomain: String
        get() {
            if (!::prefs.isInitialized) {
                Log.e(TAG, "streamingcommunityDomain GET: prefs is not initialized")
                return DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            }
            val storedValue = prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) {
                DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            } else {
                storedValue
            }
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) {
                Log.e(TAG, "streamingcommunityDomain SET: prefs is not initialized")
                return
            }

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("StreamingCommunity")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.STREAMINGCOMMUNITY_DOMAIN.name)
                } else {
                    putString(Key.STREAMINGCOMMUNITY_DOMAIN.name, value)
                }
                apply()
            }
        }

    var serienstreamDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_SERIENSTREAM_DOMAIN
            val storedValue = prefs.getString(Key.SERIENSTREAM_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_SERIENSTREAM_DOMAIN else storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.SERIENSTREAM_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("SerienStream")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.SERIENSTREAM_DOMAIN.name)
                } else {
                    putString(Key.SERIENSTREAM_DOMAIN.name, value)
                }
                apply()
            }
        }

    var cuevanaDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_CUEVANA_DOMAIN
            val storedValue = prefs.getString(Key.CUEVANA_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_CUEVANA_DOMAIN else storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.CUEVANA_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("Cuevana 3")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.CUEVANA_DOMAIN.name)
                } else {
                    putString(Key.CUEVANA_DOMAIN.name, value)
                }
                apply()
            }
        }

    var poseidonDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_POSEIDON_DOMAIN
            val storedValue = prefs.getString(Key.POSEIDON_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_POSEIDON_DOMAIN else storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.POSEIDON_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("Poseidonhd2")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.POSEIDON_DOMAIN.name)
                } else {
                    putString(Key.POSEIDON_DOMAIN.name, value)
                }
                apply()
            }
        }

    var moflixDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_MOFLIX_DOMAIN
            val storedValue = prefs.getString(Key.MOFLIX_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_MOFLIX_DOMAIN else storedValue
        }
        set(value) {
            if (!::prefs.isInitialized) return

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.MOFLIX_DOMAIN.name)
                } else {
                    putString(Key.MOFLIX_DOMAIN.name, value)
                }
                apply()
            }
        }

    var dohProviderUrl: String
        get() = Key.DOH_PROVIDER_URL.getString() ?: DEFAULT_DOH_PROVIDER_URL
        set(value) {
            Key.DOH_PROVIDER_URL.setString(value)
            DnsResolver.setDnsUrl(value)
        }

    var paddingX: Int
        get() = Key.SCREEN_PADDING_X.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_X.setInt(value)

    var paddingY: Int
        get() = Key.SCREEN_PADDING_Y.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_Y.setInt(value)

    var favoriteProviders: Set<String>
        get() = Key.FAVORITE_PROVIDERS.getStringSet() ?: emptySet()
        set(value) {
            Key.FAVORITE_PROVIDERS.setStringSet(value)
        }

    var preferredServerName: String?
        get() = Key.PREFERRED_SERVER_NAME.getString()
        set(value) = Key.PREFERRED_SERVER_NAME.setString(value)

    var lastUpdateCheckMillis: Long
        get() = Key.LAST_UPDATE_CHECK_MILLIS.getLong() ?: 0L
        set(value) = Key.LAST_UPDATE_CHECK_MILLIS.setLong(value)

    var forceTvUi: Boolean
        get() = Key.FORCE_TV_UI.getBoolean() ?: false
        set(value) {
            Key.FORCE_TV_UI.setBoolean(value)
        }

    var remoteControlTvIp: String?
        get() = Key.REMOTE_CONTROL_TV_IP.getString()
        set(value) {
            Key.REMOTE_CONTROL_TV_IP.setString(value)
        }

    var remoteControlTvId: String?
        get() = Key.REMOTE_CONTROL_TV_ID.getString()
        set(value) {
            Key.REMOTE_CONTROL_TV_ID.setString(value)
        }

    var loginToken: String?
        get() = Key.LOGIN_TOKEN.getString()
        set(value) = Key.LOGIN_TOKEN.setString(value)

    var selectedRegion: String
        get() = Key.SELECTED_REGION.getString() ?: "US"
        set(value) = Key.SELECTED_REGION.setString(value)

    var secondarySubtitle: String?
        get() = Key.SECONDARY_SUBTITLE.getString()
        set(value) = Key.SECONDARY_SUBTITLE.setString(value)

    var bilingualSubtitles: Boolean
        get() = Key.BILINGUAL_SUBTITLES.getBoolean() ?: false
        set(value) = Key.BILINGUAL_SUBTITLES.setBoolean(value)

    var familyMode: Boolean
        get() = Key.FAMILY_MODE.getBoolean() ?: false
        set(value) = Key.FAMILY_MODE.setBoolean(value)

    var autoStartOnBoot: Boolean
        get() = Key.AUTO_START_ON_BOOT.getBoolean() ?: false
        set(value) = Key.AUTO_START_ON_BOOT.setBoolean(value)

    private enum class Key {
        APP_LAYOUT,
        FORCE_TV_UI,
        REMOTE_CONTROL_TV_IP,
        REMOTE_CONTROL_TV_ID,
        LOGIN_TOKEN,
        SELECTED_REGION,
        SECONDARY_SUBTITLE,
        BILINGUAL_SUBTITLES,
        FAMILY_MODE,
        AUTO_START_ON_BOOT,
        CURRENT_LANGUAGE,
        CURRENT_PROVIDER,
        PLAYER_RESIZE,
        PLAYER_RESIZE_NAME,
        CAPTION_TEXT_SIZE,
        CAPTION_STYLE_FONT_COLOR,
        CAPTION_STYLE_BACKGROUND_COLOR,
        CAPTION_STYLE_WINDOW_COLOR,
        CAPTION_STYLE_EDGE_TYPE,
        CAPTION_STYLE_EDGE_COLOR,
        CAPTION_STYLE_MARGIN,
        SCREEN_PADDING_X,
        SCREEN_PADDING_Y,
        QUALITY_HEIGHT,
        SUBTITLE_NAME,
        SUBTITLE_OFFSET,
        SERIENSTREAM_DOMAIN,
        MOFLIX_DOMAIN,
        STREAMINGCOMMUNITY_DOMAIN,
        CUEVANA_DOMAIN,
        POSEIDON_DOMAIN,
        DOH_PROVIDER_URL, // Removed STREAMINGCOMMUNITY_DNS_OVER_HTTPS, added DOH_PROVIDER_URL
        AUTOPLAY,
        AUTOPLAY_TRAILERS,
        PROVIDER_CACHE,
        KEEP_SCREEN_ON_WHEN_PAUSED,
        PLAYER_GESTURES,
        IMMERSIVE_MODE,
        TMDB_API_KEY,
        SUBDL_API_KEY,
        AUTO_DOWNLOAD_SUBTITLES,
        FORCE_EXTRA_BUFFERING,
        AUTOPLAY_BUFFER,
        SERVER_AUTO_SUBTITLES_DISABLED,
        ENABLE_TMDB,
        PARENTAL_CONTROL_PIN,
        PARENTAL_CONTROL_ADMIN_PIN,
        PARENTAL_CONTROL_MAX_AGE,
        PARENTAL_CONTROL_FAILED_ATTEMPTS,
        PARENTAL_CONTROL_LOCKED_UNTIL,
        PARENTAL_CONTROL_HARD_LOCKED,
        SELECTED_THEME,
        BYPASS_WS_ADVERTISED_HOST,
        REAL_DEBRID_TOKEN,
        TRAKT_TOKEN,
        CURRENT_PROFILE_ID,
        UPDATE_CHECK_ENABLED,
        PROVIDER_LANGUAGE,
        FAVORITE_PROVIDERS,
        PREFERRED_SERVER_NAME,
        LAST_UPDATE_CHECK_MILLIS,
        DOWNLOAD_WIFI_ONLY,
        MAX_PARALLEL_DOWNLOADS,
        PREFERRED_DOWNLOAD_QUALITY;

        fun getStringSet(): Set<String>? = if (::prefs.isInitialized) {
            when {
                prefs.contains(name) -> prefs.getStringSet(name, null)
                else -> null
            }
        } else null

        fun setStringSet(value: Set<String>?) = value?.let {
            if (::prefs.isInitialized) {
                with(prefs.edit()) {
                    putStringSet(name, value)
                    apply()
                }
            }
        } ?: remove()

        fun getBoolean(): Boolean? = if (::prefs.isInitialized) {
            when {
                prefs.contains(name) -> prefs.getBoolean(name, false)
                else -> null
            }
        } else null

        fun getFloat(): Float? = if (::prefs.isInitialized) {
            when {
                prefs.contains(name) -> prefs.getFloat(name, 0F)
                else -> null
            }
        } else null

        fun getInt(): Int? = if (::prefs.isInitialized) {
            when {
                prefs.contains(name) -> prefs.getInt(name, 0)
                else -> null
            }
        } else null

        fun getLong(): Long? = if (::prefs.isInitialized) {
            when {
                prefs.contains(name) -> prefs.getLong(name, 0)
                else -> null
            }
        } else null

        fun getString(): String? = if (::prefs.isInitialized) {
            when {
                prefs.contains(name) -> prefs.getString(name, null)
                else -> null
            }
        } else null

        fun setBoolean(value: Boolean?) = value?.let {
            if (::prefs.isInitialized) {
                with(prefs.edit()) {
                    putBoolean(name, value)
                    apply()
                }
            }
        } ?: remove()

        fun setFloat(value: Float?) = value?.let {
            if (::prefs.isInitialized) {
                with(prefs.edit()) {
                    putFloat(name, value)
                    apply()
                }
            }
        } ?: remove()

        fun setInt(value: Int?) = value?.let {
            if (::prefs.isInitialized) {
                with(prefs.edit()) {
                    putInt(name, value)
                    apply()
                }
            }
        } ?: remove()

        fun setLong(value: Long?) = value?.let {
            if (::prefs.isInitialized) {
                with(prefs.edit()) {
                    putLong(name, value)
                    apply()
                }
            }
        } ?: remove()

        fun setString(value: String?) = value?.let {
            if (::prefs.isInitialized) {
                with(prefs.edit()) {
                    putString(name, value)
                    apply()
                }
            }
        } ?: remove()

        fun remove() {
            if (::prefs.isInitialized) {
                with(prefs.edit()) {
                    remove(name)
                    apply()
                }
            }
        }
    }
}
