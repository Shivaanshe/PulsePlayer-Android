package com.example.song.util

import android.util.Log
import com.example.song.data.model.StreamingItem
import com.example.song.data.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object SpotifyResolver {
    private const val TAG = "SpotifyResolver"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun unescapeHtml(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    suspend fun resolve(url: String, repository: SongRepository): List<StreamingItem> = withContext(Dispatchers.IO) {
        val isSingleTrack = url.contains("/track/")
        
        // Layer 1: Fetch clean metadata from Spotify API first
        val apiMeta = try {
            PulseLogger.updateTask("Fetching Spotify Meta...")
            repository.resolveSpotifyMetadata(url)
        } catch (e: Exception) {
            Log.e(TAG, "Spotify API failed", e)
            CrashTracker.recordException(e, "Spotify API resolution failed", mapOf("spotify_url" to url))
            null
        }

        // Layer 2: Attempt high-performance Embed Scraper
        try {
            PulseLogger.updateTask("Scraping Spotify Embed...")
            val embedUrl = when {
                url.contains("/track/") -> url.replace("/track/", "/embed/track/")
                url.contains("/album/") -> url.replace("/album/", "/embed/album/")
                url.contains("/playlist/") -> url.replace("/playlist/", "/embed/playlist/")
                else -> url
            }

            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                .build()

            val html = client.newCall(request).execute().use { response ->
                response.body?.string() ?: ""
            }

            // Strategy A: JSON Extraction (__NEXT_DATA__)
            val jsonPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>")
            val jsonMatcher = jsonPattern.matcher(html)
            
            if (jsonMatcher.find()) {
                try {
                    val jsonStr = jsonMatcher.group(1) ?: ""
                    val fullJson = JSONObject(jsonStr)
                    
                    val pageProps = fullJson.optJSONObject("props")?.optJSONObject("pageProps")
                    val entity = pageProps?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
                    
                    if (entity != null) {
                        val collectionTitle = unescapeHtml(apiMeta?.title ?: entity.optString("title", "Spotify Collection"))
                        val collectionThumb = apiMeta?.thumbnailUrl ?: extractSpotifyImage(entity) ?: ""
                        
                        val tracks = mutableListOf<StreamingItem>()
                        val trackList = entity.optJSONArray("trackList")
                        
                        if (trackList != null) {
                            for (i in 0 until trackList.length()) {
                                val trackJson = trackList.getJSONObject(i)
                                val trackTitle = unescapeHtml(trackJson.optString("title"))
                                val trackArtist = unescapeHtml(trackJson.optString("subtitle").ifEmpty { 
                                    trackJson.optString("artist").ifEmpty { apiMeta?.artist ?: "Unknown Artist" } 
                                })
                                
                                if (trackTitle.isNotEmpty()) {
                                    var trackThumb = extractSpotifyImage(trackJson)
                                    if (trackThumb == null || trackThumb.contains("spotifycdn.com/embed")) {
                                        trackThumb = repository.fetchArtwork(trackTitle, trackArtist)
                                    }
                                    
                                    tracks.add(StreamingItem(
                                        youtubeUrl = "ytsearch1:$trackTitle $trackArtist",
                                        title = trackTitle,
                                        artist = trackArtist,
                                        thumbnailUrl = trackThumb ?: collectionThumb,
                                        isPlaylist = false,
                                        parentPlaylistUrl = if (trackList.length() > 1) url else null,
                                        duration = trackJson.optLong("duration", 0L)
                                    ))
                                }
                            }
                        } else if (isSingleTrack) {
                            var trackTitle = unescapeHtml(apiMeta?.title ?: entity.optString("title"))
                            
                            val artistsArray = entity.optJSONArray("artists")
                            val artistFromEntity = if (artistsArray != null && artistsArray.length() > 0) {
                                val names = mutableListOf<String>()
                                for (j in 0 until artistsArray.length()) {
                                    names.add(artistsArray.getJSONObject(j).optString("name"))
                                }
                                names.joinToString(", ")
                            } else {
                                entity.optString("subtitle").ifEmpty { 
                                    entity.optString("artist").ifEmpty { 
                                        entity.optJSONObject("album")?.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                                    }
                                }
                            }

                            var trackArtist = unescapeHtml(apiMeta?.artist ?: artistFromEntity)
                            if (trackArtist.isEmpty()) trackArtist = "Unknown Artist"

                            if (trackTitle.contains(" - song by ")) trackTitle = trackTitle.substringBefore(" - song by ")
                            else if (trackTitle.contains(" - Single")) trackTitle = trackTitle.substringBefore(" - Single")

                            if (trackTitle.isNotEmpty()) {
                                var trackThumb = extractSpotifyImage(entity)
                                if (trackThumb == null || (trackThumb.contains("spotifycdn.com/embed"))) {
                                    trackThumb = repository.fetchArtwork(trackTitle, trackArtist)
                                }
                                
                                tracks.add(StreamingItem(
                                    youtubeUrl = "ytsearch1:$trackTitle $trackArtist",
                                    title = trackTitle,
                                    artist = trackArtist,
                                    thumbnailUrl = trackThumb ?: collectionThumb,
                                    isPlaylist = false,
                                    parentPlaylistUrl = null,
                                    duration = entity.optLong("duration", 0L)
                                ))
                            }
                        }

                        if (tracks.isNotEmpty()) {
                            val result = mutableListOf<StreamingItem>()
                            if (tracks.size > 1 || !isSingleTrack) {
                                result.add(StreamingItem(
                                    youtubeUrl = url,
                                    title = collectionTitle,
                                    artist = apiMeta?.artist ?: "Spotify",
                                    thumbnailUrl = collectionThumb,
                                    isPlaylist = true
                                ))
                            }
                            result.addAll(tracks)
                            PulseLogger.updateTask(null)
                            return@withContext result
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON Extraction failed", e)
                }
            }

            // Strategy B: OpenGraph Fallback (Regex)
            PulseLogger.log("JSON Scrape blocked. Trying OpenGraph fallback...")
            val ogTitlePattern = Pattern.compile("<meta property=\"og:title\" content=\"(.*?)\"")
            val ogDescPattern = Pattern.compile("<meta property=\"og:description\" content=\"(.*?)\"")
            
            val ogTitleMatcher = ogTitlePattern.matcher(html)
            val ogDescMatcher = ogDescPattern.matcher(html)
            
            if (ogTitleMatcher.find()) {
                val rawTitle = unescapeHtml(ogTitleMatcher.group(1) ?: "")
                val rawDesc = if (ogDescMatcher.find()) unescapeHtml(ogDescMatcher.group(1) ?: "") else ""
                
                // Spotify OG description: "Song · Artist · 2024" or "Playlist · 50 songs"
                val extractedArtist = if (rawDesc.contains(" · ")) {
                    rawDesc.substringBefore(" · ").trim()
                } else "Spotify"

                if (rawTitle.isNotEmpty()) {
                    val finalTitle = if (rawTitle.contains(" - song by ")) rawTitle.substringBefore(" - song by ") else rawTitle
                    val item = StreamingItem(
                        youtubeUrl = "ytsearch1:$finalTitle $extractedArtist",
                        title = finalTitle,
                        artist = extractedArtist,
                        thumbnailUrl = apiMeta?.thumbnailUrl ?: "",
                        isPlaylist = false
                    )
                    PulseLogger.updateTask(null)
                    return@withContext listOf(item)
                }
            }

            return@withContext fallbackWithMeta(apiMeta)
        } catch (e: Exception) {
            Log.e(TAG, "Embed Scrape Failed", e)
            CrashTracker.recordException(e, "Spotify embed scrape failed", mapOf("spotify_url" to url))
            return@withContext fallbackWithMeta(apiMeta)
        }
    }

    private suspend fun fallbackWithMeta(apiMeta: com.example.song.data.api.SpotifyResponse?): List<StreamingItem> {
        if (apiMeta == null) {
            PulseLogger.log("Critical: No metadata found for Spotify link. Aborting to prevent random matches.", isError = true)
            return emptyList()
        }

        PulseLogger.log("Using YouTube Music Bridge for: ${apiMeta.title}")
        return try {
            val query = "${apiMeta.title} ${apiMeta.artist ?: ""}".trim()
            val results = YoutubeStreamHandler.getMetadata("ytsearch1:$query")
            results.map { 
                if (it.isPlaylist) it.copy(title = apiMeta.title, artist = apiMeta.artist, thumbnailUrl = apiMeta.thumbnailUrl)
                else it.copy(artist = apiMeta.artist ?: it.artist)
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            PulseLogger.updateTask(null)
        }
    }

    private fun extractSpotifyImage(json: JSONObject): String? {
        val imgObj = json.optJSONObject("visual") 
            ?: json.optJSONObject("coverArt") 
            ?: json.optJSONObject("artwork")
        
        if (imgObj != null) {
            val sources = imgObj.optJSONArray("sources")
            if (sources != null && sources.length() > 0) {
                return sources.getJSONObject(sources.length() - 1).optString("url")
            }
        }
        return null
    }
}
