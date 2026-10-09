package com.example.song.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.song.data.dao.SongDao
import com.example.song.data.dao.PlaylistDao
import com.example.song.data.dao.StreamingDao
import com.example.song.data.dao.SpotifyCrawlDao
import com.example.song.data.model.Song
import com.example.song.data.model.Playlist
import com.example.song.data.model.PlaylistSongCrossRef
import com.example.song.data.model.StreamingItem
import com.example.song.data.model.PlaylistEntity
import com.example.song.data.model.PlaylistTrackEntity

@Database(
    entities = [
        Song::class,
        Playlist::class,
        PlaylistSongCrossRef::class,
        StreamingItem::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class
    ],
    version = 12,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun streamingDao(): StreamingDao
    abstract fun spotifyCrawlDao(): SpotifyCrawlDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `spotify_playlists` (
                        `playlistId` TEXT NOT NULL,
                        `title` TEXT,
                        `expectedCount` INTEGER,
                        `status` TEXT NOT NULL,
                        `crawlGeneration` INTEGER NOT NULL,
                        `scriptVersion` TEXT,
                        `selectorsVersion` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`playlistId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                        `playlistId` TEXT NOT NULL,
                        `rowIndex` INTEGER NOT NULL,
                        `rowType` TEXT NOT NULL,
                        `spotifyId` TEXT,
                        `title` TEXT NOT NULL,
                        `artists` TEXT NOT NULL,
                        `album` TEXT,
                        `thumbnailUrl` TEXT,
                        `durationMs` INTEGER,
                        `explicit` INTEGER NOT NULL,
                        `resolveState` TEXT NOT NULL,
                        `ytVideoId` TEXT,
                        `resolveAttempts` INTEGER NOT NULL,
                        `capturedGeneration` INTEGER NOT NULL,
                        PRIMARY KEY(`playlistId`, `rowIndex`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_spotifyId` 
                    ON `playlist_tracks` (`playlistId`, `spotifyId`)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlist_tracks` ADD COLUMN `thumbnailUrl` TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "song_database"
                )
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
