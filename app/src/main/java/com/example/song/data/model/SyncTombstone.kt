package com.example.song.data.model

import androidx.room.Entity

@Entity(primaryKeys = ["playlistUrl", "trackUrl"], tableName = "sync_tombstones")
data class SyncTombstone(
    val playlistUrl: String,
    val trackUrl: String,
    val coreTitle: String,
    val videoId: String
)
