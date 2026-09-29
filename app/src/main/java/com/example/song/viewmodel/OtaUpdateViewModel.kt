package com.example.song.viewmodel

import android.app.Application
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.song.data.api.GitHubAsset
import com.example.song.data.api.GitHubRelease
import com.example.song.data.ota.DownloadProgress
import com.example.song.data.ota.InstallResult
import com.example.song.data.ota.OtaUpdateManager
import com.example.song.data.ota.UpdateCheckResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class OtaUpdateViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val TAG = "OtaUpdateViewModel"
    private val otaManager = OtaUpdateManager(application)

    private val _isChecking = MutableStateFlow(false)
    val isChecking = _isChecking.asStateFlow()

    private val _updateCheckResult = MutableStateFlow<UpdateCheckResult?>(null)
    val updateCheckResult = _updateCheckResult.asStateFlow()

    private val _activeRelease = MutableStateFlow<GitHubRelease?>(null)
    val activeRelease = _activeRelease.asStateFlow()

    private val _activeAsset = MutableStateFlow<GitHubAsset?>(null)
    val activeAsset = _activeAsset.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _showUpdateModal = MutableStateFlow(false)
    val showUpdateModal = _showUpdateModal.asStateFlow()

    private val _showWhatsNewModal = MutableStateFlow(false)
    val showWhatsNewModal = _showWhatsNewModal.asStateFlow()

    private val _whatsNewChangelog = MutableStateFlow<String?>(null)
    val whatsNewChangelog = _whatsNewChangelog.asStateFlow()

    private val _showPermissionModal = MutableStateFlow(false)
    val showPermissionModal = _showPermissionModal.asStateFlow()

    private val _showSettingsModal = MutableStateFlow(false)
    val showSettingsModal = _showSettingsModal.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage = _toastMessage.asStateFlow()

    private var progressPollingJob: Job? = null
    private var downloadedFile: File? = null

    // Persisted in SavedStateHandle so it survives process death when user goes to Settings
    private var isWaitingForInstallPermission: Boolean
        get() = savedStateHandle.get<Boolean>("KEY_WAITING_INSTALL_PERMISSION") ?: false
        set(value) {
            savedStateHandle["KEY_WAITING_INSTALL_PERMISSION"] = value
        }

    /**
     * Called on application start.
     * First checks post-update housekeeping (cleaning APKs, showing "What's New" if version updated),
     * then executes auto-throttled update check.
     */
    fun initializeOnStart() {
        viewModelScope.launch {
            try {
                val postUpdate = otaManager.checkPostUpdateHousekeeping()
                if (postUpdate.isPostUpdate) {
                    _whatsNewChangelog.value = postUpdate.changelog
                    _showWhatsNewModal.value = true
                }

                // Cold start background download recovery check
                val pendingFile = otaManager.checkPendingDownloadOnStart()
                if (pendingFile != null) {
                    downloadedFile = pendingFile
                    triggerInstallFlow(pendingFile)
                } else {
                    // Cold start background check
                    checkForUpdates(force = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing OTA check", e)
            }
        }
    }

    fun checkForUpdates(force: Boolean = false) {
        viewModelScope.launch {
            _isChecking.value = true
            try {
                val result = otaManager.checkForUpdate(force = force)
                _updateCheckResult.value = result

                when (result) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        _activeRelease.value = result.release
                        _activeAsset.value = result.apkAsset
                        _showUpdateModal.value = true
                    }
                    is UpdateCheckResult.UpToDate -> {
                        if (force) {
                            _toastMessage.value = "App is up to date (v${result.currentVersion})"
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        if (force) {
                            _toastMessage.value = "Update check failed: ${result.message}"
                        }
                    }
                    UpdateCheckResult.Throttled -> {
                        if (force) {
                            _toastMessage.value = "Checked recently. Up to date."
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                if (force) {
                    _toastMessage.value = "Error checking for updates: ${e.message}"
                }
            } finally {
                _isChecking.value = false
            }
        }
    }

    fun startDownloadOrInstall() {
        val release = _activeRelease.value ?: return
        val asset = _activeAsset.value ?: return

        val existingFile = otaManager.getExistingDownloadedApk(release, asset)
        if (existingFile != null && existingFile.exists() && existingFile.length() > 0) {
            Log.d(TAG, "Found valid existing downloaded APK (${existingFile.name}). Bypassing download and launching install flow.")
            downloadedFile = existingFile
            triggerInstallFlow(existingFile)
        } else {
            // File was missing or wiped by cleaner app. Start fresh download
            downloadedFile = null
            startDownloadForce()
        }
    }

    private fun startDownloadForce() {
        val release = _activeRelease.value ?: return
        val asset = _activeAsset.value ?: return

        viewModelScope.launch {
            try {
                _isDownloading.value = true
                val downloadId = otaManager.startDownload(release, asset)
                startProgressPolling(downloadId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start download", e)
                _isDownloading.value = false
                _toastMessage.value = e.message ?: "Download failed"
            }
        }
    }

    fun startDownload() {
        startDownloadOrInstall()
    }

    private fun startProgressPolling(downloadId: Long) {
        progressPollingJob?.cancel()
        progressPollingJob = viewModelScope.launch {
            while (_isDownloading.value) {
                val progress = otaManager.queryDownloadProgress(downloadId)
                _downloadProgress.value = progress

                if (progress != null) {
                    when (progress.status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            _isDownloading.value = false
                            onDownloadCompleted(downloadId)
                            break
                        }
                        DownloadManager.STATUS_FAILED -> {
                            _isDownloading.value = false
                            _toastMessage.value = "Download failed via DownloadManager"
                            break
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    fun onDownloadCompleted(downloadId: Long) {
        viewModelScope.launch {
            _isDownloading.value = false
            progressPollingJob?.cancel()

            val downloadsDir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val files = downloadsDir?.listFiles()
            val apkFile = files?.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

            if (apkFile != null && apkFile.exists() && apkFile.length() > 0) {
                downloadedFile = apkFile
                triggerInstallFlow(apkFile)
            } else {
                // Completed broadcast received, but file was wiped by cleaner app! Re-download automatically.
                Log.w(TAG, "Download completion broadcast received, but file is missing from disk. Restarting download.")
                downloadedFile = null
                startDownloadForce()
            }
        }
    }

    fun triggerInstallFlow(apkFile: File? = downloadedFile) {
        val targetFile = apkFile ?: downloadedFile
        if (targetFile == null || !targetFile.exists()) {
            _toastMessage.value = "APK file not found for installation."
            return
        }

        if (otaManager.canInstallPackages()) {
            when (val result = otaManager.installApk(targetFile)) {
                InstallResult.Success -> {
                    Log.d(TAG, "Package installer launched successfully.")
                }
                InstallResult.CorruptFile -> {
                    _toastMessage.value = "Downloaded update was incomplete or corrupt. Re-downloading..."
                    downloadedFile = null
                    startDownloadForce()
                }
                InstallResult.PermissionOrSecurityBlocked -> {
                    _toastMessage.value = "Please enable 'Install Unknown Apps' in Settings to update."
                    _showPermissionModal.value = true
                }
                InstallResult.InstallerNotFound -> {
                    _toastMessage.value = "No system package installer found on this device."
                }
                is InstallResult.Error -> {
                    _toastMessage.value = "Installation failed: ${result.message}"
                }
            }
        } else {
            // Prompt user to grant "Install Unknown Apps" permission
            _toastMessage.value = "Please enable 'Install Unknown Apps' in Settings to update."
            _showPermissionModal.value = true
        }
    }

    fun checkPermissionAndResumeInstall() {
        if (!isWaitingForInstallPermission) return
        isWaitingForInstallPermission = false

        if (otaManager.canInstallPackages()) {
            _showPermissionModal.value = false
            val release = _activeRelease.value
            val asset = _activeAsset.value
            val fileToInstall = downloadedFile ?: (if (release != null && asset != null) otaManager.getExistingDownloadedApk(release, asset) else null)
            if (fileToInstall != null && fileToInstall.exists()) {
                triggerInstallFlow(fileToInstall)
            }
        }
    }

    fun openPermissionSettings(context: Context) {
        isWaitingForInstallPermission = true
        _showPermissionModal.value = false
        try {
            val intent = otaManager.getInstallUnknownAppsIntent()
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES not supported on this OEM skin. Falling back to application details.", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open settings fallback", e2)
                _toastMessage.value = "Please enable 'Install Unknown Apps' for Song in System Settings manually."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching permission settings", e)
            _toastMessage.value = "Unable to open settings automatically."
        }
    }

    fun remindLater(millisFromNow: Long = 86_400_000L) {
        val release = _activeRelease.value
        if (release != null) {
            viewModelScope.launch {
                otaManager.setRemindLater(release.tagName, millisFromNow)
                _showUpdateModal.value = false
            }
        } else {
            _showUpdateModal.value = false
        }
    }

    fun dismissUpdateModal() {
        _showUpdateModal.value = false
    }

    fun dismissWhatsNewModal() {
        viewModelScope.launch {
            otaManager.clearPendingChangelog()
            _showWhatsNewModal.value = false
        }
    }

    fun dismissPermissionModal() {
        _showPermissionModal.value = false
    }

    fun openSettingsModal() {
        _showSettingsModal.value = true
    }

    fun dismissSettingsModal() {
        _showSettingsModal.value = false
    }

    fun clearToast() {
        _toastMessage.value = null
    }
}
