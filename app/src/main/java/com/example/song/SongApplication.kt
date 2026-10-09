package com.example.song

import android.app.Application
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.song.data.database.AppDatabase
import com.example.song.data.repository.SongRepository
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.example.song.data.preferences.CachePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import android.content.Intent
import android.os.Process
import com.example.song.util.CrashTracker
import com.example.song.util.ResilientDns
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class SongApplication : Application(), ImageLoaderFactory {

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val _cachedKeys = MutableStateFlow<Set<String>>(emptySet())
    val cachedKeys = _cachedKeys.asStateFlow()

    @androidx.media3.common.util.UnstableApi
    var playerCache: SimpleCache? = null
        private set

    lateinit var repository: SongRepository
        private set

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ResilientDns)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun newImageLoader(): ImageLoader {
        val totalLimitMb = try {
            runBlocking { CachePreferences(this@SongApplication).songCacheSizeMb.first() }
        } catch (e: Exception) {
            CachePreferences.DEFAULT_SONG_CACHE_MB
        }
        val imageCacheMb = maxOf(20, totalLimitMb / 6)

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(imageCacheMb.toLong() * 1024 * 1024L)
                    .build()
            }
            .okHttpClient { okHttpClient }
            .build()
    }

    companion object {
        private lateinit var instance: SongApplication
        fun getInstance(): SongApplication = instance
    }

    @UnstableApi
    private fun initPlayerCache() {
        val totalLimitMb = try {
            runBlocking { CachePreferences(this@SongApplication).songCacheSizeMb.first() }
        } catch (e: Exception) {
            CachePreferences.DEFAULT_SONG_CACHE_MB
        }
        val imageCacheMb = maxOf(20, totalLimitMb / 6)
        val audioCacheMb = maxOf(80, totalLimitMb - imageCacheMb)
        val cacheSize: Long = audioCacheMb.toLong() * 1024 * 1024L
        val cacheEvictor = LeastRecentlyUsedCacheEvictor(cacheSize)
        val databaseProvider = StandaloneDatabaseProvider(this)
        var cacheDirFile = File(cacheDir, "media_cache")

        try {
            playerCache = SimpleCache(
                cacheDirFile, 
                cacheEvictor, 
                databaseProvider
            )
        } catch (e: Exception) {
            Log.e("SongApplication", "Failed to initialize playerCache safely. Wiping corrupt/legacy cache and retrying.", e)
            try {
                val deleted = cacheDirFile.deleteRecursively()
                if (!deleted || cacheDirFile.exists()) {
                    Log.w("SongApplication", "deleteRecursively() failed or folder locked by OS. Switching to fallback directory.")
                    cacheDirFile = File(cacheDir, "media_cache_fallback_${System.currentTimeMillis()}")
                }

                playerCache = SimpleCache(
                    cacheDirFile, 
                    cacheEvictor, 
                    databaseProvider
                )
            } catch (e2: Exception) {
                Log.e("SongApplication", "Failed to re-initialize playerCache after cleanup. Disabling local media cache.", e2)
                playerCache = null
            }
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val keys = try { playerCache?.keys ?: emptySet() } catch (e: Exception) { emptySet() }
                if (_cachedKeys.value != keys) {
                    _cachedKeys.value = keys
                }
                delay(2000)
            }
        }
    }

    @UnstableApi
    fun updateCacheConfig(totalLimitMb: Int) {
        val validatedTotalMb = totalLimitMb.coerceAtLeast(CachePreferences.MIN_SONG_CACHE_MB)
        val imageCacheMb = maxOf(20, validatedTotalMb / 6)
        val audioCacheMb = maxOf(80, validatedTotalMb - imageCacheMb)
        val cacheSizeBytes = audioCacheMb.toLong() * 1024 * 1024L

        try {
            playerCache?.release()
        } catch (e: Exception) {
            Log.e("SongApplication", "Error releasing playerCache for update", e)
        }

        val cacheEvictor = LeastRecentlyUsedCacheEvictor(cacheSizeBytes)
        val databaseProvider = StandaloneDatabaseProvider(this)
        var cacheDirFile = File(cacheDir, "media_cache")

        try {
            playerCache = SimpleCache(cacheDirFile, cacheEvictor, databaseProvider)
        } catch (e: Exception) {
            Log.e("SongApplication", "Failed to update playerCache", e)
            cacheDirFile = File(cacheDir, "media_cache_fallback_${System.currentTimeMillis()}")
            try {
                playerCache = SimpleCache(cacheDirFile, cacheEvictor, databaseProvider)
            } catch (e2: Exception) {
                playerCache = null
            }
        }
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("SongApplication", "Fatal uncaught crash intercepted!", throwable)

                // Report fatal uncaught crash to Firebase Crashlytics before launching RecoveryActivity
                CrashTracker.recordException(
                    throwable = throwable,
                    breadcrumb = "Fatal uncaught crash intercepted on thread '${thread.name}'",
                    customKeys = mapOf(
                        "fatal_uncaught_thread" to thread.name,
                        "handled_by_crash_guard" to true
                    )
                )

                val crashLog = throwable.stackTraceToString()
                val intent = Intent(this, com.example.song.ui.screens.RecoveryActivity::class.java).apply {
                    putExtra("EXTRA_CRASH_LOG", crashLog)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("SongApplication", "Failed to launch RecoveryActivity", e)
                defaultHandler?.uncaughtException(thread, throwable)
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    @androidx.media3.common.util.UnstableApi
    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashHandler()
        instance = this

        val database = AppDatabase.getDatabase(this)
        repository = SongRepository(
            database.songDao(),
            database.playlistDao(),
            database.streamingDao(),
            filesDir
        )

        initPlayerCache()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // PRD §3.1: Reset any lingering INITIALIZING or CRAWLING records to PAUSED_PARTIAL on process startup
                database.spotifyCrawlDao().recoverProcessDeath()
            } catch (e: Exception) {
                Log.e("SongApplication", "Failed process death recovery for spotify crawl status", e)
            }

            try {
                Log.d("SongApplication", "Starting engine initialization...")
                YoutubeDL.getInstance().init(this@SongApplication)
                FFmpeg.getInstance().init(this@SongApplication)
                
                try {
                    Log.d("SongApplication", "Checking for yt-dlp binary updates...")
                    val updateResult = YoutubeDL.getInstance().updateYoutubeDL(this@SongApplication)
                    Log.d("SongApplication", "yt-dlp update status: $updateResult")
                    com.example.song.util.PulseLogger.log("Engine updated: $updateResult")
                } catch (e: Exception) {
                    Log.e("SongApplication", "Failed to update yt-dlp binary", e)
                }
                
                _isReady.value = true
                Log.d("SongApplication", "YoutubeDL and FFmpeg initialized successfully")
                com.example.song.util.PulseLogger.log("Engine Ready")
            } catch (e: YoutubeDLException) {
                Log.e("SongApplication", "Failed to initialize YoutubeDL", e)
            } catch (e: Exception) {
                Log.e("SongApplication", "General initialization error", e)
            }
        }
    }
}
