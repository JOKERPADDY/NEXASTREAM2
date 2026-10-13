package com.nexastream.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.nexastream.app.BuildConfig
import com.nexastream.app.models.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

object InAppUpdater {

    private const val TAG = "InAppUpdater"

    // Primary & Fallback Update Configuration URLs
    private val UPDATE_CONFIG_URLS = listOf(
        "https://nexastream-73801.web.app/update.json",
        "https://raw.githubusercontent.com/JOKERPADDY/NEXASTREAM2/main/update.json",
        "https://raw.githubusercontent.com/THWAYNESHOP/NEXASTREAM2/main/update.json"
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val gson by lazy { Gson() }

    private data class Version(val name: String) : Comparable<Version> {
        override operator fun compareTo(other: Version): Int {
            val cleanThis = this.name.removePrefix("v").removePrefix("V").trim()
            val cleanOther = other.name.removePrefix("v").removePrefix("V").trim()
            val thisParts = cleanThis.split(".").toTypedArray()
            val thatParts = cleanOther.split(".").toTypedArray()
            for (i in 0 until max(thisParts.size, thatParts.size)) {
                val thisPart = thisParts.getOrNull(i)?.toIntOrNull() ?: 0
                val thatPart = thatParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            return 0
        }
    }

    suspend fun getRemoteUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        for (configUrl in UPDATE_CONFIG_URLS) {
            try {
                Log.d(TAG, "Checking for update at: $configUrl")
                val request = Request.Builder()
                    .url(configUrl)
                    .header("User-Agent", "NexaStream-App/${BuildConfig.VERSION_NAME}")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: continue
                    val updateInfo = gson.fromJson(bodyString, UpdateInfo::class.java)

                    if (updateInfo != null && isUpdateAvailable(updateInfo)) {
                        Log.i(TAG, "New update available: v${updateInfo.versionName} (Code: ${updateInfo.versionCode})")
                        return@withContext updateInfo
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check update from $configUrl: ${e.message}")
            }
        }

        // Fallback to GitHub API Releases if update.json check fails
        return@withContext tryFallbackGitHubRelease()
    }

    private fun isUpdateAvailable(info: UpdateInfo): Boolean {
        val hasNewCode = info.versionCode > BuildConfig.VERSION_CODE
        val hasNewVersionName = Version(info.versionName) > Version(BuildConfig.VERSION_NAME)
        return (hasNewCode || hasNewVersionName) && info.apkUrl.isNotBlank()
    }

    private suspend fun tryFallbackGitHubRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        val repos = listOf(
            Pair("JOKERPADDY", "NEXASTREAM2"),
            Pair("THWAYNESHOP", "NEXASTREAM2")
        )
        for ((owner, repo) in repos) {
            try {
                val release = GitHub.Releases.getLatestRelease(owner, repo)
                val releaseVersion = release.tagName.removePrefix("v").removePrefix("V").trim()
                if (Version(releaseVersion) > Version(BuildConfig.VERSION_NAME)) {
                    val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    if (apkAsset != null) {
                        return@withContext UpdateInfo(
                            versionCode = BuildConfig.VERSION_CODE + 1,
                            versionName = releaseVersion,
                            apkUrl = apkAsset.browserDownloadUrl,
                            changelog = release.body ?: "New release available: v$releaseVersion",
                            forceUpdate = false,
                            supportTelegramUrl = "https://t.me/NEXASTREAM2"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallback GitHub release check failed for $owner/$repo: ${e.message}")
            }
        }
        return@withContext null
    }

    suspend fun downloadApk(context: Context, downloadUrl: String, onProgress: ((Int) -> Unit)? = null): File = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.delete() }

        val apkFile = File.createTempFile("NexaStream-Update-", ".apk", context.cacheDir)

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "NexaStream-App/${BuildConfig.VERSION_NAME}")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to download update APK: HTTP ${response.code}")
        }

        val body = response.body ?: throw IllegalStateException("Empty response body when downloading APK")
        val contentLength = body.contentLength()

        body.byteStream().use { input ->
            FileOutputStream(apkFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0 && onProgress != null) {
                        val percent = ((totalBytesRead * 100) / contentLength).toInt()
                        onProgress(percent)
                    }
                }
                output.flush()
            }
        }

        Log.i(TAG, "Downloaded update APK successfully (${apkFile.length()} bytes)")
        return@withContext apkFile
    }

    fun installApk(context: Context, uri: Uri) {
        val file = File(uri.path ?: return)
        if (!file.exists()) {
            Log.e(TAG, "Cannot install APK: File does not exist at ${file.absolutePath}")
            return
        }

        // On Android 8.0+ (API 26+), verify install permission before launching installer
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                android.widget.Toast.makeText(
                    context,
                    "Please allow installing unknown apps, then return and click Update again",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                val settingsIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start installer intent: ${e.message}", e)
            android.widget.Toast.makeText(context, "Failed to launch installer: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
