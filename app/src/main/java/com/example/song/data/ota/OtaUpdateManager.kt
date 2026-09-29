package com.example.song.data.ota

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.shivaansh.pulseplayer.BuildConfig
import com.example.song.data.api.GitHubAsset
import com.example.song.data.api.GitHubRelease
import com.example.song.data.api.GitHubReleaseService
import com.example.song.util.SemVer
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: GitHubRelease, val apkAsset: GitHubAsset) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
    object Throttled : UpdateCheckResult()
}

sealed interface InstallResult {
    object Success : InstallResult
    object CorruptFile : InstallResult
    object PermissionOrSecurityBlocked : InstallResult
    object InstallerNotFound : InstallResult
    data class Error(val message: String) : InstallResult
}

data class DownloadProgress(
    val downloadId: Long,
    val status: Int, // DownloadManager.STATUS_*
    val bytesDownloaded: Long,
    val totalBytes: Long
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes.toFloat() else 0f
}

data class PostUpdateResult(
    val isPostUpdate: Boolean,
    val changelog: String?
)

class OtaUpdateManager(
    private val context: Context,
    private val preferences: OtaUpdatePreferences = OtaUpdatePreferences(context),
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO
) {

    companion object {
        private const val TAG = "OtaUpdateManager"
        const val DEFAULT_OWNER = "Shivaanshe"
        const val DEFAULT_REPO = "PulsePlayer-Android"
        const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val retrofitService: GitHubReleaseService by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubReleaseService::class.java)
    }

    private val downloadManager: DownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /**
     * Checks GitHub for the latest release.
     * @param force If true, bypasses the rate limiting cache check.
     */
    suspend fun checkForUpdate(
        force: Boolean = false,
        currentVersionName: String = BuildConfig.VERSION_NAME
    ): UpdateCheckResult {
        val now = System.currentTimeMillis()
        if (!force) {
            val lastCheck = preferences.lastCheckTimestamp.first()
            if (now - lastCheck < AUTO_CHECK_INTERVAL_MS) {
                Log.d(TAG, "Update check throttled. Last check: $lastCheck")
                return UpdateCheckResult.Throttled
            }
        }

        return try {
            Log.d(TAG, "Fetching latest release from GitHub for $owner/$repo...")
            val latestRelease = retrofitService.getLatestRelease(owner, repo)
            preferences.setLastCheckTimestamp(now)

            val apkAsset = latestRelease.apkAsset
            if (apkAsset == null) {
                Log.w(TAG, "Latest release ${latestRelease.tagName} has no valid APK asset")
                return UpdateCheckResult.Error("No APK file found in latest release assets.")
            }

            val currentSemVer = SemVer.parse(currentVersionName) ?: SemVer(0, 0, 0)
            val latestSemVer = SemVer.parse(latestRelease.tagName) ?: SemVer(0, 0, 0)

            Log.d(TAG, "Current version: $currentSemVer, Latest release: $latestSemVer")

            if (latestSemVer.isNewerThan(currentSemVer)) {
                // Check if user chose "Remind Later" / deferred update (only if auto-check)
                if (!force) {
                    val shouldPrompt = preferences.shouldPromptUpdate(latestRelease.tagName)
                    if (!shouldPrompt) {
                        Log.d(TAG, "User opted to remind later / deferred version ${latestRelease.tagName}")
                        return UpdateCheckResult.Throttled
                    }
                }

                // Save pending changelog for post-update onboarding
                preferences.setPendingChangelog(latestRelease.body)
                UpdateCheckResult.UpdateAvailable(latestRelease, apkAsset)
            } else {
                cleanDownloadedApks()
                UpdateCheckResult.UpToDate(currentVersionName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            UpdateCheckResult.Error(e.message ?: "Failed to check for updates")
        }
    }

    /**
     * Checks if the APK file for the given release/asset already exists locally and is valid.
     * Strictly verifies local file length against expected asset size to prevent partial download installation crashes.
     */
    fun getExistingDownloadedApk(release: GitHubRelease, asset: GitHubAsset): File? {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val fileName = asset.name.ifBlank { "song-release-${release.tagName}.apk" }
        val targetFile = File(downloadsDir, fileName)

        if (targetFile.exists()) {
            val localSize = targetFile.length()
            val expectedSize = asset.size

            if (localSize > 0) {
                if (expectedSize > 0 && localSize != expectedSize) {
                    Log.w(TAG, "Partial/corrupted APK detected! Local size: $localSize, Expected size: $expectedSize. Deleting corrupt file.")
                    targetFile.delete()
                    return null
                }

                // Verify package archive integrity
                val archiveInfo = context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
                if (archiveInfo == null || archiveInfo.packageName.isNullOrBlank()) {
                    Log.w(TAG, "APK package archive integrity check failed for ${targetFile.absolutePath}! Deleting corrupt file.")
                    targetFile.delete()
                    return null
                }

                Log.d(TAG, "Found valid complete cached APK file ($localSize bytes) for ${archiveInfo.packageName} at ${targetFile.absolutePath}")
                return targetFile
            } else {
                Log.w(TAG, "Empty APK file found ($localSize bytes). Deleting.")
                targetFile.delete()
            }
        }
        return null
    }

    /**
     * Enqueues the APK asset download via Android DownloadManager.
     * Includes Low Storage space check and System DownloadManager service validation.
     */
    suspend fun startDownload(release: GitHubRelease, asset: GitHubAsset): Long {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("External downloads directory unavailable")

        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        // Low Storage Space Check: Verify usable space >= asset.size + 10MB safety margin (or 250MB fallback)
        val requiredSpace = if (asset.size > 0) asset.size + (10 * 1024 * 1024L) else 350 * 1024 * 1024L
        val usableSpace = downloadsDir.usableSpace
        if (usableSpace < requiredSpace) {
            val reqMb = requiredSpace / (1024 * 1024)
            val availMb = usableSpace / (1024 * 1024)
            throw IllegalStateException("Insufficient storage space for update. Required: ~${reqMb}MB, Available: ${availMb}MB")
        }

        // Validate that System DownloadManager provider is enabled
        try {
            val state = context.packageManager.getApplicationEnabledSetting("com.android.providers.downloads")
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                throw IllegalStateException("System Download Manager is disabled in Settings. Please enable Download Manager to download updates.")
            }
        } catch (e: IllegalArgumentException) {
            // Package name not found on customized OS skins; proceed to try-catch enqueue
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not query DownloadManager state", e)
        }

        // Cancel any stale/previous active download task in DownloadManager
        try {
            val staleId = preferences.activeDownloadId.first()
            if (staleId != -1L) {
                downloadManager.remove(staleId)
                preferences.setActiveDownloadId(-1L)
                Log.d(TAG, "Removed stale download task ID: $staleId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error purging stale download task", e)
        }

        val fileName = asset.name.ifBlank { "song-release-${release.tagName}.apk" }
        val targetFile = File(downloadsDir, fileName)
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
            .setTitle("Downloading Song ${release.tagName}")
            .setDescription("Downloading app update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadId = try {
            downloadManager.enqueue(request)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "DownloadManager enqueue failed (Service disabled or invalid request)", e)
            throw IllegalStateException("System Download Manager is disabled or unavailable. Please enable Download Manager in System Settings.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download", e)
            throw IllegalStateException("Download failed: ${e.message}")
        }

        preferences.setActiveDownloadId(downloadId)
        preferences.setDownloadedApkPath(targetFile.absolutePath)
        Log.d(TAG, "Enqueued download ID: $downloadId to path: ${targetFile.absolutePath}")
        return downloadId
    }

    /**
     * Queries current status and progress of an active download.
     */
    fun queryDownloadProgress(downloadId: Long): DownloadProgress? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query) ?: return null

        cursor.use {
            if (it.moveToFirst()) {
                val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val downloadedIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                val status = if (statusIndex != -1) it.getInt(statusIndex) else DownloadManager.STATUS_FAILED
                val downloaded = if (downloadedIndex != -1) it.getLong(downloadedIndex) else 0L
                val total = if (totalIndex != -1) it.getLong(totalIndex) else 0L

                return DownloadProgress(downloadId, status, downloaded, total)
            }
        }
        return null
    }

    /**
     * Checks if the app has permission to request package installation (`REQUEST_INSTALL_PACKAGES`).
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Creates intent to open System Settings for enabling "Install Unknown Apps".
     */
    fun getInstallUnknownAppsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Dispatches the downloaded APK to the system package installer via FileProvider.
     */
    fun installApk(apkFile: File): InstallResult {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            Log.e(TAG, "APK file does not exist or is empty: ${apkFile.absolutePath}")
            return InstallResult.CorruptFile
        }

        // Verify package archive integrity before attempting installation
        val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        if (archiveInfo == null || archiveInfo.packageName.isNullOrBlank()) {
            Log.e(TAG, "APK archive integrity check failed for ${apkFile.absolutePath}! Package is truncated or corrupt. Deleting corrupt file.")
            try { apkFile.delete() } catch (e: Exception) {}
            return InstallResult.CorruptFile
        }

        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.d(TAG, "Launching package installer intent for $apkUri")
            context.startActivity(installIntent)
            return InstallResult.Success
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity found on system to handle package installation intent", e)
            try { apkFile.delete() } catch (cleanupErr: Exception) {}
            return InstallResult.InstallerNotFound
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException launching package installer (OS background restriction)", e)
            return InstallResult.PermissionOrSecurityBlocked
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            try { apkFile.delete() } catch (cleanupErr: Exception) {}
            return InstallResult.Error(e.message ?: "Installation failed")
        }
    }

    /**
     * Checks on cold start if a background download completed while the app process was terminated.
     * Verifies that the completed file actually exists on disk; if wiped by a cleaner app, purges the stale task.
     */
    suspend fun checkPendingDownloadOnStart(): File? {
        val activeId = preferences.activeDownloadId.first()
        if (activeId == -1L) return null

        val progress = queryDownloadProgress(activeId)
        if (progress != null) {
            if (progress.status == DownloadManager.STATUS_SUCCESSFUL) {
                val apkPath = preferences.downloadedApkPath.first()
                if (!apkPath.isNullOrBlank()) {
                    val file = File(apkPath)
                    if (file.exists() && file.length() > 0) {
                        Log.d(TAG, "Recovered completed background download (ID $activeId) at $apkPath")
                        return file
                    } else {
                        // File was wiped from disk by phone optimizer/cleaner app!
                        Log.w(TAG, "Download ID $activeId was marked SUCCESSFUL, but file at $apkPath was wiped from disk. Purging stale task.")
                        try { downloadManager.remove(activeId) } catch (e: Exception) {}
                        preferences.setActiveDownloadId(-1L)
                        preferences.setDownloadedApkPath(null)
                    }
                }
            } else if (progress.status == DownloadManager.STATUS_FAILED || progress.status == DownloadManager.STATUS_PENDING) {
                Log.w(TAG, "Stale download ID $activeId failed or stalled in background. Cleaning up task.")
                try { downloadManager.remove(activeId) } catch (e: Exception) {}
                preferences.setActiveDownloadId(-1L)
                preferences.setDownloadedApkPath(null)
            }
        } else {
            // Task ID no longer present in DownloadManager database
            preferences.setActiveDownloadId(-1L)
            preferences.setDownloadedApkPath(null)
        }
        return null
    }

    /**
     * Post-update housekeeping: compares current [BuildConfig.VERSION_CODE] against DataStore.
     * If incremented: renders "What's New" changelog, resets pending download preferences,
     * and deletes cached installation package from external storage.
     */
    suspend fun checkPostUpdateHousekeeping(currentVersionCode: Int = BuildConfig.VERSION_CODE): PostUpdateResult {
        val lastInstalledCode = preferences.lastInstalledVersionCode.first()

        if (lastInstalledCode == -1) {
            // First run on this installation
            preferences.setLastInstalledVersionCode(currentVersionCode)
            cleanDownloadedApks()
            return PostUpdateResult(isPostUpdate = false, changelog = null)
        }

        if (currentVersionCode > lastInstalledCode) {
            Log.d(TAG, "App updated from versionCode $lastInstalledCode to $currentVersionCode")
            val changelog = preferences.pendingChangelog.first()

            // Update stored version code, clean up residual APKs, and purge legacy cache
            preferences.setLastInstalledVersionCode(currentVersionCode)
            preferences.setActiveDownloadId(-1L)
            preferences.setDownloadedApkPath(null)
            preferences.clearRemindLater()
            cleanDownloadedApks()
            purgeLegacyMediaCaches()

            return PostUpdateResult(isPostUpdate = true, changelog = changelog)
        }

        return PostUpdateResult(isPostUpdate = false, changelog = null)
    }

    /**
     * Deletes all cached .apk files in the app's external downloads directory.
     */
    fun cleanDownloadedApks() {
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir?.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk", ignoreCase = true)) {
                    val deleted = file.delete()
                    Log.d(TAG, "Cleaning up cached APK ${file.name}: $deleted")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning downloaded APKs", e)
        }
    }

    /**
     * Purges legacy media (including timestamped fallbacks) and image cache directories on version updates.
     */
    fun purgeLegacyMediaCaches() {
        try {
            // 1. Purge all media cache variations (Base + Fallbacks)
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith("media_cache")) {
                    val deleted = file.deleteRecursively()
                    Log.d(TAG, "Purged legacy cache directory on version update: ${file.name} (deleted=$deleted)")
                }
            }
            
            // 2. Purge image cache
            val imageCache = File(context.cacheDir, "image_cache")
            if (imageCache.exists()) {
                val deleted = imageCache.deleteRecursively()
                Log.d(TAG, "Purged legacy image_cache directory on version update (deleted=$deleted)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to purge legacy media caches", e)
        }
    }

    suspend fun setRemindLater(versionTag: String, millisFromNow: Long = 86_400_000L) {
        preferences.setRemindLater(millisFromNow, versionTag)
    }

    suspend fun clearPendingChangelog() {
        preferences.setPendingChangelog(null)
    }
}
