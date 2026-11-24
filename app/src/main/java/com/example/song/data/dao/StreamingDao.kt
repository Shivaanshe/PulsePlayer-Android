package com.example.song.data.dao

import androidx.room.*
import com.example.song.data.model.StreamingItem
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamingDao {
    @Query("SELECT * FROM streaming_items WHERE parentPlaylistUrl IS NULL ORDER BY position ASC, id DESC")
    fun getAllTopLevelItems(): Flow<List<StreamingItem>>

    @Query("SELECT * FROM streaming_items WHERE parentPlaylistUrl = :playlistUrl ORDER BY position ASC, id ASC")
    fun getItemsForPlaylist(playlistUrl: String): Flow<List<StreamingItem>>

    @Query("SELECT * FROM streaming_items WHERE isFavorite = 1 ORDER BY position ASC, id DESC")
    fun getFavoriteItems(): Flow<List<StreamingItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: StreamingItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<StreamingItem>)

    @Update
    suspend fun updateItems(items: List<StreamingItem>)

    @Delete
    suspend fun deleteItem(item: StreamingItem)
    
    @Query("DELETE FROM streaming_items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: Int)

    @Query("SELECT * FROM streaming_items WHERE id = :itemId")
    suspend fun getItemById(itemId: Int): StreamingItem?

    @Query("SELECT * FROM streaming_items WHERE id IN (:itemIds)")
    suspend fun getItemsByIdsSync(itemIds: List<Int>): List<StreamingItem>

    @Query("SELECT * FROM streaming_items WHERE isPlaylist = 0 ORDER BY position ASC, id DESC")
    fun getAllSingleStreamingSongs(): Flow<List<StreamingItem>>
    
    @Query("UPDATE streaming_items SET title = :newTitle WHERE id = :itemId")
    suspend fun updateTitle(itemId: Int, newTitle: String)

    @Query("UPDATE streaming_items SET parentPlaylistUrl = :playlistUrl WHERE id = :itemId")
    suspend fun updateParentPlaylist(itemId: Int, playlistUrl: String?)

    @Query("UPDATE streaming_items SET isFavorite = :isFavorite WHERE id = :itemId")
    suspend fun updateFavorite(itemId: Int, isFavorite: Boolean)
    
    @Query("DELETE FROM streaming_items WHERE parentPlaylistUrl = :playlistUrl")
    suspend fun deletePlaylistItems(playlistUrl: String)

    @Query("SELECT * FROM streaming_items WHERE isPlaylist = 1 AND youtubeUrl = :url LIMIT 1")
    suspend fun getPlaylistByUrl(url: String): StreamingItem?

    @Query("SELECT * FROM streaming_items WHERE parentPlaylistUrl = :playlistUrl ORDER BY position ASC, id ASC")
    suspend fun getItemsForPlaylistSync(playlistUrl: String): List<StreamingItem>

    @Query("""
        SELECT * FROM streaming_items 
        WHERE parentPlaylistUrl IS NULL 
          AND isPlaylist = 0 
          AND (
              youtubeUrl = :url 
              OR (title = :title AND (:artist IS NULL OR artist = :artist))
          )
        LIMIT 1
    """)
    suspend fun getTopLevelSingleSong(url: String, title: String, artist: String?): StreamingItem?

    @Query("SELECT * FROM streaming_items WHERE parentPlaylistUrl IS NULL AND isPlaylist = 0")
    suspend fun getAllTopLevelSingleSongsSync(): List<StreamingItem>

    @Query("SELECT * FROM streaming_items WHERE youtubeUrl IN (:urls) AND parentPlaylistUrl IS NULL ORDER BY id DESC")
    suspend fun getItemsByUrlsSync(urls: List<String>): List<StreamingItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncTombstone(tombstone: com.example.song.data.model.SyncTombstone)

    @Query("SELECT * FROM sync_tombstones WHERE playlistUrl = :playlistUrl")
    suspend fun getTombstonesForPlaylist(playlistUrl: String): List<com.example.song.data.model.SyncTombstone>

    @Query("DELETE FROM sync_tombstones WHERE playlistUrl = :playlistUrl")
    suspend fun deleteTombstonesForPlaylist(playlistUrl: String)

    @Query("DELETE FROM sync_tombstones WHERE playlistUrl = :playlistUrl AND trackUrl = :trackUrl")
    suspend fun removeTombstone(playlistUrl: String, trackUrl: String)

    @Query("UPDATE streaming_items SET lastSyncedAt = :timestamp WHERE id = :itemId")
    suspend fun updateLastSyncedAt(itemId: Int, timestamp: Long)

    @Query("UPDATE streaming_items SET thumbnailUrl = :thumbnailUrl WHERE id = :itemId")
    suspend fun updateThumbnail(itemId: Int, thumbnailUrl: String)

    @Transaction
    suspend fun insertTracksAndUpdateSyncTime(tracks: List<StreamingItem>, playlistId: Int, syncTime: Long) {
        if (tracks.isNotEmpty()) {
            insertItems(tracks)
        }
        updateLastSyncedAt(playlistId, syncTime)
    }
}
