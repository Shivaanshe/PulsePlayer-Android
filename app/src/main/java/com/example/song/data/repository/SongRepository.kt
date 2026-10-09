package com.example.song.data.repository

import com.example.song.data.dao.StreamingDao
import com.example.song.data.model.StreamingItem
import com.example.song.data.api.ITunesService
import com.example.song.data.api.SpotifyService
import com.example.song.data.dao.PlaylistDao
import com.example.song.data.dao.SongDao
import com.example.song.data.database.AppDatabase
import com.example.song.data.model.Playlist
import com.example.song.data.model.PlaylistSongCrossRef
import com.example.song.data.model.Song
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.net.URL
import java.util.UUID
import android.util.Log
import com.example.song.SongApplication
import com.example.song.util.YoutubeStreamHandler
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SongRepository(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val streamingDao: StreamingDao,
    private val baseDir: File
) {
    private val iTunesService: ITunesService by lazy {
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(SongApplication.getInstance().okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ITunesService::class.java)
    }

    private val spotifyService: SpotifyService by lazy {
        Retrofit.Builder()
            .baseUrl("https://open.spotify.com/")
            .client(SongApplication.getInstance().okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SpotifyService::class.java)
    }

    val spotifyCrawlDao: com.example.song.data.dao.SpotifyCrawlDao by lazy {
        AppDatabase.getDatabase(SongApplication.getInstance()).spotifyCrawlDao()
    }

    val spotifyCrawlPipeline: com.example.song.data.crawler.SpotifyCrawlPipeline by lazy {
        com.example.song.data.crawler.SpotifyCrawlPipeline(
            spotifyCrawlDao,
            SongApplication.getInstance().okHttpClient
        )
    }

    suspend fun convertSpotifyCrawlToStreamingItems(playlistId: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        val tracks = spotifyCrawlDao.getTracksForPlaylistSync(playlistId)
        val playlistMeta = spotifyCrawlDao.getPlaylist(playlistId)
        val playlistTitle = playlistMeta?.title ?: "Spotify Playlist"
        val playlistUrl = "https://open.spotify.com/playlist/$playlistId"
        val firstCover = tracks.firstOrNull { !it.thumbnailUrl.isNullOrEmpty() }?.thumbnailUrl ?: ""

        val items = mutableListOf<StreamingItem>()
        items.add(
            StreamingItem(
                youtubeUrl = playlistUrl,
                title = playlistTitle,
                artist = "Spotify",
                thumbnailUrl = firstCover,
                isPlaylist = true
            )
        )

        tracks.forEach { track ->
            val query = "${track.title} ${track.artists}".trim()
            val ytSearchUrl = if (!track.ytVideoId.isNullOrEmpty()) {
                "https://www.youtube.com/watch?v=${track.ytVideoId}"
            } else {
                "ytsearch1:$query"
            }

            items.add(
                StreamingItem(
                    youtubeUrl = ytSearchUrl,
                    title = track.title,
                    artist = track.artists,
                    thumbnailUrl = track.thumbnailUrl ?: firstCover,
                    isPlaylist = false,
                    parentPlaylistUrl = playlistUrl,
                    duration = track.durationMs ?: 0L
                )
            )
        }

        items
    }

    private var downloadJob: Job? = null
    private var currentRequestId: String? = null

    val allSongs: Flow<List<Song>> = songDao.getAllSongs()
    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylists()
    
    val favoriteSongs: Flow<List<Song>> = combine(
        songDao.getFavoriteSongs(),
        streamingDao.getFavoriteItems()
    ) { local, streaming ->
        val mappedStreaming = streaming.map { item ->
            Song(
                id = 1_000_000 + item.id, // Offset ID for streaming items
                title = item.title,
                artist = item.artist ?: "YouTube",
                audioUri = item.youtubeUrl,
                imageUrl = item.thumbnailUrl,
                isFavorite = item.isFavorite,
                duration = item.duration,
                position = item.position
            )
        }
        (local + mappedStreaming).sortedWith(compareBy({ it.position }, { -it.id }))
    }

    val topLevelStreamingItems: Flow<List<StreamingItem>> = streamingDao.getAllTopLevelItems()
    val allStreamingSongs: Flow<List<StreamingItem>> = streamingDao.getAllSingleStreamingSongs()
    val allSongIdsInPlaylists: Flow<List<Int>> = playlistDao.getAllSongIdsInPlaylists()

    suspend fun scanAndRestoreSongs() = withContext(Dispatchers.IO) {
        cleanUpDuplicateSingleSongs()
        val folders = listOf(File(baseDir, "Music"), File(baseDir, "DownloadedMusic"))
        val existingUris = songDao.getAllSongsSync().map { it.audioUri }.toSet()

        folders.forEach { folder ->
            if (folder.exists() && folder.isDirectory) {
                folder.listFiles()?.forEach { file ->
                    if (file.isFile && file.extension in listOf("mp3", "m4a", "wav", "ogg", "opus")) {
                        if (!existingUris.contains(file.absolutePath)) {
                            val song = Song(
                                title = file.nameWithoutExtension.replace("_", " "),
                                artist = "Local",
                                audioUri = file.absolutePath
                            )
                            songDao.insertSong(song)
                        }
                    }
                }
            }
        }
    }

    suspend fun getTopLevelSingleSong(url: String, title: String, artist: String?): StreamingItem? = withContext(Dispatchers.IO) {
        streamingDao.getTopLevelSingleSong(url, title, artist)
    }

    suspend fun cleanUpDuplicateSingleSongs() = withContext(Dispatchers.IO) {
        val prefs = SongApplication.getInstance().getSharedPreferences("app_migration_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("has_cleaned_duplicate_streaming_songs_v1", false)) {
            return@withContext
        }

        val allSingleSongs = streamingDao.getAllTopLevelSingleSongsSync()
        val grouped = allSingleSongs.groupBy { item ->
            val keyTitle = item.title.lowercase().trim()
            val keyArtist = item.artist?.lowercase()?.trim() ?: ""
            if (item.youtubeUrl.startsWith("http")) item.youtubeUrl else "$keyTitle|$keyArtist"
        }

        grouped.forEach { (_, duplicates) ->
            if (duplicates.size > 1) {
                val toDelete = duplicates.drop(1)
                toDelete.forEach { streamingDao.deleteItem(it) }
            }
        }

        prefs.edit { putBoolean("has_cleaned_duplicate_streaming_songs_v1", true) }
    }

    fun getItemsForStreamingPlaylist(playlistUrl: String): Flow<List<StreamingItem>> {
        return streamingDao.getItemsForPlaylist(playlistUrl)
    }

    suspend fun getPlaylistByUrl(url: String): StreamingItem? = withContext(Dispatchers.IO) {
        streamingDao.getPlaylistByUrl(url)
    }

    suspend fun getItemsForPlaylistSync(url: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        streamingDao.getItemsForPlaylistSync(url)
    }

    suspend fun smartMergePlaylist(
        existingPlaylist: StreamingItem,
        newPlaylistHeader: StreamingItem,
        newTracks: List<StreamingItem>
    ) = withContext(Dispatchers.IO) {
        val existingTracks = streamingDao.getItemsForPlaylistSync(existingPlaylist.youtubeUrl)
        val existingTracksByUrl = existingTracks.associateBy { it.youtubeUrl }

        val updatedOrNewTracks = mutableListOf<StreamingItem>()
        val processedExistingUrls = mutableSetOf<String>()

        newTracks.forEachIndexed { index, newTrack ->
            val existingTrack = existingTracksByUrl[newTrack.youtubeUrl]
            if (existingTrack != null) {
                processedExistingUrls.add(existingTrack.youtubeUrl)
                updatedOrNewTracks.add(
                    existingTrack.copy(
                        title = newTrack.title,
                        artist = newTrack.artist ?: existingTrack.artist,
                        thumbnailUrl = newTrack.thumbnailUrl ?: existingTrack.thumbnailUrl,
                        duration = if (newTrack.duration > 0) newTrack.duration else existingTrack.duration,
                        position = index,
                        parentPlaylistUrl = existingPlaylist.youtubeUrl
                    )
                )
            } else {
                updatedOrNewTracks.add(
                    newTrack.copy(
                        id = 0,
                        parentPlaylistUrl = existingPlaylist.youtubeUrl,
                        position = index
                    )
                )
            }
        }

        val tracksToDelete = existingTracks.filter { it.youtubeUrl !in processedExistingUrls }
        tracksToDelete.forEach { streamingDao.deleteItem(it) }

        val updatedHeader = existingPlaylist.copy(
            title = newPlaylistHeader.title,
            artist = newPlaylistHeader.artist ?: existingPlaylist.artist,
            thumbnailUrl = newPlaylistHeader.thumbnailUrl ?: existingPlaylist.thumbnailUrl
        )
        streamingDao.updateItems(listOf(updatedHeader))
        streamingDao.insertItems(updatedOrNewTracks)
    }

    suspend fun insertStreamingItems(items: List<StreamingItem>) {
        streamingDao.insertItems(items)
    }

    suspend fun updateStreamingItems(items: List<StreamingItem>) {
        streamingDao.updateItems(items)
    }

    suspend fun deleteStreamingItem(item: StreamingItem) {
        if (item.isPlaylist) {
            streamingDao.deletePlaylistItems(item.youtubeUrl)
        }
        streamingDao.deleteItem(item)
    }

    suspend fun deleteStreamingItemById(itemId: Int) {
        val item = streamingDao.getItemById(itemId)
        item?.let {
            if (it.isPlaylist) {
                streamingDao.deletePlaylistItems(it.youtubeUrl)
            }
            streamingDao.deleteItemById(itemId)
        }
    }

    suspend fun updateStreamingItemTitle(itemId: Int, newTitle: String) {
        streamingDao.updateTitle(itemId, newTitle)
    }

    suspend fun updateStreamingItemFavorite(itemId: Int, isFavorite: Boolean) {
        streamingDao.updateFavorite(itemId, isFavorite)
    }

    suspend fun getStreamingItemById(itemId: Int): StreamingItem? {
        return streamingDao.getItemById(itemId)
    }

    suspend fun updateStreamingItemParentPlaylist(itemId: Int, playlistUrl: String?) {
        streamingDao.updateParentPlaylist(itemId, playlistUrl)
    }

    suspend fun insertSong(song: Song): Int {
        val cleanTitle = song.title
            .substringAfterLast("/")
            .substringBeforeLast(".")
            .replace("_", " ")
            .replace("-", " ")
            .trim()

        val coverUrl = try {
            val response = iTunesService.searchSong(cleanTitle)
            response.results.firstOrNull()?.artworkUrl100?.replace("100x100bb", "500x500bb")
        } catch (e: Exception) {
            null
        }
        
        return songDao.insertSong(song.copy(
            title = cleanTitle,
            imageUrl = coverUrl ?: song.imageUrl
        )).toInt()
    }

    suspend fun updateSong(song: Song) {
        songDao.updateSong(song)
    }

    suspend fun updateSongs(songs: List<Song>) {
        songs.forEach { songDao.updateSong(it) }
    }

    suspend fun deleteSong(songId: Int) {
        val song = songDao.getSongByIdSync(songId)
        song?.let {
            val file = File(it.audioUri)
            if (file.exists()) {
                file.delete()
            }
        }
        songDao.deleteSong(songId)
    }

    suspend fun createPlaylist(name: String): Int {
        return playlistDao.insertPlaylist(Playlist(name = name)).toInt()
    }

    suspend fun updatePlaylistPositions(playlists: List<Playlist>) {
        playlistDao.updatePlaylists(playlists)
    }

    suspend fun deletePlaylist(playlistId: Int) {
        playlistDao.deletePlaylistWithCrossRefs(playlistId)
    }

    suspend fun addSongToPlaylist(songId: Int, playlistId: Int) {
        playlistDao.insertSongToPlaylist(PlaylistSongCrossRef(playlistId, songId))
    }

    suspend fun removeSongFromPlaylist(songId: Int, playlistId: Int) {
        playlistDao.removeSongFromPlaylist(PlaylistSongCrossRef(playlistId, songId))
    }

    suspend fun updatePlaylistSongOrder(playlistId: Int, songs: List<Song>) {
        val crossRefs = songs.mapIndexed { index, song ->
            PlaylistSongCrossRef(playlistId, song.id, position = index)
        }
        playlistDao.insertPlaylistSongCrossRefs(crossRefs)
    }

    fun getSongsInPlaylist(playlistId: Int): Flow<List<Song>> {
        return playlistDao.getSongsInPlaylist(playlistId)
    }

    suspend fun getFirstSongSync(): Song? = withContext(Dispatchers.IO) {
        songDao.getFirstSongSync()
    }

    suspend fun getSongByIdSync(id: Int): Song? = withContext(Dispatchers.IO) {
        songDao.getSongByIdSync(id)
    }

    suspend fun getAllSongsSync(): List<Song> = withContext(Dispatchers.IO) {
        songDao.getAllSongsSync()
    }

    suspend fun getSongsByIdsSync(ids: List<Int>): List<Song> = withContext(Dispatchers.IO) {
        songDao.getSongsByIdsSync(ids)
    }

    suspend fun getStreamingItemsByIdsSync(ids: List<Int>): List<StreamingItem> = withContext(Dispatchers.IO) {
        streamingDao.getItemsByIdsSync(ids)
    }

    suspend fun fetchArtwork(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = "$title $artist"
            val response = iTunesService.searchSong(query)
            response.results.firstOrNull()?.artworkUrl100?.replace("100x100bb", "500x500bb")
        } catch (e: Exception) {
            null
        }
    }

    suspend fun resolveSpotifyMetadata(url: String) = withContext(Dispatchers.IO) {
        spotifyService.getMetadata(url)
    }

    fun cancelDownload() {
        currentRequestId?.let { 
            try {
                YoutubeStreamHandler.cancelProcess(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        downloadJob?.cancel()
        downloadJob = null
    }

    suspend fun downloadYouTubeAudio(
        url: String, 
        playlistId: Int? = null,
        overrideTitle: String? = null,
        overrideArtist: String? = null,
        overrideImageUrl: String? = null,
        progressCallback: (Float, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        
        val musicDir = File(baseDir, "DownloadedMusic")
        if (!musicDir.exists()) musicDir.mkdirs()

        try {
            Log.d("SongRepository", "Starting download for URL: $url")
            
            // If it's a search bridge query, resolve it to a real URL first
            val actualUrl = if (url.startsWith("ytsearch")) {
                val results = YoutubeStreamHandler.getMetadata(url)
                results.find { !it.isPlaylist }?.youtubeUrl ?: url
            } else {
                url
            }

            // 1. Create a separate request for getting info
            val infoRequest = YoutubeDLRequest(actualUrl).apply {
                addOption("--no-check-certificate")
                addOption("--yes-playlist")
                addOption("--playlist-items", "1")
                addOption("--no-cache-dir")
            }
            
            val videoInfo: VideoInfo = try {
                YoutubeDL.getInstance().getInfo(infoRequest)
            } catch (e: Exception) {
                Log.e("SongRepository", "Failed to get video info", e)
                throw Exception("Failed to get video info: ${e.message}")
            }

            if (videoInfo.duration > 1200) { // 20 mins limit
                throw Exception("Video is too long (> 20 mins)")
            }

            // 2. Create a fresh request for the actual download
            val downloadRequest = YoutubeDLRequest(url).apply {
                addOption("--extract-audio")
                addOption("--audio-format", "mp3")
                // Use requestId as filename to avoid issues with special characters in titles
                addOption("--output", "${musicDir.absolutePath}/$requestId.%(ext)s")
                addOption("--no-check-certificate")
                addOption("--yes-playlist")
                addOption("--playlist-items", "1")
                addOption("--no-cache-dir") // Disable cache to prevent old video data reuse
            }

            // Perform download
            try {
                val response = YoutubeDL.getInstance().execute(downloadRequest, requestId) { progress, eta, line ->
                    Log.d("SongRepository", "Progress: $progress, ETA: $eta, Line: $line")
                    progressCallback(progress, eta)
                }
                Log.d("SongRepository", "Download finished. Exit code: ${response.exitCode}")
            } catch (e: Exception) {
                Log.e("SongRepository", "Execution failed", e)
                throw Exception("Download failed: ${e.message}")
            }

            // 3. Fallback check for different extensions
            val possibleExtensions = listOf("mp3", "m4a", "webm", "opus", "wav")
            var downloadedFile: File? = null
            
            for (ext in possibleExtensions) {
                val file = File(musicDir, "$requestId.$ext")
                if (file.exists()) {
                    downloadedFile = file
                    break
                }
            }
            
            if (downloadedFile != null && downloadedFile.exists()) {
                Log.d("SongRepository", "Success! File found: ${downloadedFile.absolutePath}")
                
                val extension = downloadedFile.extension
                val safeTitle = (videoInfo.title ?: "Downloaded Song")
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .take(100) 
                var finalFile = File(musicDir, "$safeTitle.$extension")
                
                var counter = 1
                while (finalFile.exists()) {
                    finalFile = File(musicDir, "$safeTitle ($counter).$extension")
                    counter++
                }
                
                val songId = if (downloadedFile.renameTo(finalFile)) {
                    val song = Song(
                        title = overrideTitle ?: videoInfo.title ?: finalFile.nameWithoutExtension,
                        artist = overrideArtist ?: videoInfo.uploader ?: "YouTube",
                        audioUri = finalFile.absolutePath,
                        imageUrl = overrideImageUrl ?: videoInfo.thumbnail,
                        duration = videoInfo.duration * 1000L
                    )
                    insertSong(song)
                } else {
                    val song = Song(
                        title = overrideTitle ?: videoInfo.title ?: downloadedFile.nameWithoutExtension,
                        artist = overrideArtist ?: videoInfo.uploader ?: "YouTube",
                        audioUri = downloadedFile.absolutePath,
                        imageUrl = overrideImageUrl ?: videoInfo.thumbnail,
                        duration = videoInfo.duration * 1000L
                    )
                    insertSong(song)
                }

                // Link to playlist if requested
                if (playlistId != null) {
                    addSongToPlaylist(songId, playlistId)
                }
            } else {
                val existingFiles = musicDir.list()?.joinToString(", ") ?: "none"
                Log.e("SongRepository", "File $requestId.mp3 not found. Existing files: $existingFiles")
                throw Exception("Downloaded file not found in Music folder")
            }
        } catch (e: Exception) {
            Log.e("SongRepository", "General error during download", e)
            val tempFiles = musicDir.listFiles { _, name -> name.startsWith(requestId) }
            tempFiles?.forEach { file ->
                try {
                    if (file.exists()) file.delete()
                } catch (_: Exception) {}
            }
            throw e
        } finally {
            currentRequestId = null
        }
    }
}
