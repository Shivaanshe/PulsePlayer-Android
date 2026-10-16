package com.example.song.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.song.SongApplication

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "com.example.song.ACTION_STOP_DOWNLOAD" -> SongApplication.stopDownloadHandler?.invoke()
            "com.example.song.ACTION_PAUSE_DOWNLOAD" -> SongApplication.pauseDownloadHandler?.invoke()
            "com.example.song.ACTION_RESUME_DOWNLOAD" -> SongApplication.resumeDownloadHandler?.invoke()
        }
    }
}
