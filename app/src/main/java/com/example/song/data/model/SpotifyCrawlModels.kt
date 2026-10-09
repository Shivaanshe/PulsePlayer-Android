package com.example.song.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CrawlStatus {
    IDLE,
    PREFLIGHT,
    INITIALIZING,
    CRAWLING,
    PAUSED_PARTIAL,
    INCOMPLETE_HALTED,
    COMPLETED,
    COMPLETED_WITH_GAPS,
    FAILED_RECOVERABLE,
    FAILED_TERMINAL
}

enum class RowType {
    TRACK,
    EPISODE,
    LOCAL,
    UNAVAILABLE
}

enum class ResolveState {
    PENDING,
    RESOLVING,
    RESOLVED,
    NOT_FOUND,
    FAILED
}

@Entity(tableName = "spotify_playlists")
data class PlaylistEntity(
    @PrimaryKey val playlistId: String,
    val title: String?,
    val expectedCount: Int?,
    val status: CrawlStatus,
    val crawlGeneration: Int = 1,
    val scriptVersion: String? = "2.1.0",
    val selectorsVersion: String? = "2025.1",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "rowIndex"],
    indices = [Index("playlistId", "spotifyId")]
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val rowIndex: Int,
    val rowType: RowType,
    val spotifyId: String?,
    val title: String,
    val artists: String,
    val album: String?,
    val thumbnailUrl: String? = null,
    val durationMs: Long?,
    val explicit: Boolean = false,
    val resolveState: ResolveState = ResolveState.PENDING,
    val ytVideoId: String? = null,
    val resolveAttempts: Int = 0,
    val capturedGeneration: Int
)
