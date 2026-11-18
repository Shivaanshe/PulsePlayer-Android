package com.example.song.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Entity(tableName = "streaming_items")
@Parcelize
data class StreamingItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val youtubeUrl: String,
    val title: String,
    val artist: String? = null,
    val thumbnailUrl: String?,
    val isPlaylist: Boolean = false,
    val parentPlaylistUrl: String? = null,
    val duration: Long = 0L,
    val isFavorite: Boolean = false,
    val position: Int = 0,
    val lastSyncedAt: Long? = null
) : Parcelable
