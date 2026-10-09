package com.example.song.viewmodel

import android.app.Application
import android.util.Log
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.song.SongApplication
import com.example.song.data.database.AppDatabase
import com.example.song.data.model.Playlist
import com.example.song.data.model.QueueItem
import com.example.song.data.model.Song
import com.example.song.data.model.StreamingItem
import com.example.song.data.repository.SongRepository
import com.example.song.service.MusicService
import com.example.song.util.CrashTracker
import com.example.song.util.MusicQueueCache
import com.example.song.util.PulseLogger
import com.example.song.util.SpotifyResolver
import com.example.song.util.YoutubeStreamHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

enum class ItemActionState {
    Idle, Loading, Success
}

@OptIn(UnstableApi::class)
class SongViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SongRepository = SongRepository(
        AppDatabase.getDatabase(application).songDao(),
        AppDatabase.getDatabase(application).playlistDao(),
        AppDatabase.getDatabase(application).streamingDao(),
        application.filesDir
    )
    private var mediaController: MediaController? = null
    private var isConnecting = false
    private var lastSentTimelineIds = listOf<Int>()
    private var isPreparingNewSession = false

    private fun isConnectedToInternet(): Boolean {
        val context = getApplication<Application>()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favoriteSongs = MutableStateFlow<List<Song>>(emptyList())
    val favoriteSongs: StateFlow<List<Song>> = _favoriteSongs.asStateFlow()

    private val _currentPlayingSong = MutableStateFlow<Song?>(null)
    val currentPlayingSong: StateFlow<Song?> = _currentPlayingSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentQueue = MutableStateFlow<List<Song>>(emptyList())
    val currentQueue: StateFlow<List<Song>> = _currentQueue.asStateFlow()

    private val _manualQueue = MutableStateFlow<List<QueueItem>>(emptyList())
    val manualQueue: StateFlow<List<QueueItem>> = _manualQueue.asStateFlow()

    private val _parentQueue = MutableStateFlow<List<QueueItem>>(emptyList())
    val parentQueue: StateFlow<List<QueueItem>> = _parentQueue.asStateFlow()

    private val _historyStack = MutableStateFlow<List<Song>>(emptyList())
    val historyStack: StateFlow<List<Song>> = _historyStack.asStateFlow()

    private val _sourcePlaylist = MutableStateFlow<List<Song>>(emptyList())
    val sourcePlaylist: StateFlow<List<Song>> = _sourcePlaylist.asStateFlow()

    private val _unshuffledQueue = MutableStateFlow<List<Song>>(emptyList())
    val unshuffledQueue: StateFlow<List<Song>> = _unshuffledQueue.asStateFlow()

    private val _queueContextTitle = MutableStateFlow("Up Next")
    val queueContextTitle: StateFlow<String> = _queueContextTitle.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _showQueueSheet = MutableStateFlow(false)
    val showQueueSheet: StateFlow<Boolean> = _showQueueSheet.asStateFlow()

    private val _selectedSongForOptions = MutableStateFlow<Song?>(null)
    val selectedSongForOptions: StateFlow<Song?> = _selectedSongForOptions.asStateFlow()

    private val _queueSnackbarMessage = MutableStateFlow<String?>(null)
    val queueSnackbarMessage: StateFlow<String?> = _queueSnackbarMessage.asStateFlow()

    private val _sleepTimerMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerMinutes: StateFlow<Int?> = _sleepTimerMinutes.asStateFlow()

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()

    private var sleepTimerJob: Job? = null

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _topLevelStreamingItems = MutableStateFlow<List<StreamingItem>>(emptyList())
    val topLevelStreamingItems: StateFlow<List<StreamingItem>> = _topLevelStreamingItems.asStateFlow()

    private val _allStreamingSongs = MutableStateFlow<List<StreamingItem>>(emptyList())
    val allStreamingSongs: StateFlow<List<StreamingItem>> = _allStreamingSongs.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _extractionStatus = MutableStateFlow<String?>(null)
    val extractionStatus: StateFlow<String?> = _extractionStatus.asStateFlow()

    private val _extractionProgress = MutableStateFlow<Float?>(null)
    val extractionProgress: StateFlow<Float?> = _extractionProgress.asStateFlow()

    private val _pendingStreamingItems = MutableStateFlow<List<StreamingItem>>(emptyList())
    val pendingStreamingItems: StateFlow<List<StreamingItem>> = _pendingStreamingItems.asStateFlow()

    private val _duplicatePlaylistState = MutableStateFlow<DuplicatePlaylistState>(DuplicatePlaylistState.Idle)
    val duplicatePlaylistState: StateFlow<DuplicatePlaylistState> = _duplicatePlaylistState.asStateFlow()

    private val _pendingDownloadItems = MutableStateFlow<List<StreamingItem>>(emptyList())
    val pendingDownloadItems: StateFlow<List<StreamingItem>> = _pendingDownloadItems.asStateFlow()

    private val _resolvingUrlId = MutableStateFlow<Int?>(null)
    val resolvingUrlId: StateFlow<Int?> = _resolvingUrlId.asStateFlow()

    private val _extractionError = MutableStateFlow<String?>(null)
    val extractionError: StateFlow<String?> = _extractionError.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _onlineSearchResults = MutableStateFlow<List<StreamingItem>>(emptyList())
    val onlineSearchResults: StateFlow<List<StreamingItem>> = _onlineSearchResults.asStateFlow()

    val spotifyCrawlPipeline = repository.spotifyCrawlPipeline
    val spotifyCrawlState = repository.spotifyCrawlPipeline.pipelineState

    private val _activeSpotifyCrawlUrl = MutableStateFlow<String?>(null)
    val activeSpotifyCrawlUrl: StateFlow<String?> = _activeSpotifyCrawlUrl.asStateFlow()

    fun startSpotifyCrawl(url: String) {
        viewModelScope.launch {
            try {
                _isExtracting.value = true
                _extractionStatus.value = "Preflighting Spotify Playlist..."
                val preflight = repository.spotifyCrawlPipeline.sanitizeAndPreflight(url)

                if (preflight.route == com.example.song.data.crawler.SpotifyCrawlPipeline.PreflightRoute.UNAVAILABLE) {
                    _extractionError.value = "Spotify Playlist Unavailable or Deleted"
                    return@launch
                }

                repository.spotifyCrawlPipeline.prepareCrawl(
                    playlistId = preflight.playlistId,
                    initialExpectedCount = preflight.expectedCount,
                    title = preflight.title
                )
                _activeSpotifyCrawlUrl.value = preflight.cleanUrl
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed launching Spotify crawler", e)
                _extractionError.value = "Failed to launch Spotify crawler: ${e.message}"
            } finally {
                _isExtracting.value = false
                _extractionStatus.value = null
            }
        }
    }

    fun dismissSpotifyCrawl() {
        _activeSpotifyCrawlUrl.value = null
        repository.spotifyCrawlPipeline.stopCrawl()
    }

    fun resumeSpotifyCrawl(playlistId: String, url: String) {
        viewModelScope.launch {
            try {
                repository.spotifyCrawlPipeline.prepareCrawl(
                    playlistId = playlistId,
                    initialExpectedCount = null,
                    title = null
                )
                // Force reload trigger
                val current = _activeSpotifyCrawlUrl.value
                _activeSpotifyCrawlUrl.value = null
                delay(50)
                _activeSpotifyCrawlUrl.value = current ?: url
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed resuming Spotify crawl", e)
            }
        }
    }

    fun importCapturedSpotifyPlaylist(playlistId: String) {
        viewModelScope.launch {
            try {
                val items = repository.convertSpotifyCrawlToStreamingItems(playlistId)
                if (items.isNotEmpty()) {
                    repository.insertStreamingItems(items)
                    PulseLogger.log("Imported ${items.size - 1} tracks from Spotify playlist.")
                }
            } catch (e: Exception) {
                Log.e("SongViewModel", "Error importing captured Spotify playlist", e)
            } finally {
                _activeSpotifyCrawlUrl.value = null
            }
        }
    }

    private val _isOnlineSearching = MutableStateFlow(false)
    val isOnlineSearching: StateFlow<Boolean> = _isOnlineSearching.asStateFlow()

    private val _itemActionStates = MutableStateFlow<Map<String, ItemActionState>>(emptyMap())
    val itemActionStates: StateFlow<Map<String, ItemActionState>> = _itemActionStates.asStateFlow()

    val systemLogs: StateFlow<List<String>> = PulseLogger.logs
    val currentTask: StateFlow<String?> = PulseLogger.currentTask
    val cachedKeys: StateFlow<Set<String>> = com.example.song.SongApplication.getInstance().cachedKeys

    // 🛡️ Debug Settings
    private val _isArrangeModeEnabled = MutableStateFlow(false)
    val isArrangeModeEnabled: StateFlow<Boolean> = _isArrangeModeEnabled.asStateFlow()

    private val _globalScrollOffset = MutableStateFlow(0f)
    val globalScrollOffset: StateFlow<Float> = _globalScrollOffset.asStateFlow()

    private val _selectedSongIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedSongIds: StateFlow<Set<Int>> = _selectedSongIds.asStateFlow()

    private val _selectedStreamingIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedStreamingIds: StateFlow<Set<Int>> = _selectedStreamingIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = combine(_selectedSongIds, _selectedStreamingIds) { songs, streaming ->
        songs.isNotEmpty() || streaming.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var isUserSeeking = false

    val filteredSongs = combine(
        _allSongs, 
        _searchQuery, 
        repository.allSongIdsInPlaylists
    ) { songs, query, idsInPlaylists ->
        val playlistIds = idsInPlaylists.toSet()
        val librarySongs = songs.filter { !playlistIds.contains(it.id) }
        
        if (query.isBlank()) librarySongs
        else librarySongs.filter { 
            it.title.contains(query, ignoreCase = true) || 
            it.artist.contains(query, ignoreCase = true) 
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            repository.cleanUpDuplicateSingleSongs()
        }
        viewModelScope.launch {
            repository.allSongs.collect { songs ->
                _allSongs.value = songs
                val currentId = _currentPlayingSong.value?.id 
                    ?: mediaController?.currentMediaItem?.mediaId?.toIntOrNull()
                
                if (currentId != null) {
                    songs.find { it.id == currentId }?.let { updated ->
                        _currentPlayingSong.value = updated
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.allPlaylists.collect { _playlists.value = it }
        }
        viewModelScope.launch {
            repository.favoriteSongs.collect { _favoriteSongs.value = it }
        }
        viewModelScope.launch {
            repository.topLevelStreamingItems.collect { items ->
                // Show all playlists and single songs (agnostic of parentPlaylistUrl)
                _topLevelStreamingItems.value = items
            }
        }
        viewModelScope.launch {
            repository.allStreamingSongs.collect { items ->
                _allStreamingSongs.value = items
            }
        }

        initMediaController(application)
    }

    fun toggleArrangeMode(enabled: Boolean) {
        _isArrangeModeEnabled.value = enabled
        PulseLogger.log("Arrange Mode: $enabled")
    }

    fun updateGlobalScrollOffset(offset: Float) {
        _globalScrollOffset.value = offset
    }

    fun getItemsForStreamingPlaylist(playlistUrl: String): Flow<List<StreamingItem>> {
        return repository.getItemsForStreamingPlaylist(playlistUrl)
    }

    fun fetchStreamingMetadata(url: String) {
        viewModelScope.launch {
            if (!isConnectedToInternet()) {
                _extractionError.value = "App is offline. Please check your Internet connection."
                return@launch
            }

            if (url.contains("spotify.com/playlist") || url.contains("spotify.link") || url.contains("spotify:playlist:")) {
                startSpotifyCrawl(url)
                return@launch
            }

            _isExtracting.value = true
            _extractionError.value = null
            _extractionProgress.value = 0.15f
            _extractionStatus.value = if (url.contains("spotify.com")) {
                "Resolving Spotify track metadata..."
            } else if (url.contains("/playlist") || url.contains("list=")) {
                "Fetching YouTube playlist stream info..."
            } else {
                "Connecting to media link..."
            }
            PulseLogger.log("Searching URL: $url")

            val tickerJob = viewModelScope.launch {
                delay(300)
                _extractionProgress.value = 0.35f
                val stages = listOf(0.45f, 0.58f, 0.70f, 0.82f, 0.88f)
                for (stage in stages) {
                    delay(500)
                    if (_isExtracting.value) {
                        _extractionProgress.value = stage
                    } else break
                }
            }

            try {
                val items = if (url.contains("spotify.com")) {
                    SpotifyResolver.resolve(url, repository)
                } else {
                    YoutubeStreamHandler.getMetadata(url)
                }
                
                tickerJob.cancel()
                _extractionProgress.value = 0.95f
                Log.d("SongViewModel", "Extraction results for $url: ${items.size} items")
                PulseLogger.log("Found ${items.size} items for extraction.")
                _extractionStatus.value = "Found ${items.size} track${if (items.size > 1) "s" else ""}! Finalizing..."
                _extractionProgress.value = 1.00f
                delay(350)
                
                if (items.isEmpty()) {
                    _extractionError.value = if (url.contains("spotify.com")) "Could not find this track on YouTube" else "No videos found in this URL"
                    return@launch
                }

                val isCollection = items.any { it.isPlaylist } || url.contains("/playlist/") || url.contains("/album/") || url.contains("list=")
                
                if (items.size == 1 && !isCollection) {
                    val singleSong = items.first()
                    val existing = repository.getTopLevelSingleSong(singleSong.youtubeUrl, singleSong.title, singleSong.artist)
                    if (existing != null) {
                        _duplicatePlaylistState.value = DuplicatePlaylistState.AlreadyExists(singleSong.title)
                    } else {
                        repository.insertStreamingItems(items)
                    }
                } else {
                    _pendingStreamingItems.value = items
                }
            } catch (e: Exception) {
                tickerJob.cancel()
                val errorMsg = e.localizedMessage ?: ""
                PulseLogger.log("Extraction error: $errorMsg", isError = true)
                CrashTracker.recordException(
                    throwable = e,
                    breadcrumb = "YouTube extraction error",
                    customKeys = mapOf("extraction_url" to url)
                )
                val isOffline = !isConnectedToInternet() ||
                               e is UnknownHostException ||
                               errorMsg.contains("Unable to resolve host", ignoreCase = true) == true ||
                               errorMsg.contains("No address associated", ignoreCase = true) == true ||
                               errorMsg.contains("Network is unreachable", ignoreCase = true) == true

                _extractionError.value = if (isOffline) {
                    "App is offline. Please check your Internet connection."
                } else {
                    when {
                        errorMsg.contains("429") -> "YouTube is rate-limiting requests. Please try again in a few minutes."
                        errorMsg.contains("confirm you're not a bot") -> "Bot detection triggered. Try a different link or wait."
                        else -> "Extraction failed: $errorMsg"
                    }
                }
                e.printStackTrace()
            } finally {
                _isExtracting.value = false
                _extractionStatus.value = null
                _extractionProgress.value = null
            }
        }
    }

    fun addPendingStreamingItems(asCollection: Boolean) {
        viewModelScope.launch {
            val items = _pendingStreamingItems.value
            if (items.isEmpty()) return@launch

            if (asCollection) {
                val playlistHeader = items.find { it.isPlaylist }
                if (playlistHeader != null) {
                    val existingPlaylist = repository.getPlaylistByUrl(playlistHeader.youtubeUrl)
                    if (existingPlaylist != null) {
                        val existingTracks = repository.getItemsForPlaylistSync(playlistHeader.youtubeUrl)
                        val newTracks = items.filter { !it.isPlaylist }
                        val existingUrls = existingTracks.map { it.youtubeUrl }.toSet()
                        val newUrls = newTracks.map { it.youtubeUrl }.toSet()

                        _pendingStreamingItems.value = emptyList()

                        if (existingTracks.size == newTracks.size && existingUrls == newUrls) {
                            _duplicatePlaylistState.value = DuplicatePlaylistState.AlreadyExists(existingPlaylist.title)
                        } else {
                            _duplicatePlaylistState.value = DuplicatePlaylistState.UpdateAvailable(
                                existingPlaylist = existingPlaylist,
                                newHeader = playlistHeader,
                                newTracks = newTracks,
                                existingCount = existingTracks.size,
                                newCount = newTracks.size
                            )
                        }
                        return@launch
                    }
                }
                repository.insertStreamingItems(items)
            } else {
                val filteredItems = items.filter { !it.isPlaylist }.map { 
                    it.copy(parentPlaylistUrl = null) 
                }
                val nonDuplicates = filteredItems.filter { item ->
                    repository.getTopLevelSingleSong(item.youtubeUrl, item.title, item.artist) == null
                }
                if (nonDuplicates.isNotEmpty()) {
                    repository.insertStreamingItems(nonDuplicates)
                } else {
                    val firstItem = filteredItems.firstOrNull()
                    if (firstItem != null) {
                        _duplicatePlaylistState.value = DuplicatePlaylistState.AlreadyExists(firstItem.title)
                    }
                }
            }
            _pendingStreamingItems.value = emptyList()
        }
    }

    fun confirmUpdatePlaylist() {
        viewModelScope.launch {
            val state = _duplicatePlaylistState.value
            if (state is DuplicatePlaylistState.UpdateAvailable) {
                repository.smartMergePlaylist(
                    existingPlaylist = state.existingPlaylist,
                    newPlaylistHeader = state.newHeader,
                    newTracks = state.newTracks
                )
                _duplicatePlaylistState.value = DuplicatePlaylistState.Idle
            }
        }
    }

    fun clearDuplicatePlaylistState() {
        _duplicatePlaylistState.value = DuplicatePlaylistState.Idle
    }

    fun clearPendingStreamingItems() {
        _pendingStreamingItems.value = emptyList()
    }

    fun deleteStreamingItem(item: StreamingItem) {
        viewModelScope.launch {
            repository.deleteStreamingItem(item)
        }
    }

    fun updateStreamingItemTitle(itemId: Int, newTitle: String) {
        viewModelScope.launch {
            repository.updateStreamingItemTitle(itemId, newTitle)
        }
    }

    fun addStreamingItemToPlaylist(itemId: Int, playlistUrl: String?) {
        viewModelScope.launch {
            if (playlistUrl != null) {
                val existingItem = repository.getStreamingItemById(itemId)
                if (existingItem != null) {
                    val currentPlaylistItems = repository.getItemsForStreamingPlaylist(playlistUrl).first()
                    val minPosition = currentPlaylistItems.minOfOrNull { it.position } ?: 0

                    if (existingItem.parentPlaylistUrl == null) {
                        // Create a copy at the TOP (beginning) of the playlist so original stays in Recommended
                        val playlistCopy = existingItem.copy(
                            id = 0, // Room auto-generates a new primary key
                            parentPlaylistUrl = playlistUrl,
                            position = minPosition - 1
                        )
                        repository.insertStreamingItems(listOf(playlistCopy))
                    } else {
                        val updated = existingItem.copy(
                            parentPlaylistUrl = playlistUrl,
                            position = minPosition - 1
                        )
                        repository.updateStreamingItems(listOf(updated))
                    }
                }
            } else {
                repository.updateStreamingItemParentPlaylist(itemId, playlistUrl)
            }
        }
    }

    fun addStreamingItems(items: List<StreamingItem>) {
        viewModelScope.launch {
            repository.insertStreamingItems(items)
        }
    }

    fun updateStreamingItems(items: List<StreamingItem>) {
        viewModelScope.launch {
            repository.updateStreamingItems(items)
        }
    }

    fun updateSongs(songs: List<Song>) {
        viewModelScope.launch {
            val updatedSongs = songs.mapIndexed { index, song ->
                song.copy(position = index)
            }
            repository.updateSongs(updatedSongs)
        }
    }

    fun reorderFavorites(songs: List<Song>) {
        viewModelScope.launch {
            // Assign sequential positions starting from 0
            val updatedSongs = songs.mapIndexed { index, song ->
                song.copy(position = index)
            }
            
            // 1. Update local songs positions
            val localSongsToUpdate = updatedSongs.filter { it.id < 1_000_000 }
            if (localSongsToUpdate.isNotEmpty()) {
                repository.updateSongs(localSongsToUpdate)
            }
            
            // 2. Update streaming items positions
            val streamingSongs = updatedSongs.filter { it.id >= 1_000_000 }
            if (streamingSongs.isNotEmpty()) {
                val allStreaming = repository.allStreamingSongs.first()
                val updatedStreamingItems = allStreaming.map { item ->
                    val songEquivalent = streamingSongs.find { it.id == (1_000_000 + item.id) }
                    if (songEquivalent != null) {
                        item.copy(position = songEquivalent.position)
                    } else item
                }
                repository.updateStreamingItems(updatedStreamingItems)
            }
        }
    }

    fun updatePlaylistSongOrder(playlistId: Int, songs: List<Song>) {
        viewModelScope.launch {
            repository.updatePlaylistSongOrder(playlistId, songs)
        }
    }

    fun moveStreamingItem(fromIndex: Int, toIndex: Int, currentItems: List<StreamingItem>) {
        viewModelScope.launch {
            val newList = currentItems.toMutableList()
            val item = newList.removeAt(fromIndex)
            newList.add(toIndex, item)
            
            val updatedItems = newList.mapIndexed { index, streamingItem ->
                streamingItem.copy(position = index)
            }
            repository.updateStreamingItems(updatedItems)
        }
    }

    fun movePlaylistSong(playlistId: Int, fromIndex: Int, toIndex: Int, currentSongs: List<Song>) {
        viewModelScope.launch {
            val newList = currentSongs.toMutableList()
            val song = newList.removeAt(fromIndex)
            newList.add(toIndex, song)
            
            repository.updatePlaylistSongOrder(playlistId, newList)
        }
    }

    fun initMediaController(context: Context) {
        if (mediaController != null || isConnecting) return
        isConnecting = true

        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            isConnecting = false
            try {
                if (future.isDone && !future.isCancelled) {
                    val controller = future.get()
                    mediaController = controller

                    // 🛡️ Queue Restoration on App Reopen / Background Reconnect
                    if (_currentQueue.value.isEmpty() && controller.mediaItemCount > 0) {
                        val restoredSongs = mutableListOf<Song>()
                        for (i in 0 until controller.mediaItemCount) {
                            val item = controller.getMediaItemAt(i)
                            val songId = item.mediaId.toIntOrNull()
                            if (songId != null) {
                                val song = _allSongs.value.find { it.id == songId }
                                    ?: _allStreamingSongs.value.find { (1_000_000 + it.id) == songId }?.let {
                                        Song(
                                            id = 1_000_000 + it.id,
                                            title = it.title,
                                            artist = it.artist ?: "Unknown Artist",
                                            audioUri = it.youtubeUrl,
                                            imageUrl = it.thumbnailUrl,
                                            duration = it.duration
                                        )
                                    }
                                    ?: Song(
                                        id = songId,
                                        title = item.mediaMetadata.title?.toString() ?: "Unknown",
                                        artist = item.mediaMetadata.artist?.toString() ?: "Unknown",
                                        audioUri = item.mediaMetadata.extras?.getString("youtube_url") ?: "",
                                        imageUrl = item.mediaMetadata.extras?.getString("custom_artwork_url")
                                    )
                                restoredSongs.add(song)
                            }
                        }

                        val curIndex = controller.currentMediaItemIndex
                        if (curIndex in restoredSongs.indices) {
                            _historyStack.value = restoredSongs.subList(0, curIndex)
                            _currentPlayingSong.value = restoredSongs[curIndex]
                            _parentQueue.value = restoredSongs.subList(curIndex + 1, restoredSongs.size).map { QueueItem(song = it) }
                            _sourcePlaylist.value = restoredSongs
                            recomputeCombinedQueue(updateService = false)
                        }
                    }

                    controller.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            mediaItem?.let { item ->
                                val songId = item.mediaId.toIntOrNull()
                                val song = _allSongs.value.find { it.id == songId } ?: Song(
                                    id = songId ?: 0,
                                    title = item.mediaMetadata.title?.toString() ?: "Unknown",
                                    artist = item.mediaMetadata.artist?.toString() ?: "Unknown",
                                    audioUri = item.mediaMetadata.extras?.getString("youtube_url") ?: "",
                                    imageUrl = item.mediaMetadata.extras?.getString("custom_artwork_url")
                                )
                                
                                // 1. PRUNE FIRST (while _currentPlayingSong still holds the OLD song)
                                if (songId != null) {
                                    pruneActiveSongFromQueue(songId)
                                }

                                // 2. UPDATE STATE SECOND
                                _currentPlayingSong.value = song
                                _duration.value = controller.duration.coerceAtLeast(0L)
                                PulseLogger.log("Track transition: ${song.title}")
                            }
                        }

                        override fun onIsPlayingChanged(playing: Boolean) {
                            _isPlaying.value = playing
                            PulseLogger.log("Playback state: ${if (playing) "Playing" else "Paused"}")
                        }

                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int
                        ) {
                            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || reason == Player.DISCONTINUITY_REASON_SKIP) {
                                _currentPosition.value = 0L
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                _duration.value = controller.duration.coerceAtLeast(0L)
                                _currentPosition.value = controller.currentPosition
                                _playbackError.value = null
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val currentItem = controller.currentMediaItem
                            val uriString = currentItem?.localConfiguration?.uri?.toString() ?: ""
                            
                            if (uriString.contains("pulse_placeholder:")) {
                                PulseLogger.log("Masked placeholder error. Resolving JIT...")
                                return
                            }

                            Log.e("SongViewModel", "Playback error: ${error.message}", error)
                            PulseLogger.log("Engine error: ${error.localizedMessage}", isError = true)
                            CrashTracker.recordException(
                                throwable = error,
                                breadcrumb = "ExoPlayer playback error",
                                customKeys = mapOf(
                                    "media_title" to (currentItem?.mediaMetadata?.title?.toString() ?: "Unknown"),
                                    "media_uri" to uriString,
                                    "error_code" to error.errorCodeName
                                )
                            )
                            
                            val isOffline = !isConnectedToInternet() || 
                                           error.cause is UnknownHostException ||
                                           error.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                                           error.message?.contains("No address associated", ignoreCase = true) == true ||
                                           error.message?.contains("Network is unreachable", ignoreCase = true) == true

                            _playbackError.value = if (isOffline) {
                                "App is offline. Please check your Internet connection."
                            } else {
                                "Playback Error: ${error.localizedMessage}"
                            }
                            _isPlaying.value = false
                        }

                        override fun onRepeatModeChanged(repeatMode: Int) {
                            _repeatMode.value = repeatMode
                        }
                    })

                    _repeatMode.value = controller.repeatMode
                    _isPlaying.value = controller.isPlaying
                    _currentPlayingSong.value = controller.currentMediaItem?.let { item ->
                        val songId = item.mediaId.toIntOrNull()
                        _allSongs.value.find { it.id == songId } ?: Song(
                            id = songId ?: 0,
                            title = item.mediaMetadata.title?.toString() ?: "Unknown",
                            artist = item.mediaMetadata.artist?.toString() ?: "Unknown",
                            audioUri = item.mediaMetadata.extras?.getString("youtube_url") ?: "",
                            imageUrl = item.mediaMetadata.extras?.getString("custom_artwork_url")
                        )
                    }
                    startProgressUpdate()
                }
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to initialize MediaController asynchronously", e)
                CrashTracker.recordException(
                    throwable = e,
                    breadcrumb = "Failed to initialize MediaController asynchronously"
                )
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun getOrInitMediaController(): MediaController? {
        if (mediaController == null || mediaController?.isConnected == false) {
            initMediaController(getApplication())
        }
        return mediaController
    }

    private fun startProgressUpdate() {
        viewModelScope.launch {
            while (isActive) {
                if (!isUserSeeking) {
                    mediaController?.let {
                        _currentPosition.value = it.currentPosition
                        if (_duration.value <= 0) {
                             val dur = it.duration
                             if (dur > 0) _duration.value = dur
                        }
                    }
                }
                delay(500)
            }
        }
    }

    fun setUserSeeking(seeking: Boolean) {
        isUserSeeking = seeking
    }

    fun updateSeekPosition(position: Long) {
        _currentPosition.value = position
    }

    fun seekTo(position: Long) {
        getOrInitMediaController()?.seekTo(position)
        isUserSeeking = false
    }

    fun addSong(song: Song) {
        viewModelScope.launch {
            repository.insertSong(song)
        }
    }

    fun updateFavorite(song: Song, isFavorite: Boolean) {
        viewModelScope.launch {
            if (song.id >= 1_000_000) {
                // Streaming Item Favorite
                val actualId = song.id - 1_000_000
                repository.updateStreamingItemFavorite(actualId, isFavorite)
            } else {
                // Local Song Favorite
                val updatedSong = song.copy(isFavorite = isFavorite)
                repository.updateSong(updatedSong)
            }
            
            if (_currentPlayingSong.value?.id == song.id) {
                _currentPlayingSong.value = song.copy(isFavorite = isFavorite)
            }
        }
    }

    fun toggleStreamingFavorite(item: StreamingItem) {
        viewModelScope.launch {
            repository.updateStreamingItemFavorite(item.id, !item.isFavorite)
        }
    }

    fun deleteSong(songId: Int) {
        viewModelScope.launch {
            repository.deleteSong(songId)
            if (_currentPlayingSong.value?.id == songId) {
                mediaController?.stop()
                _currentPlayingSong.value = null
            }
        }
    }

    private val _selectionRange = MutableStateFlow<SelectionRange?>(null)

    data class SelectionRange(
        val startId: Int,
        val initialSelectedIds: Set<Int>,
        val isAdding: Boolean
    )

    fun startRangeSelection(songId: Int, allItems: List<Int>, isStreaming: Boolean = false) {
        val currentSelected = if (isStreaming) _selectedStreamingIds.value else _selectedSongIds.value
        val isAdding = !currentSelected.contains(songId)
        
        _selectionRange.value = SelectionRange(songId, currentSelected, isAdding)
        updateRangeSelection(songId, allItems, isStreaming)
    }

    fun updateRangeSelection(currentId: Int, allItems: List<Int>, isStreaming: Boolean = false) {
        val range = _selectionRange.value ?: return

        if (allItems.isEmpty()) return

        val startIndex = allItems.indexOf(range.startId)
        val currentIndex = allItems.indexOf(currentId)

        if (startIndex == -1 || currentIndex == -1) return

        val fromIndex = minOf(startIndex, currentIndex)
        val toIndex = maxOf(startIndex, currentIndex)
        val rangeIds = allItems.subList(fromIndex, toIndex + 1).toSet()

        if (isStreaming) {
            val newSelection = if (range.isAdding) {
                range.initialSelectedIds + rangeIds
            } else {
                range.initialSelectedIds - rangeIds
            }
            _selectedStreamingIds.value = newSelection
        } else {
            val newSelection = if (range.isAdding) {
                range.initialSelectedIds + rangeIds
            } else {
                range.initialSelectedIds - rangeIds
            }
            _selectedSongIds.value = newSelection
        }
    }

    fun endRangeSelection() {
        _selectionRange.value = null
    }

    fun toggleSelectionMode(enabled: Boolean) {
        if (!enabled) {
            _selectedSongIds.value = emptySet()
            _selectedStreamingIds.value = emptySet()
        }
    }

    fun toggleSongSelection(songId: Int) {
        val current = _selectedSongIds.value.toMutableSet()
        if (current.contains(songId)) {
            current.remove(songId)
        } else {
            current.add(songId)
        }
        _selectedSongIds.value = current
    }

    fun toggleStreamingSelection(itemId: Int) {
        val current = _selectedStreamingIds.value.toMutableSet()
        if (current.contains(itemId)) {
            current.remove(itemId)
        } else {
            current.add(itemId)
        }
        _selectedStreamingIds.value = current
    }

    fun selectSong(songId: Int) {
        val current = _selectedSongIds.value.toMutableSet()
        if (current.add(songId)) {
            _selectedSongIds.value = current
        }
    }

    fun selectStreamingItem(itemId: Int) {
        val current = _selectedStreamingIds.value.toMutableSet()
        if (current.add(itemId)) {
            _selectedStreamingIds.value = current
        }
    }

    fun deleteSelectedItems() {
        viewModelScope.launch {
            val songIds = _selectedSongIds.value.toList()
            val streamingIds = _selectedStreamingIds.value.toList()

            songIds.forEach { id ->
                repository.deleteSong(id)
                if (_currentPlayingSong.value?.id == id) {
                    mediaController?.stop()
                    _currentPlayingSong.value = null
                }
            }

            streamingIds.forEach { id ->
                repository.deleteStreamingItemById(id)
            }

            toggleSelectionMode(false)
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
        }
    }

    fun updatePlaylistPositions(playlists: List<Playlist>) {
        viewModelScope.launch {
            repository.updatePlaylistPositions(playlists)
        }
    }

    fun deletePlaylist(playlistId: Int) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun addSongToPlaylist(songId: Int, playlistId: Int) {
        viewModelScope.launch {
            repository.addSongToPlaylist(songId, playlistId)
        }
    }

    fun removeSongFromPlaylist(songId: Int, playlistId: Int) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(songId, playlistId)
        }
    }

    fun removeSelectedFromPlaylist(playlistId: Int) {
        viewModelScope.launch {
            val songIds = _selectedSongIds.value.toList()
            songIds.forEach { id ->
                repository.removeSongFromPlaylist(id, playlistId)
            }
            toggleSelectionMode(false)
        }
    }

    fun getSongsInPlaylist(playlistId: Int): Flow<List<Song>> {
        return repository.getSongsInPlaylist(playlistId)
    }

    private fun pruneActiveSongFromQueue(activeSongId: Int) {
        if (_repeatMode.value == Player.REPEAT_MODE_ONE && _currentPlayingSong.value?.id == activeSongId) {
            return
        }

        val currentSong = _currentPlayingSong.value
        val history = _historyStack.value.toMutableList()
        val manual = _manualQueue.value.toMutableList()
        val parent = _parentQueue.value.toMutableList()

        val historyMatchIdx = history.indexOfLast { it.id == activeSongId }

        if (historyMatchIdx != -1) {
            // 🔙 BACKWARD TRANSITION: We jumped back to a song in history
            val poppedFromHistory = history.subList(historyMatchIdx + 1, history.size)
            val itemsToReinsert = mutableListOf<Song>()
            itemsToReinsert.addAll(poppedFromHistory)

            if (currentSong != null && currentSong.id != activeSongId) {
                itemsToReinsert.add(currentSong)
            }

            // Restore user-queued items back into manualQueue
            val manualReinsert = itemsToReinsert.filter { song ->
                _sourcePlaylist.value.none { it.id == song.id }
            }
            val parentReinsert = itemsToReinsert.filter { song -> song !in manualReinsert }

            if (manualReinsert.isNotEmpty()) {
                _manualQueue.value = manualReinsert.map { QueueItem(song = it, isUserQueued = true) } + manual
            }
            _parentQueue.value = parentReinsert.map { QueueItem(song = it) } + parent
            _historyStack.value = history.subList(0, historyMatchIdx)
        } else {
            // 🔜 FORWARD TRANSITION: Record the old song to history
            if (currentSong != null && currentSong.id != activeSongId) {
                if (history.isEmpty() || history.last().id != currentSong.id) {
                    history.add(currentSong)
                }
            }

            val inManual = manual.indexOfFirst { it.song.id == activeSongId }
            val inParent = parent.indexOfFirst { it.song.id == activeSongId }

            if (inManual != -1) {
                // Landed in Manual Queue: Move skipped manual items to history
                val skippedManual = manual.subList(0, inManual).map { it.song }
                history.addAll(skippedManual)
                _manualQueue.value = manual.drop(inManual + 1)
            } else if (inParent != -1) {
                // Landed in Parent Queue: We skipped all remaining manual items, move them to history
                val skippedManual = manual.map { it.song }
                history.addAll(skippedManual)
                _manualQueue.value = emptyList()

                val skippedParent = parent.subList(0, inParent).map { it.song }
                history.addAll(skippedParent)
                _parentQueue.value = parent.drop(inParent + 1)
            }
            _historyStack.value = history
        }

        // 🛡️ Do NOT trigger updateServiceQueue during an ExoPlayer transition!
        recomputeCombinedQueue(updateService = false)
    }

    private fun recomputeCombinedQueue(updateService: Boolean = true) {
        val activeSong = _currentPlayingSong.value
        val manualItems = _manualQueue.value
        var parentItems = _parentQueue.value

        // 🛡️ Fallback Context Protection: If parentQueue is empty but we have a sourcePlaylist, recover upcoming tracks
        if (parentItems.isEmpty() && _sourcePlaylist.value.isNotEmpty() && activeSong != null) {
            val source = _sourcePlaylist.value
            val matchIdx = source.indexOfFirst { it.id == activeSong.id }
            if (matchIdx != -1) {
                var recoveredSongs = if (matchIdx + 1 < source.size) {
                    source.subList(matchIdx + 1, source.size)
                } else if (_repeatMode.value == Player.REPEAT_MODE_ALL && source.size > 1) {
                    // 🛡️ Edge Case Guard: Wrap around to index 0 when Repeat All is enabled and list reaches the end
                    source.filter { it.id != activeSong.id }
                } else {
                    emptyList()
                }

                if (recoveredSongs.isNotEmpty()) {
                    if (_isShuffleEnabled.value && recoveredSongs.size > 1) {
                        recoveredSongs = recoveredSongs.shuffled()
                    }
                    parentItems = recoveredSongs.map { QueueItem(song = it) }
                    _parentQueue.value = parentItems
                }
            }
        }

        // 🛡️ CRITICAL ORDER RULE: Manual items MUST play in exact user-queued order BEFORE parent items!
        val combinedSongs = if (_isShuffleEnabled.value) {
            val shuffledParentSongs = parentItems.map { it.song }.shuffled()
            listOfNotNull(activeSong) + manualItems.map { it.song } + shuffledParentSongs
        } else {
            listOfNotNull(activeSong) + manualItems.map { it.song } + parentItems.map { it.song }
        }

        _currentQueue.value = combinedSongs

        if (updateService) {
            updateServiceQueue()
        }
    }

    fun playSong(song: Song, queue: List<Song> = _allSongs.value, contextTitle: String? = null) {
        if (queue.isEmpty()) return
        
        contextTitle?.let { _queueContextTitle.value = it } ?: run { _queueContextTitle.value = "Playing \"${song.title}\"" }

        _sourcePlaylist.value = queue
        _manualQueue.value = emptyList()

        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

        // 1. Preceding songs go to history stack
        val pastSongs = if (index > 0) queue.subList(0, index) else emptyList()
        _historyStack.value = pastSongs

        // 2. Remaining upcoming songs go to parent queue
        val remainingSongs = if (index + 1 < queue.size) queue.subList(index + 1, queue.size) else emptyList()
        _parentQueue.value = remainingSongs.map { QueueItem(song = it, isUserQueued = false) }

        _currentPlayingSong.value = song
        PulseLogger.log("Playing local song: ${song.title}")

        // 🛡️ PREVENT DOUBLE-COMMAND CRASH: Suppress UPDATE_QUEUE temporarily
        isPreparingNewSession = true
        recomputeCombinedQueue()
        isPreparingNewSession = false

        val fullTimeline = _historyStack.value + _currentQueue.value
        MusicQueueCache.setQueue(fullTimeline)

        val ids = fullTimeline.mapTo(ArrayList()) { it.id }
        lastSentTimelineIds = ArrayList(ids)

        getOrInitMediaController()?.let { controller ->
            val args = Bundle().apply {
                putIntegerArrayList("ids", ids)
                putInt("index", _historyStack.value.size)
                putBoolean("isStreaming", false)
                if (fullTimeline.size <= 150) {
                    putParcelableArrayList("songs", ArrayList(fullTimeline))
                }
            }
            controller.sendCustomCommand(SessionCommand("PLAY_QUEUE", Bundle.EMPTY), args)
        }
    }

    private fun StreamingItem.toMediaItem(directUrl: String? = null): MediaItem {
        val bundle = Bundle().apply {
            putString("youtube_url", youtubeUrl)
            putString("custom_artwork_url", thumbnailUrl) 
        }
        
        val safeUriString = if (!directUrl.isNullOrEmpty() && directUrl.startsWith("http")) {
            directUrl
        } else {
            "asset:///pulse_placeholder_$id.mp3"
        }
        
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(Uri.parse(safeUriString))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist ?: "YouTube")
                    .setExtras(bundle)
                    .build()
            )
            .build()
    }

    fun playStreamingItem(item: StreamingItem, queue: List<StreamingItem>, contextTitle: String? = null) {
        val filteredQueue = queue.filter { !it.isPlaylist }
        val mappedQueue = filteredQueue.map {
            Song(
                id = 1_000_000 + it.id,
                title = it.title,
                artist = it.artist ?: "Unknown Artist",
                audioUri = it.youtubeUrl,
                imageUrl = it.thumbnailUrl,
                isFavorite = it.isFavorite,
                duration = it.duration
            )
        }

        contextTitle?.let { _queueContextTitle.value = it } ?: run { _queueContextTitle.value = "Playing \"${item.title}\"" }

        val targetSong = mappedQueue.find { it.id == (1_000_000 + item.id) } ?: mappedQueue.firstOrNull() ?: return

        _sourcePlaylist.value = mappedQueue
        _manualQueue.value = emptyList()

        val index = mappedQueue.indexOfFirst { it.id == targetSong.id }.coerceAtLeast(0)

        // 1. Preceding songs go to history stack
        val pastSongs = if (index > 0) mappedQueue.subList(0, index) else emptyList()
        _historyStack.value = pastSongs

        // 2. Remaining upcoming songs go to parent queue
        val remainingSongs = if (index + 1 < mappedQueue.size) mappedQueue.subList(index + 1, mappedQueue.size) else emptyList()
        _parentQueue.value = remainingSongs.map { QueueItem(song = it, isUserQueued = false) }

        _currentPlayingSong.value = targetSong
        PulseLogger.log("Playing streaming item: ${item.title}")

        // 🛡️ PREVENT DOUBLE-COMMAND CRASH: Suppress UPDATE_QUEUE temporarily
        isPreparingNewSession = true
        recomputeCombinedQueue()
        isPreparingNewSession = false

        val fullTimeline = _historyStack.value + _currentQueue.value
        MusicQueueCache.setQueue(fullTimeline)

        val ids = fullTimeline.mapTo(ArrayList()) { it.id }
        lastSentTimelineIds = ArrayList(ids)

        getOrInitMediaController()?.let { controller ->
            val args = Bundle().apply {
                putIntegerArrayList("ids", ids)
                putInt("index", _historyStack.value.size)
                putBoolean("isStreaming", true)
                if (fullTimeline.size <= 150) {
                    putParcelableArrayList("songs", ArrayList(fullTimeline))
                }
            }
            controller.sendCustomCommand(SessionCommand("PLAY_QUEUE", Bundle.EMPTY), args)
        }
    }

    fun openQueueSheet() {
        _showQueueSheet.value = true
    }

    fun closeQueueSheet() {
        _showQueueSheet.value = false
    }

    fun openSongOptions(song: Song) {
        _selectedSongForOptions.value = song
    }

    fun closeSongOptions() {
        _selectedSongForOptions.value = null
    }

    fun clearQueueSnackbar() {
        _queueSnackbarMessage.value = null
    }

    fun updateQueueContextTitle(title: String) {
        _queueContextTitle.value = title
    }

    fun addToQueue(songs: List<Song>, playNext: Boolean = false, contextTitle: String? = null) {
        if (songs.isEmpty()) return

        contextTitle?.let { _queueContextTitle.value = it }

        val newItems = songs.map { QueueItem(song = it, isUserQueued = true) }

        if (_currentQueue.value.isEmpty()) {
            _manualQueue.value = newItems
            if (_currentPlayingSong.value == null) {
                playSong(songs.first(), songs, contextTitle)
                return
            }
        } else {
            val currentManual = _manualQueue.value.toMutableList()
            if (playNext) {
                currentManual.addAll(0, newItems)
            } else {
                currentManual.addAll(newItems)
            }
            _manualQueue.value = currentManual
        }

        // 🛡️ Reset lastSentTimelineIds to force updateServiceQueue to sync timeline with ExoPlayer
        lastSentTimelineIds = emptyList()
        recomputeCombinedQueue()

        val text = if (songs.size == 1) {
            "Added \"${songs.first().title}\" to Queue"
        } else {
            "Added ${songs.size} songs to Queue"
        }
        _queueSnackbarMessage.value = text
    }

    fun addSelectedToQueue(playNext: Boolean = false) {
        val selectedSongIdsSet = _selectedSongIds.value
        val selectedStreamingIdsSet = _selectedStreamingIds.value

        val localSongs = _allSongs.value.filter { it.id in selectedSongIdsSet }
        val streamingSongs = _allStreamingSongs.value.filter { it.id in selectedStreamingIdsSet }.map {
            Song(
                id = 1_000_000 + it.id,
                title = it.title,
                artist = it.artist ?: "Unknown Artist",
                audioUri = it.youtubeUrl,
                imageUrl = it.thumbnailUrl,
                duration = it.duration
            )
        }

        val allSelected = localSongs + streamingSongs
        if (allSelected.isNotEmpty()) {
            addToQueue(allSelected, playNext = playNext)
            toggleSelectionMode(false)
        }
    }

    fun removeFromQueueByQueueId(queueId: String) {
        _manualQueue.value = _manualQueue.value.filter { it.queueId != queueId }
        _parentQueue.value = _parentQueue.value.filter { it.queueId != queueId }
        recomputeCombinedQueue()
    }

    fun removeFromQueue(index: Int) {
        val currentList = _currentQueue.value.toMutableList()
        if (index in currentList.indices) {
            val removedSong = currentList.removeAt(index)
            _manualQueue.value = _manualQueue.value.filter { it.song.id != removedSong.id }
            _parentQueue.value = _parentQueue.value.filter { it.song.id != removedSong.id }
            recomputeCombinedQueue()
        }
    }

    fun reorderManualQueue(fromIndex: Int, toIndex: Int) {
        val list = _manualQueue.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _manualQueue.value = list
            recomputeCombinedQueue()
        }
    }

    fun reorderParentQueue(fromIndex: Int, toIndex: Int) {
        val list = _parentQueue.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _parentQueue.value = list
            recomputeCombinedQueue()
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val currentList = _currentQueue.value.toMutableList()
        if (fromIndex in currentList.indices && toIndex in currentList.indices) {
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)
            _currentQueue.value = currentList
            updateServiceQueue()
        }
    }

    fun updateServiceQueue() {
        // 🛡️ Suppress update if PLAY_QUEUE is about to handle it
        if (isPreparingNewSession) return

        val activeSong = _currentPlayingSong.value
        val historySongs = _historyStack.value
        val queue = _currentQueue.value
        if (queue.isEmpty() && historySongs.isEmpty()) return

        val fullTimeline = historySongs + queue
        MusicQueueCache.setQueue(fullTimeline)

        val ids = fullTimeline.mapTo(ArrayList()) { it.id }

        // 🛡️ INFINITE LOOP FIX: Only notify ExoPlayer if the actual playlist items changed
        if (ids == lastSentTimelineIds) return
        lastSentTimelineIds = ArrayList(ids)

        val currentPlayingId = activeSong?.id
        val index = fullTimeline.indexOfFirst { it.id == currentPlayingId }.coerceAtLeast(historySongs.size)

        getOrInitMediaController()?.let { controller ->
            val args = Bundle().apply {
                putIntegerArrayList("ids", ids)
                putInt("index", index)
                if (fullTimeline.size <= 150) {
                    putParcelableArrayList("songs", ArrayList(fullTimeline))
                }
            }
            controller.sendCustomCommand(SessionCommand("UPDATE_QUEUE", Bundle.EMPTY), args)
        }
    }

    fun clearManualQueue() {
        _manualQueue.value = emptyList()
        recomputeCombinedQueue()
    }

    fun clearQueue() {
        _manualQueue.value = emptyList()
        _parentQueue.value = emptyList()
        _historyStack.value = emptyList()
        _sourcePlaylist.value = emptyList()
        recomputeCombinedQueue()
    }

    fun toggleShuffle() {
        val newShuffleState = !_isShuffleEnabled.value
        _isShuffleEnabled.value = newShuffleState
        recomputeCombinedQueue()
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerMinutes.value = null
            _sleepTimerRemainingMs.value = null
        } else {
            _sleepTimerMinutes.value = minutes
            val totalMs = minutes * 60 * 1000L
            _sleepTimerRemainingMs.value = totalMs

            sleepTimerJob = viewModelScope.launch {
                var remaining = totalMs
                while (remaining > 0 && isActive) {
                    delay(1000L)
                    remaining -= 1000L
                    _sleepTimerRemainingMs.value = remaining
                }
                if (isActive) {
                    mediaController?.pause()
                    _sleepTimerMinutes.value = null
                    _sleepTimerRemainingMs.value = null
                    PulseLogger.log("Sleep timer expired. Playback paused.")
                }
            }
        }
    }

    fun skipToNext() {
        getOrInitMediaController()?.let { controller ->
            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
            } else {
                controller.seekTo(0)
            }
        }
    }

    fun skipToPrevious() {
        val pos = _currentPosition.value
        getOrInitMediaController()?.let { controller ->
            if (pos > 3000L || (!controller.hasPreviousMediaItem() && controller.currentMediaItemIndex == 0)) {
                controller.seekTo(0)
                _currentPosition.value = 0L
                PulseLogger.log("Restarted current track at 00:00")
            } else {
                controller.seekToPreviousMediaItem()
            }
        }
    }

    fun toggleRepeatMode() {
        getOrInitMediaController()?.let {
            val nextMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            it.repeatMode = nextMode
            _repeatMode.value = nextMode
            PulseLogger.log("Repeat mode: $nextMode")
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            clearOnlineSearchResults()
        }
    }

    fun fetchDownloadMetadata(url: String) {
        viewModelScope.launch {
            if (!isConnectedToInternet()) {
                _downloadState.value = DownloadState.Error("App is offline. Please check your Internet connection.")
                delay(2000)
                _downloadState.value = DownloadState.Idle
                return@launch
            }
            _downloadState.value = DownloadState.Checking
            PulseLogger.log("Searching download: $url")
            try {
                val sanitizedUrl = if (url.contains("youtube.com") && url.contains("list=")) {
                    val listId = url.substringAfter("list=").substringBefore("&")
                    "https://www.youtube.com/playlist?list=$listId"
                } else url

                val items = if (url.contains("spotify.com")) {
                    SpotifyResolver.resolve(url, repository)
                } else {
                    YoutubeStreamHandler.getMetadata(sanitizedUrl)
                }
                
                if (items.isEmpty()) {
                    _downloadState.value = DownloadState.Error("Invalid link")
                    delay(2000)
                    _downloadState.value = DownloadState.Idle
                    return@launch
                }

                val isCollection = items.any { it.isPlaylist } || url.contains("/playlist/") || url.contains("/album/") || url.contains("list=")
                
                if (items.size == 1 && !isCollection) {
                    val item = items[0]
                    downloadFromYoutube(
                        url = item.youtubeUrl,
                        overrideTitle = item.title,
                        overrideArtist = item.artist,
                        overrideImageUrl = item.thumbnailUrl
                    )
                } else {
                    _pendingDownloadItems.value = items
                    _downloadState.value = DownloadState.Idle
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val isOffline = !isConnectedToInternet() ||
                               e is UnknownHostException ||
                               (e.localizedMessage?.contains("Unable to resolve host", ignoreCase = true) == true) ||
                               (e.localizedMessage?.contains("No address associated", ignoreCase = true) == true) ||
                               (e.localizedMessage?.contains("Network is unreachable", ignoreCase = true) == true)

                val msg = if (isOffline) "App is offline. Please check your Internet connection." else "Invalid link"
                _downloadState.value = DownloadState.Error(msg)
                delay(2000)
                _downloadState.value = DownloadState.Idle
            }
        }
    }

    // Queue management for sequential downloads
    private val downloadQueue = ConcurrentLinkedQueue<DownloadRequest>()
    private val activeDownloadUrls = Collections.synchronizedSet(mutableSetOf<String>())
    private var queueWorkerJob: Job? = null

    private fun enqueueDownloadRequest(request: DownloadRequest): CompletableDeferred<Unit> {
        val urlKey = request.url.trim()

        synchronized(activeDownloadUrls) {
            if (activeDownloadUrls.contains(urlKey)) {
                PulseLogger.log("Download already active or queued for URL: $urlKey", isError = false)
                val existing = downloadQueue.find { it.url.trim() == urlKey }
                return existing?.deferred ?: request.deferred.apply { complete(Unit) }
            }
            activeDownloadUrls.add(urlKey)
        }

        val currentStates = _itemActionStates.value.toMutableMap()
        currentStates[urlKey] = ItemActionState.Loading
        _itemActionStates.value = currentStates

        downloadQueue.add(request)
        startQueueWorkerIfNeeded()
        return request.deferred
    }

    private fun startQueueWorkerIfNeeded() {
        if (queueWorkerJob?.isActive == true) return

        queueWorkerJob = viewModelScope.launch(Dispatchers.IO) {
            var completedCount = 0

            while (isActive) {
                val request = downloadQueue.poll() ?: break
                val currentTotal = completedCount + downloadQueue.size + 1
                val currentIndex = completedCount + 1

                val isReadyFlow = SongApplication.getInstance().isReady
                if (!isReadyFlow.value) {
                    _downloadState.value = DownloadState.Checking
                    try {
                        withTimeout(15000) {
                            isReadyFlow.first { it }
                        }
                    } catch (e: Exception) {
                        _downloadState.value = DownloadState.Error("Music engine failed to initialize.")
                        onRequestFailed(request, e)
                        delay(2000)
                        if (downloadQueue.isEmpty()) {
                            _downloadState.value = DownloadState.Idle
                        }
                        continue
                    }
                }

                try {
                    PulseLogger.log("Processing queued download ($currentIndex/$currentTotal): ${request.overrideTitle ?: request.url}")
                    _downloadState.value = DownloadState.Downloading(0f, currentIndex, currentTotal)

                    repository.downloadYouTubeAudio(
                        url = request.url,
                        playlistId = request.playlistId,
                        overrideTitle = request.overrideTitle,
                        overrideArtist = request.overrideArtist,
                        overrideImageUrl = request.overrideImageUrl
                    ) { progress, _ ->
                        _downloadState.value = DownloadState.Downloading(progress, currentIndex, currentTotal)
                    }

                    _downloadState.value = DownloadState.Downloading(100f, currentIndex, currentTotal)
                    PulseLogger.log("Finished queued download: ${request.overrideTitle ?: request.url}")

                    val states = _itemActionStates.value.toMutableMap()
                    states[request.url] = ItemActionState.Success
                    _itemActionStates.value = states

                    request.deferred.complete(Unit)
                    completedCount++

                    viewModelScope.launch {
                        delay(3000)
                        if (_itemActionStates.value[request.url] == ItemActionState.Success) {
                            val updated = _itemActionStates.value.toMutableMap()
                            updated.remove(request.url)
                            _itemActionStates.value = updated
                        }
                    }
                } catch (e: Exception) {
                    onRequestFailed(request, e)
                } finally {
                    synchronized(activeDownloadUrls) {
                        activeDownloadUrls.remove(request.url.trim())
                    }
                }
            }

            if (completedCount > 0) {
                _downloadState.value = DownloadState.Success
                delay(1500)
                if (downloadQueue.isEmpty()) {
                    _downloadState.value = DownloadState.Idle
                }
            }
        }
    }

    private fun onRequestFailed(request: DownloadRequest, e: Exception) {
        val states = _itemActionStates.value.toMutableMap()
        states.remove(request.url)
        _itemActionStates.value = states

        if (e is CancellationException || e.message == "Download cancelled") {
            _downloadState.value = DownloadState.Idle
        } else {
            PulseLogger.log("Queued download failed: ${e.localizedMessage}", isError = true)
            CrashTracker.recordException(
                throwable = e,
                breadcrumb = "Queued download failed",
                customKeys = mapOf(
                    "download_url" to request.url,
                    "override_title" to (request.overrideTitle ?: "none")
                )
            )
            val isOffline = !isConnectedToInternet() ||
                           e is UnknownHostException ||
                           (e.localizedMessage?.contains("Unable to resolve host", ignoreCase = true) == true) ||
                           (e.localizedMessage?.contains("No address associated", ignoreCase = true) == true) ||
                           (e.localizedMessage?.contains("Network is unreachable", ignoreCase = true) == true)

            val msg = if (isOffline) "App is offline. Please check your Internet connection." else "Download failed: ${e.localizedMessage ?: "Unknown error"}"
            _downloadState.value = DownloadState.Error(msg)
            viewModelScope.launch {
                delay(2500)
                if (downloadQueue.isEmpty() && _downloadState.value is DownloadState.Error) {
                    _downloadState.value = DownloadState.Idle
                }
            }
        }
        request.deferred.completeExceptionally(e)
    }

    fun startBatchDownload(asPlaylist: Boolean) {
        viewModelScope.launch {
            val items = _pendingDownloadItems.value.filter { !it.isPlaylist }
            if (items.isEmpty()) return@launch

            val playlistItem = _pendingDownloadItems.value.find { it.isPlaylist }
            val playlistId = if (asPlaylist) {
                val name = playlistItem?.title ?: "Downloaded Playlist"
                repository.createPlaylist(name)
            } else {
                null
            }

            _pendingDownloadItems.value = emptyList()

            items.forEach { item ->
                val request = DownloadRequest(
                    url = item.youtubeUrl,
                    isLibrary = true,
                    overrideTitle = item.title,
                    overrideArtist = item.artist,
                    overrideImageUrl = item.thumbnailUrl,
                    playlistId = playlistId
                )
                enqueueDownloadRequest(request)
            }
        }
    }

    fun clearPendingDownloadItems() {
        _pendingDownloadItems.value = emptyList()
    }

    fun downloadFromYoutube(
        url: String,
        overrideTitle: String? = null,
        overrideArtist: String? = null,
        overrideImageUrl: String? = null
    ) {
        viewModelScope.launch {
            val request = DownloadRequest(
                url = url,
                isLibrary = true,
                overrideTitle = overrideTitle,
                overrideArtist = overrideArtist,
                overrideImageUrl = overrideImageUrl
            )
            try {
                enqueueDownloadRequest(request).await()
            } catch (_: Exception) {
            }
        }
    }

    fun cancelDownload() {
        downloadQueue.clear()
        synchronized(activeDownloadUrls) {
            activeDownloadUrls.clear()
        }
        repository.cancelDownload()
        _downloadState.value = DownloadState.Idle
    }

    fun clearOnlineSearchResults() {
        _onlineSearchResults.value = emptyList()
        _isOnlineSearching.value = false
    }

    fun searchOnline(query: String, isLibrary: Boolean) {
        viewModelScope.launch {
            if (!isConnectedToInternet()) {
                _playbackError.value = "App is offline. Please check your Internet connection."
                return@launch
            }
            _isOnlineSearching.value = true
            _onlineSearchResults.value = emptyList()
            try {
                val maxDuration = if (isLibrary) 600 else null
                val results = YoutubeStreamHandler.searchYouTube(query, maxResults = 5, maxDurationSeconds = maxDuration)
                _onlineSearchResults.value = results
            } catch (e: Exception) {
                e.printStackTrace()
                _onlineSearchResults.value = emptyList()
            } finally {
                _isOnlineSearching.value = false
            }
        }
    }

    suspend fun ingestOnlineItem(item: StreamingItem, isLibrary: Boolean) {
        if (isLibrary) {
            val request = DownloadRequest(
                url = item.youtubeUrl,
                isLibrary = true,
                overrideTitle = item.title,
                overrideArtist = item.artist,
                overrideImageUrl = item.thumbnailUrl
            )
            enqueueDownloadRequest(request).await()
        } else {
            val existing = repository.getTopLevelSingleSong(item.youtubeUrl, item.title, item.artist)
            if (existing == null) {
                repository.insertStreamingItems(listOf(item))
            }
            val currentStates = _itemActionStates.value.toMutableMap()
            currentStates[item.youtubeUrl] = ItemActionState.Success
            _itemActionStates.value = currentStates
            viewModelScope.launch {
                delay(3000)
                if (_itemActionStates.value[item.youtubeUrl] == ItemActionState.Success) {
                    val updated = _itemActionStates.value.toMutableMap()
                    updated.remove(item.youtubeUrl)
                    _itemActionStates.value = updated
                }
            }
        }
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    fun togglePlayPause() {
        val controller = getOrInitMediaController()
        Log.d("SongViewModel", "togglePlayPause triggered. isPlaying: ${controller?.isPlaying}, state: ${controller?.playbackState}")
        _playbackError.value = null
        controller?.let {
            when {
                it.isPlaying -> it.pause()
                it.playbackState == Player.STATE_IDLE -> {
                    it.prepare()
                    it.play()
                }
                it.playbackState == Player.STATE_ENDED -> {
                    it.seekTo(0)
                    it.play()
                }
                else -> it.play()
            }
        }
    }

    fun updateTask(task: String?) {
        PulseLogger.updateTask(task)
    }

    fun clearPlaybackError() {
        _playbackError.value = null
        PulseLogger.clear()
    }

    override fun onCleared() {
        isConnecting = false
        val controller = mediaController
        mediaController = null
        if (controller != null) {
            try {
                controller.stop()
                controller.release()
            } catch (e: Exception) {
                Log.w("SongViewModel", "Handled exception during MediaController release (Service unbind/process teardown)", e)
            }
        }
        super.onCleared()
    }
}

data class DownloadRequest(
    val url: String,
    val isLibrary: Boolean,
    val overrideTitle: String? = null,
    val overrideArtist: String? = null,
    val overrideImageUrl: String? = null,
    val playlistId: Int? = null,
    val deferred: CompletableDeferred<Unit> = CompletableDeferred()
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Checking : DownloadState()
    data class Downloading(
        val progress: Float,
        val current: Int = 1,
        val total: Int = 1
    ) : DownloadState()
    data object Success : DownloadState()
    data class Error(val message: String) : DownloadState()
}

sealed class DuplicatePlaylistState {
    data object Idle : DuplicatePlaylistState()
    data class AlreadyExists(val title: String) : DuplicatePlaylistState()
    data class UpdateAvailable(
        val existingPlaylist: StreamingItem,
        val newHeader: StreamingItem,
        val newTracks: List<StreamingItem>,
        val existingCount: Int,
        val newCount: Int
    ) : DuplicatePlaylistState()
}
