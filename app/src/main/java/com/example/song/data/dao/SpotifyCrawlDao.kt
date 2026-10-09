package com.example.song.data.dao

import androidx.room.*
import com.example.song.data.model.CrawlStatus
import com.example.song.data.model.PlaylistEntity
import com.example.song.data.model.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotifyCrawlDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM spotify_playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query("SELECT * FROM spotify_playlists WHERE playlistId = :playlistId")
    fun observePlaylist(playlistId: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM spotify_playlists ORDER BY updatedAt DESC")
    fun observeAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY rowIndex ASC")
    fun getTracksForPlaylist(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY rowIndex ASC")
    suspend fun getTracksForPlaylistSync(playlistId: String): List<PlaylistTrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getCapturedCount(playlistId: String): Int

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearTracksForPlaylist(playlistId: String)

    @Query("DELETE FROM spotify_playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Transaction
    suspend fun deletePlaylistWithTracks(playlistId: String) {
        clearTracksForPlaylist(playlistId)
        deletePlaylist(playlistId)
    }

    @Query("UPDATE spotify_playlists SET status = 'PAUSED_PARTIAL' WHERE status IN ('INITIALIZING', 'CRAWLING')")
    suspend fun recoverProcessDeath()

    @Transaction
    suspend fun insertBatchTransactional(
        playlistId: String,
        tracks: List<PlaylistTrackEntity>,
        expectedCount: Int?,
        title: String? = null
    ) {
        if (tracks.isNotEmpty()) {
            insertTracks(tracks)
        }
        val current = getPlaylist(playlistId)
        val now = System.currentTimeMillis()
        if (current != null) {
            updatePlaylist(
                current.copy(
                    title = title ?: current.title,
                    expectedCount = expectedCount ?: current.expectedCount,
                    updatedAt = now
                )
            )
        } else {
            insertPlaylist(
                PlaylistEntity(
                    playlistId = playlistId,
                    title = title,
                    expectedCount = expectedCount,
                    status = CrawlStatus.CRAWLING,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    @Transaction
    suspend fun finalizeCrawlTransactional(
        playlistId: String,
        reasonStatus: CrawlStatus,
        expectedCount: Int?
    ) {
        val current = getPlaylist(playlistId) ?: return
        val count = getCapturedCount(playlistId)
        val exp = expectedCount ?: current.expectedCount ?: count

        val finalStatus = if (reasonStatus == CrawlStatus.COMPLETED) {
            if (exp > 0 && count < exp) CrawlStatus.COMPLETED_WITH_GAPS else CrawlStatus.COMPLETED
        } else {
            reasonStatus
        }

        updatePlaylist(
            current.copy(
                expectedCount = exp,
                status = finalStatus,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
