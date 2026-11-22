package com.example.song.data.model

data class StreamInfo(
    val url: String,
    val headers: Map<String, String>,
    val videoId: String? = null,
    val thumbnailUrl: String? = null
)
