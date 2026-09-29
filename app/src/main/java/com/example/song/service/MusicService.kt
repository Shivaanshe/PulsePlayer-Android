package com.example.song.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaNotification
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.song.MainActivity
import com.example.song.SongApplication
import com.example.song.data.model.Song
import com.example.song.util.CrashTracker
import com.example.song.util.PulseLogger
import com.example.song.util.YoutubeStreamHandler
import androidx.media3.common.PlaybackException
import com.example.song.util.MusicQueueCache
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var idleJob: Job? = null
    
    private lateinit var httpDataSourceFactory: OkHttpDataSource.Factory
    private var currentQueue: List<Song> = emptyList()
    private var lastSavedSongId: Int = -1

    private val resolvedCache = ConcurrentHashMap<String, ResolvedData>()
    private val artworkCache = ConcurrentHashMap<String, ByteArray>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var currentBackgroundProcessId: String? = null
    private var preResolveJob: Job? = null

    data class ResolvedData(
        val url: String,
        val headers: Map<String, String>,
        val artwork: ByteArray?,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean {
            try {
                val expireParam = Uri.parse(url).getQueryParameter("expire")
                if (expireParam != null) {
                    val expireTimeSec = expireParam.toLongOrNull()
                    if (expireTimeSec != null) {
                        val currentTimeSec = System.currentTimeMillis() / 1000L
                        return currentTimeSec >= (expireTimeSec - 300)
                    }
                }
            } catch (_: Exception) {}

            return (System.currentTimeMillis() - timestamp) > 90 * 60 * 1000L
        }
    }

    companion object {
        private const val TAG = "PulseDebug"
        private const val PREFS_NAME = "pulse_prefs"
        private const val KEY_LAST_SONG_ID = "last_played_song_id"
        private const val KEY_LAST_QUEUE_IDS = "last_queue_ids"
        private const val KEY_LAST_INDEX = "last_played_index"
        private const val KEY_LAST_POSITION = "last_played_position"
    }

    override fun onCreate() {
        super.onCreate()

        val app = SongApplication.getInstance()
        val cache = app.playerCache
        
        httpDataSourceFactory = OkHttpDataSource.Factory(app.okHttpClient)
        val baseFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val resolvingDataSourceFactory = ResolvingDataSource.Factory(
            baseFactory,
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                    val uriStr = dataSpec.uri.toString()
                    
                    if (uriStr.contains("pulse.music/resolve")) {
                        val mediaId = dataSpec.uri.pathSegments.lastOrNull() ?: "unknown"
                        val query = dataSpec.uri.getQueryParameter("query")
                        if (query.isNullOrBlank()) {
                            PulseLogger.log("JIT Resolution aborted: Query parameter missing or blank in $uriStr", isError = true)
                            throw IOException("Invalid stream resolution query for Media ID: $mediaId")
                        }
                        val artworkUrl = dataSpec.uri.getQueryParameter("artwork_url") ?: ""
                        
                        val cached = resolvedCache[query]
                        if (cached != null && !cached.isExpired()) {
                            PulseLogger.log("JIT Cache Hit: Instant skip enabled.")
                            return dataSpec.buildUpon()
                                .setUri(Uri.parse(cached.url))
                                .setHttpRequestHeaders(cached.headers)
                                .setKey(mediaId)
                                .build()
                        }

                        PulseLogger.log("JIT Resolution started for: $query")
                        
                        return try {
                            runBlocking(Dispatchers.IO) { 
                                val resolved = performResolution(query, artworkUrl, isPriority = true)
                                if (resolved != null) {
                                    resolvedCache[query] = resolved
                                    withContext(Dispatchers.Main) {
                                        updateActiveMetadata(query, resolved.artwork)
                                    }

                                    dataSpec.buildUpon()
                                        .setUri(Uri.parse(resolved.url))
                                        .setHttpRequestHeaders(resolved.headers)
                                        .setKey(mediaId)
                                        .build()
                                } else {
                                    PulseLogger.log("JIT Resolution FAILED for: $query", isError = true)
                                    throw IOException("JIT stream resolution failed for query: $query")
                                }
                            }
                        } catch (_: CancellationException) {
                            PulseLogger.log("JIT Resolution preempted. Deferring to next access.")
                            dataSpec
                        }
                    }
                    return dataSpec
                }
            }
        )

        val dataSourceFactory = if (cache != null) {
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(resolvingDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            DefaultMediaSourceFactory(cacheDataSourceFactory)
        } else {
            DefaultMediaSourceFactory(resolvingDataSourceFactory)
        }

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        val renderersFactory = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(
                MediaCodecAudioRenderer(
                    this,
                    MediaCodecSelector.DEFAULT,
                    handler,
                    audioListener
                )
            )
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(dataSourceFactory)
            .setAudioAttributes(audioAttributes, true) // true handles audio focus automatically
            .setHandleAudioBecomingNoisy(true) // handle Bluetooth/Headphone disconnect
            .build()

        // 🛡️ Disable Video tracks explicitly to prevent hardware video decoder allocation
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        // 🛡️ Guarantee 1.0x normal playback speed to prevent sample rate/fast-forward bugs
        player.playbackParameters = PlaybackParameters(1.0f, 1.0f)
        
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 🛡️ Avoid feedback loop: Ignore transitions triggered by metadata/playlist updates
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return

                // 🛡️ Cancel stale resolution jobs immediately
                activeJobs.forEach { (key, job) ->
                    val currentQuery = mediaItem?.localConfiguration?.uri?.getQueryParameter("query")
                    if (currentQuery != key) {
                        job.cancel()
                        activeJobs.remove(key)
                    }
                }

                if (mediaItem != null) {
                    val mediaId = mediaItem.mediaId
                    
                    saveCurrentState(synchronous = false)

                    val query = mediaItem.localConfiguration?.uri?.getQueryParameter("query")
                    val artworkUrl = mediaItem.mediaMetadata.extras?.getString("custom_artwork_url")
                    
                    val cached = query?.let { resolvedCache[it] }
                    if (cached != null) {
                        serviceScope.launch { updateActiveMetadata(mediaId, cached.artwork) }
                    } else if (!artworkUrl.isNullOrEmpty() && mediaItem.mediaMetadata.artworkData == null) {
                        // 🖼️ Independent Artwork Fetch for cached items or new sessions
                        serviceScope.launch {
                            val art = fetchImageAsByteArray(artworkUrl)
                            if (art != null) {
                                updateActiveMetadata(mediaId, art)
                            }
                        }
                    }
                }
                preResolveNextItems()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (player.currentPosition > 1000) player.seekToNext()
                }
                val stateName = when(playbackState) {
                    Player.STATE_READY -> "Ready"
                    Player.STATE_BUFFERING -> "Buffering"
                    Player.STATE_IDLE -> "Idle"
                    Player.STATE_ENDED -> "Ended"
                    else -> "Unknown"
                }
                PulseLogger.log("Player State: $stateName")
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PulseLogger.log("Playback: ${if(isPlaying) "Playing" else "Paused"}")
                idleJob?.cancel()
                if (!isPlaying) {
                    idleJob = serviceScope.launch {
                        delay(30 * 1000L) // 30 seconds idle timeout when paused
                        if (!player.isPlaying && player.playbackState != Player.STATE_BUFFERING) {
                            PulseLogger.log("Idle for 30 seconds. Stopping service.")
                            stopSelf()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentMediaItem = player.currentMediaItem
                val query = currentMediaItem?.localConfiguration?.uri?.getQueryParameter("query")
                
                PulseLogger.log("ExoPlayer error in MusicService: ${error.errorCodeName} (${error.message})", isError = true)
                
                if (!query.isNullOrEmpty() && isSourceOrNetworkError(error)) {
                    PulseLogger.log("Source/network error for JIT track: $query. Invalidating cache & re-resolving...")
                    resolvedCache.remove(query)
                    attemptJitRetryAndRecovery(query, currentMediaItem)
                    return
                }

                CrashTracker.recordException(
                    throwable = error,
                    breadcrumb = "ExoPlayer error in MusicService",
                    customKeys = mapOf(
                        "error_code" to error.errorCodeName,
                        "media_id" to (currentMediaItem?.mediaId ?: "unknown")
                    )
                )
            }
        })

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(MediaSessionCallback())
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentState(synchronous = true)
        val player = mediaSession?.player ?: return
        
        if (!player.isPlaying) {
            PulseLogger.log("App swiped away while paused. Graceful termination triggered.")
            
            // 🧹 Instant cleanup so it clears from RAM properly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveCurrentState(synchronous = true)
        
        // 3. CRITICAL: In Media3, you MUST release the MediaSession BEFORE the player.
        mediaSession?.release()
        mediaSession = null
        
        if (::player.isInitialized) {
            player.release()
        }
        
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun saveCurrentState(synchronous: Boolean = false) {
        if (!::player.isInitialized) return
        val currentMediaItem = player.currentMediaItem ?: return
        val mediaId = currentMediaItem.mediaId
        val songId = mediaId.toIntOrNull() ?: -1
        val currentIndex = player.currentMediaItemIndex
        val currentPos = if (player.playbackState != Player.STATE_IDLE) player.currentPosition.coerceAtLeast(0L) else 0L

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(KEY_LAST_SONG_ID, songId)
            .putInt(KEY_LAST_INDEX, currentIndex)
            .putLong(KEY_LAST_POSITION, currentPos)

        if (currentQueue.isNotEmpty()) {
            val idsString = currentQueue.map { it.id }.joinToString(",")
            prefs.putString(KEY_LAST_QUEUE_IDS, idsString)
        }

        if (synchronous) {
            prefs.commit()
        } else {
            prefs.apply()
        }
    }

    private var isRetryingJit = false

    private fun isSourceOrNetworkError(error: PlaybackException): Boolean {
        val errorCode = error.errorCode
        return errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
               errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
               errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
               errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
               errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
               errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ||
               errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
               error.cause is UnknownHostException ||
               error.cause is SocketTimeoutException ||
               error.message?.contains("Source error", ignoreCase = true) == true ||
               error.message?.contains("Response code", ignoreCase = true) == true
    }

    private fun attemptJitRetryAndRecovery(query: String, mediaItem: MediaItem) {
        if (isRetryingJit) return
        isRetryingJit = true

        serviceScope.launch(Dispatchers.IO) {
            try {
                PulseLogger.log("Auto-recovery: Re-resolving stream for $query")
                val artworkUrl = mediaItem.mediaMetadata.extras?.getString("custom_artwork_url") ?: ""
                val freshData = performResolution(query, artworkUrl, isPriority = true)

                if (freshData != null) {
                    resolvedCache[query] = freshData
                    PulseLogger.log("Auto-recovery SUCCESS: Fresh URL obtained for $query. Resuming playback...")

                    withContext(Dispatchers.Main) {
                        if (player.mediaItemCount > 0) {
                            val currentIndex = player.currentMediaItemIndex
                            val currentPos = player.currentPosition

                            player.prepare()
                            player.seekTo(currentIndex, currentPos)
                            player.play()
                        }
                    }
                } else {
                    PulseLogger.log("Auto-recovery FAILED: Could not re-resolve stream for $query", isError = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during JIT auto-recovery", e)
                CrashTracker.recordException(e, "JIT auto-recovery failed", mapOf("query" to query))
            } finally {
                isRetryingJit = false
            }
        }
    }

    private suspend fun performResolution(query: String, artworkUrl: String, isPriority: Boolean): ResolvedData? = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            PulseLogger.log("Aborting JIT resolution: query is blank", isError = true)
            return@withContext null
        }

        val processId = UUID.randomUUID().toString()
        val isLocalFile = query.startsWith("/") || query.startsWith("file://") || File(query).exists()
        
        if (isLocalFile) {
            val artworkBytes = fetchImageAsByteArray(artworkUrl)
            return@withContext ResolvedData(Uri.fromFile(File(query)).toString(), emptyMap(), artworkBytes)
        }

        if (isPriority) {
            currentBackgroundProcessId?.let { bgProcId ->
                try {
                    YoutubeStreamHandler.cancelProcess(bgProcId)
                    PulseLogger.log("Preempted background JIT pre-resolution for priority request.")
                } catch (_: Exception) {}
            }
        } else {
            currentBackgroundProcessId = processId
        }

        val job = Job()
        activeJobs[query] = job
        
        try {
            YoutubeStreamHandler.ytDlMutex.withLock {
                if (!isPriority && !job.isActive) return@withLock null
                
                val streamDeferred = async { YoutubeStreamHandler.getStreamInfo(query, processId) }
                val artworkDeferred = async { fetchImageAsByteArray(artworkUrl) }
                
                val info = withTimeoutOrNull(35000L) { streamDeferred.await() }
                val art = artworkDeferred.await()
                
                if (info != null) {
                    ResolvedData(info.url, info.headers, art)
                } else {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            if (!isPriority && currentBackgroundProcessId == processId) {
                currentBackgroundProcessId = null
            }
            YoutubeStreamHandler.cancelProcess(processId)
            YoutubeStreamHandler.clearProcessCancellation(processId)
            activeJobs.remove(query)
            job.cancel()
        }
    }

    private fun preResolveNextItems() {
        preResolveJob?.cancel()
        preResolveJob = serviceScope.launch(Dispatchers.IO) {
            val (currentIndex, count) = withContext(Dispatchers.Main) {
                player.currentMediaItemIndex to player.mediaItemCount
            }
            if (count == 0) return@launch

            trimCacheIfNeeded(currentIndex)

            // 🔋 Active Sliding Window Pre-Resolution: 2 tracks ahead, 1 track behind
            val windowIndices = listOf(currentIndex + 1, currentIndex + 2, currentIndex - 1)
            for (i in windowIndices) {
                if (!isActive) break

                val item = withContext(Dispatchers.Main) {
                    runCatching {
                        if (i in 0 until player.mediaItemCount) {
                            player.getMediaItemAt(i)
                        } else null
                    }.getOrNull()
                } ?: continue

                val query = item.localConfiguration?.uri?.getQueryParameter("query")
                val artUrl = item.localConfiguration?.uri?.getQueryParameter("artwork_url")

                if (query != null && (!resolvedCache.containsKey(query) || resolvedCache[query]?.isExpired() == true)) {
                    try {
                        delay((1500L..3500L).random())
                        val resolved = performResolution(query, artUrl ?: "", isPriority = false)
                        if (resolved != null && isActive) {
                            resolvedCache[query] = resolved
                            withContext(Dispatchers.Main) {
                                updateMetadataInQueue(i, item.mediaId, resolved.artwork)
                            }
                        }
                    } catch (_: CancellationException) {
                        PulseLogger.log("Background pre-resolution preempted for query: $query")
                        break
                    } catch (e: Exception) {
                        PulseLogger.log("Background pre-resolution failed for query: $query", isError = true)
                    }
                }
            }
        }
    }

    private fun trimCacheIfNeeded(currentIndex: Int) {
        if (resolvedCache.size > 35) {
            val keysToRemove = resolvedCache.keys.filter { key ->
                val isNearActiveWindow = (currentIndex - 2..currentIndex + 3).any { idx ->
                    if (idx in 0 until player.mediaItemCount) {
                        val itemKey = player.getMediaItemAt(idx).localConfiguration?.uri?.getQueryParameter("query")
                        itemKey == key
                    } else false
                }
                !isNearActiveWindow
            }.take(resolvedCache.size - 25)

            for (k in keysToRemove) {
                resolvedCache.remove(k)
                artworkCache.remove(k)
            }
        }
    }

    private fun updateMetadataInQueue(index: Int, mediaId: String, artworkBytes: ByteArray?) {
        if (artworkBytes == null) return
        runCatching {
            if (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                if (item.mediaId == mediaId && item.mediaMetadata.artworkData == null) {
                    val updatedMetadata = item.mediaMetadata.buildUpon()
                        .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                    val updatedItem = item.buildUpon().setMediaMetadata(updatedMetadata).build()
                    
                    if (index != player.currentMediaItemIndex) {
                        player.replaceMediaItem(index, updatedItem)
                    }
                }
            }
        }
    }

    private fun updateActiveMetadata(mediaId: String, artworkBytes: ByteArray?) {
        runCatching {
            if (player.mediaItemCount > 0) {
                updateMetadataInQueue(player.currentMediaItemIndex, mediaId, artworkBytes)
            }
        }
    }

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand("PLAY_QUEUE", Bundle.EMPTY))
                .add(SessionCommand("UPDATE_QUEUE", Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedQueue = prefs.getString(KEY_LAST_QUEUE_IDS, null)
            val lastIndex = prefs.getInt(KEY_LAST_INDEX, 0)
            val lastSongId = prefs.getInt(KEY_LAST_SONG_ID, -1)
            val lastPosition = prefs.getLong(KEY_LAST_POSITION, 0L)

            val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()

            // ⚡ Fast Path: In-memory queue cache
            val cachedSongs = MusicQueueCache.getQueue()
            if (cachedSongs.isNotEmpty()) {
                val matchedIndex = if (lastSongId != -1) {
                    cachedSongs.indexOfFirst { it.id == lastSongId }.takeIf { it != -1 }
                } else null

                val startIndex = matchedIndex ?: if (lastIndex in cachedSongs.indices) lastIndex else 0
                val mediaItems = cachedSongs.map { mapSongToMediaItem(it) }
                currentQueue = cachedSongs

                PulseLogger.log("Fast Resumption (Memory Cache): Song ${startIndex + 1} of ${cachedSongs.size}")
                settableFuture.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, lastPosition))
                return settableFuture
            }

            if (savedQueue.isNullOrEmpty()) {
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
            }

            // 🐢 Secondary Path: Query DB with a 2.5-second timeout guard for Media3 compatibility
            serviceScope.launch {
                try {
                    val repository = SongApplication.getInstance().repository
                    val ids = savedQueue.split(",").mapNotNull { it.toIntOrNull() }

                    if (ids.isEmpty()) {
                        settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                        return@launch
                    }

                    val songs = withTimeoutOrNull(2500L) {
                        withContext(Dispatchers.IO) {
                            val streamingIds = ids.filter { it >= 1_000_000 }.map { it - 1_000_000 }
                            val localIds = ids.filter { it < 1_000_000 }

                            val streamingItems = if (streamingIds.isNotEmpty()) repository.getStreamingItemsByIdsSync(streamingIds) else emptyList()
                            val localSongs = if (localIds.isNotEmpty()) repository.getSongsByIdsSync(localIds) else emptyList()

                            val allItemsMap = mutableMapOf<Int, Song>()
                            localSongs.forEach { allItemsMap[it.id] = it }
                            streamingItems.forEach { item ->
                                val song = item.toSong().copy(id = 1_000_000 + item.id)
                                allItemsMap[1_000_000 + item.id] = song
                            }
                            ids.mapNotNull { allItemsMap[it] }
                        }
                    } ?: emptyList()

                    if (songs.isNotEmpty()) {
                        MusicQueueCache.setQueue(songs)
                        currentQueue = songs

                        val matchedIndex = if (lastSongId != -1) {
                            songs.indexOfFirst { it.id == lastSongId }.takeIf { it != -1 }
                        } else null

                        val startIndex = matchedIndex ?: if (lastIndex in songs.indices) lastIndex else 0
                        val mediaItems = songs.map { mapSongToMediaItem(it) }

                        PulseLogger.log("Queue Resumed: Song ${startIndex + 1} of ${songs.size} (Target ID: $lastSongId)")
                        settableFuture.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, lastPosition))
                    } else {
                        settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onPlaybackResumption", e)
                    CrashTracker.recordException(e, "Playback resumption failed")
                    settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                }
            }

            return settableFuture
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == "PLAY_QUEUE") {
                val index = args.getInt("index", 0)
                val ids = args.getIntegerArrayList("ids") ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                
                serviceScope.launch {
                    val repository = SongApplication.getInstance().repository
                    
                    val parcelableSongs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        args.getParcelableArrayList("songs", Song::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        args.getParcelableArrayList("songs")
                    }

                    val songs = if (!parcelableSongs.isNullOrEmpty()) {
                        parcelableSongs
                    } else {
                        val memoryQueue = MusicQueueCache.getQueue()
                        if (memoryQueue.isNotEmpty() && memoryQueue.map { it.id } == ids) {
                            memoryQueue
                        } else {
                            withContext(Dispatchers.IO) {
                                val streamingIds = ids.filter { it >= 1_000_000 }.map { it - 1_000_000 }
                                val localIds = ids.filter { it < 1_000_000 }
                                val streamingItems = if (streamingIds.isNotEmpty()) repository.getStreamingItemsByIdsSync(streamingIds) else emptyList()
                                val localSongs = if (localIds.isNotEmpty()) repository.getSongsByIdsSync(localIds) else emptyList()
                                val allItemsMap = mutableMapOf<Int, Song>()
                                localSongs.forEach { allItemsMap[it.id] = it }
                                streamingItems.forEach { item ->
                                    val song = item.toSong().copy(id = 1_000_000 + item.id)
                                    allItemsMap[1_000_000 + item.id] = song
                                }
                                ids.mapNotNull { allItemsMap[it] }
                            }
                        }
                    }
                    
                    if (songs.isNotEmpty()) {
                        MusicQueueCache.setQueue(songs)
                        currentQueue = songs
                        val targetSongId = if (index in songs.indices) songs[index].id else -1

                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(KEY_LAST_QUEUE_IDS, ids.joinToString(","))
                            .putInt(KEY_LAST_INDEX, index)
                            .putInt(KEY_LAST_SONG_ID, targetSongId)
                            .putLong(KEY_LAST_POSITION, 0L)
                            .commit()

                        PulseLogger.log("Queue mapping: ${songs.size} items JIT-Ready")
                        val mediaItems = songs.map { mapSongToMediaItem(it) }
                        
                        withContext(Dispatchers.Main) {
                            player.setMediaItems(mediaItems, index, 0L)
                            player.prepare()
                            player.play()
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            } else if (customCommand.customAction == "UPDATE_QUEUE") {
                val index = args.getInt("index", if (player.mediaItemCount > 0) player.currentMediaItemIndex else 0)
                val position = if (player.playbackState != Player.STATE_IDLE) player.currentPosition else 0L
                val isPlaying = player.isPlaying
                val ids = args.getIntegerArrayList("ids") ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))

                serviceScope.launch {
                    val repository = SongApplication.getInstance().repository

                    val parcelableSongs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        args.getParcelableArrayList("songs", Song::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        args.getParcelableArrayList("songs")
                    }

                    val songs = if (!parcelableSongs.isNullOrEmpty()) {
                        parcelableSongs
                    } else {
                        val memoryQueue = MusicQueueCache.getQueue()
                        if (memoryQueue.isNotEmpty() && memoryQueue.map { it.id } == ids) {
                            memoryQueue
                        } else {
                            withContext(Dispatchers.IO) {
                                val streamingIds = ids.filter { it >= 1_000_000 }.map { it - 1_000_000 }
                                val localIds = ids.filter { it < 1_000_000 }
                                val streamingItems = if (streamingIds.isNotEmpty()) repository.getStreamingItemsByIdsSync(streamingIds) else emptyList()
                                val localSongs = if (localIds.isNotEmpty()) repository.getSongsByIdsSync(localIds) else emptyList()
                                val allItemsMap = mutableMapOf<Int, Song>()
                                localSongs.forEach { allItemsMap[it.id] = it }
                                streamingItems.forEach { item ->
                                    val song = item.toSong().copy(id = 1_000_000 + item.id)
                                    allItemsMap[1_000_000 + item.id] = song
                                }
                                ids.mapNotNull { allItemsMap[it] }
                            }
                        }
                    }

                    if (songs.isNotEmpty()) {
                        MusicQueueCache.setQueue(songs)
                        currentQueue = songs
                        val targetSongId = if (index in songs.indices) songs[index].id else -1

                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(KEY_LAST_QUEUE_IDS, ids.joinToString(","))
                            .putInt(KEY_LAST_INDEX, index)
                            .putInt(KEY_LAST_SONG_ID, targetSongId)
                            .putLong(KEY_LAST_POSITION, position)
                            .commit()

                        val mediaItems = songs.map { mapSongToMediaItem(it) }
                        withContext(Dispatchers.Main) {
                            syncPlayerPlaylist(player, mediaItems, index, position, isPlaying)
                            preResolveNextItems()
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == "SKIP_TO_PREVIOUS") {
                serviceScope.launch(Dispatchers.Main) {
                    if (player.currentPosition > 3000) {
                        player.seekTo(0)
                    } else if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    } else {
                        player.seekTo(0)
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == "SKIP_TO_NEXT") {
                serviceScope.launch(Dispatchers.Main) {
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private fun mapSongToMediaItem(song: Song): MediaItem {
        val isLocal = song.audioUri.startsWith("/") || song.audioUri.startsWith("file://")
        val searchQuery = song.audioUri.ifBlank {
            "${song.title} ${song.artist}".trim().ifBlank { song.audioUri }
        }
        val uri = if (isLocal) Uri.fromFile(File(song.audioUri)) else {
            val encodedQuery = Uri.encode(searchQuery)
            val encodedArt = Uri.encode(song.imageUrl ?: "")
            Uri.parse("https://pulse.music/resolve/${song.id}?query=$encodedQuery&artwork_url=$encodedArt")
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setArtworkUri(song.imageUrl?.let { Uri.parse(it) })
            .setExtras(Bundle().apply { 
                putString("custom_artwork_url", song.imageUrl)
                putString("search_query", searchQuery)
            })
            .build()
        
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(uri)
            .setCustomCacheKey(song.id.toString()) 
            .setMediaMetadata(metadata)
            .build()
    }

    private fun syncPlayerPlaylist(
        player: Player,
        mediaItems: List<MediaItem>,
        targetIndex: Int,
        position: Long,
        isPlaying: Boolean
    ) {
        if (mediaItems.isEmpty()) {
            player.clearMediaItems()
            return
        }

        val safeIndex = targetIndex.coerceIn(0, mediaItems.size - 1)
        val currentItem = player.currentMediaItem
        val targetItem = mediaItems[safeIndex]
        val currentIndex = player.currentMediaItemIndex

        if (currentItem != null && currentItem.mediaId == targetItem.mediaId && player.playbackState != Player.STATE_IDLE) {
            // 🛡️ SEAMLESS QUEUE SYNC: Current playing song is unchanged!
            // Do NOT call player.setMediaItems(...) because it tears down the active decoder & resets playback.
            // Incrementally update items after and before currentMediaItemIndex.

            // 1. Sync items after current track
            val currentCountAfter = player.mediaItemCount - (currentIndex + 1)
            if (currentCountAfter > 0) {
                player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
            }
            val targetItemsAfter = if (safeIndex + 1 < mediaItems.size) {
                mediaItems.subList(safeIndex + 1, mediaItems.size)
            } else {
                emptyList()
            }
            if (targetItemsAfter.isNotEmpty()) {
                player.addMediaItems(currentIndex + 1, targetItemsAfter)
            }

            // 2. Sync items before current track
            val existingBefore = mutableListOf<String>()
            for (i in 0 until currentIndex) {
                existingBefore.add(player.getMediaItemAt(i).mediaId)
            }
            val targetBefore = mediaItems.subList(0, safeIndex).map { it.mediaId }

            if (existingBefore != targetBefore) {
                for (i in 0 until currentIndex) {
                    player.removeMediaItem(0)
                }
                val targetItemsBefore = mediaItems.subList(0, safeIndex)
                if (targetItemsBefore.isNotEmpty()) {
                    player.addMediaItems(0, targetItemsBefore)
                }
            }
        } else {
            // New song selected or player idle: full reload needed
            player.setMediaItems(mediaItems, safeIndex, position)
            if (isPlaying) player.play()
        }
    }

    private suspend fun fetchImageAsByteArray(url: String?): ByteArray? {
        if (url.isNullOrEmpty()) return null
        
        // 🚀 Cache Hit check
        artworkCache[url]?.let { return it }

        return try {
            val app = SongApplication.getInstance()
            val request = ImageRequest.Builder(app).data(url).allowHardware(false).build()
            val result = app.imageLoader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream) // 🔋 Low CPU compression
                    val bytes = stream.toByteArray()
                    
                    // 🚀 Cache Store (limit size)
                    if (artworkCache.size > 20) artworkCache.clear() 
                    artworkCache[url] = bytes
                    bytes
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    private fun com.example.song.data.model.StreamingItem.toSong(): Song {
        return Song(id = id, title = title, artist = artist ?: "Unknown Artist", audioUri = youtubeUrl, imageUrl = thumbnailUrl, duration = duration)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
}
