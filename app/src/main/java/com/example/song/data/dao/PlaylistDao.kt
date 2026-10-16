package com.example.song.data.dao

import androidx.room.*
import com.example.song.data.model.Playlist
import com.example.song.data.model.PlaylistSongCrossRef
import com.example.song.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylists(playlists: List<Playlist>)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Query("SELECT * FROM playlists ORDER BY position ASC, id DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongCrossRefs(crossRefs: List<PlaylistSongCrossRef>)

    @Delete
    suspend fun removeSongFromPlaylist(crossRef: PlaylistSongCrossRef)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Int)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun deletePlaylistCrossRefs(playlistId: Int)

    @Transaction
    suspend fun deletePlaylistWithCrossRefs(playlistId: Int) {
        deletePlaylistCrossRefs(playlistId)
        deletePlaylistById(playlistId)
    }

    @Transaction
    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId 
        WHERE playlist_song_cross_ref.playlistId = :playlistId
        ORDER BY playlist_song_cross_ref.position ASC
    """)
    fun getSongsInPlaylist(playlistId: Int): Flow<List<Song>>

    @Transaction
    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId 
        WHERE playlist_song_cross_ref.playlistId = :playlistId
        ORDER BY playlist_song_cross_ref.position ASC
    """)
    suspend fun getSongsInPlaylistSync(playlistId: Int): List<Song>

    @Query("SELECT songId FROM playlist_song_cross_ref")
    fun getAllSongIdsInPlaylists(): Flow<List<Int>>

    @Query("SELECT songId FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun getSongIdsForPlaylist(playlistId: Int): List<Int>

    @Query("SELECT songId FROM playlist_song_cross_ref WHERE playlistId != :playlistId")
    suspend fun getAllSongIdsInOtherPlaylists(playlistId: Int): List<Int>
}
