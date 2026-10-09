package com.example.song.data.crawler

import com.google.gson.annotations.SerializedName

sealed class SpotifyBridgeMessage {

    data class HelloMessage(
        @SerializedName("v") val v: Int = 1,
        @SerializedName("scriptVersion") val scriptVersion: String?,
        @SerializedName("selectorsVersion") val selectorsVersion: String?,
        @SerializedName("base") val base: String?
    ) : SpotifyBridgeMessage()

    data class MetaMessage(
        @SerializedName("v") val v: Int = 1,
        @SerializedName("title") val title: String?,
        @SerializedName("expectedCount") val expectedCount: Int?,
        @SerializedName("countInferred") val countInferred: Boolean? = false
    ) : SpotifyBridgeMessage()

    data class BatchMessage(
        @SerializedName("v") val v: Int = 1,
        @SerializedName("tracks") val tracks: List<RawTrackPayload> = emptyList()
    ) : SpotifyBridgeMessage()

    data class ProgressMessage(
        @SerializedName("v") val v: Int = 1,
        @SerializedName("captured") val captured: Int?,
        @SerializedName("expected") val expected: Int?,
        @SerializedName("distinctIndexes") val distinctIndexes: Int?,
        @SerializedName("maxIndex") val maxIndex: Int?,
        @SerializedName("phase") val phase: String?
    ) : SpotifyBridgeMessage()

    data class ErrorMessage(
        @SerializedName("v") val v: Int = 1,
        @SerializedName("code") val code: String?
    ) : SpotifyBridgeMessage()

    data class FinishedMessage(
        @SerializedName("v") val v: Int = 1,
        @SerializedName("reason") val reason: String?,
        @SerializedName("captured") val captured: Int?,
        @SerializedName("expected") val expected: Int?
    ) : SpotifyBridgeMessage()
}

data class RawTrackPayload(
    @SerializedName("rowIndex") val rowIndex: Int?,
    @SerializedName("rowRaw") val rowRaw: Int?,
    @SerializedName("rowType") val rowType: String?,
    @SerializedName("spotifyId") val spotifyId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("artists") val artists: String?,
    @SerializedName("album") val album: String?,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("durationMs") val durationMs: Long?,
    @SerializedName("explicit") val explicit: Boolean? = false
)
