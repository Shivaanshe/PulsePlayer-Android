package com.example.song.data.crawler

import android.util.Log
import com.example.song.data.dao.SpotifyCrawlDao
import com.example.song.data.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SpotifyCrawlPipeline(
    private val dao: SpotifyCrawlDao,
    private val httpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "SpotifyCrawlPipeline"
        private val SPOTIFY_ID_REGEX = Regex("^[A-Za-z0-9]{22}$")
        private val PLAYLIST_ID_PATTERN = Pattern.compile("playlist/([A-Za-z0-9]{22})")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    // Bounded channel (capacity 64, suspend backpressure, NO DROP_OLDEST per PRD §2.4)
    private val messageChannel = Channel<String>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    private val _pipelineState = MutableStateFlow<CrawlPipelineStatus>(CrawlPipelineStatus.Idle)
    val pipelineState: StateFlow<CrawlPipelineStatus> = _pipelineState.asStateFlow()

    private var activePlaylistId: String? = null
    private var activeGeneration: Int = 1
    private var expectedCount: Int? = null
    private var consumerJob: Job? = null

    init {
        startConsumer()
    }

    private fun startConsumer() {
        consumerJob?.cancel()
        consumerJob = scope.launch {
            for (rawJson in messageChannel) {
                try {
                    processIncomingMessage(rawJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing bridge message", e)
                }
            }
        }
    }

    data class PreflightResult(
        val playlistId: String,
        val cleanUrl: String,
        val title: String?,
        val expectedCount: Int?,
        val route: PreflightRoute
    )

    enum class PreflightRoute {
        LIGHTWEIGHT_PARSER,
        WEBVIEW_CRAWLER,
        UNAVAILABLE,
        OFFLINE
    }

    suspend fun sanitizeAndPreflight(rawUrl: String): PreflightResult = withContext(Dispatchers.IO) {
        val expandedUrl = expandShortLinkIfNeeded(rawUrl)
        val playlistId = extractPlaylistId(expandedUrl)
            ?: throw IllegalArgumentException("Invalid Spotify Playlist URL: $rawUrl")

        val cleanUrl = "https://open.spotify.com/playlist/$playlistId"

        // HTTP preflight request (8s timeout)
        val shortClient = httpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        try {
            val response = shortClient.newCall(request).execute()
            if (response.code == 404 || response.code == 410) {
                return@withContext PreflightResult(playlistId, cleanUrl, null, null, PreflightRoute.UNAVAILABLE)
            }

            val html = response.body?.string() ?: ""
            val (title, count) = parseHeaderMetaFromHtml(html)

            val route = when {
                count != null && count <= 100 -> PreflightRoute.LIGHTWEIGHT_PARSER
                else -> PreflightRoute.WEBVIEW_CRAWLER
            }

            PreflightResult(playlistId, cleanUrl, title, count, route)
        } catch (e: Exception) {
            Log.w(TAG, "Preflight request failed, falling back to WebView crawler with unknown count", e)
            PreflightResult(playlistId, cleanUrl, null, null, PreflightRoute.WEBVIEW_CRAWLER)
        }
    }

    fun prepareCrawl(playlistId: String, initialExpectedCount: Int? = null, title: String? = null) {
        activePlaylistId = playlistId
        expectedCount = initialExpectedCount
        activeGeneration = 1

        scope.launch {
            val existing = dao.getPlaylist(playlistId)
            val now = System.currentTimeMillis()
            if (existing != null) {
                activeGeneration = existing.crawlGeneration
                dao.updatePlaylist(
                    existing.copy(
                        title = title ?: existing.title,
                        expectedCount = initialExpectedCount ?: existing.expectedCount,
                        status = CrawlStatus.INITIALIZING,
                        updatedAt = now
                    )
                )
            } else {
                dao.insertPlaylist(
                    PlaylistEntity(
                        playlistId = playlistId,
                        title = title,
                        expectedCount = initialExpectedCount,
                        status = CrawlStatus.INITIALIZING,
                        crawlGeneration = 1,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
            _pipelineState.value = CrawlPipelineStatus.Running(playlistId, 0, initialExpectedCount, CrawlStatus.INITIALIZING)
        }
    }

    suspend fun postBridgeMessage(rawJson: String) {
        if (rawJson.length > 256 * 1024) {
            Log.w(TAG, "Dropping message exceeding 256KB cap")
            return
        }
        messageChannel.send(rawJson)
    }

    private suspend fun processIncomingMessage(rawJson: String) {
        val playlistId = activePlaylistId ?: return
        val jsonObj = try {
            gson.fromJson(rawJson, JsonObject::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON bridge message", e)
            return
        }

        if (!jsonObj.has("v") || jsonObj.get("v").asInt != 1) return
        val type = jsonObj.get("type")?.asString ?: return

        when (type) {
            "HELLO" -> {
                val scriptVer = jsonObj.get("scriptVersion")?.asString
                val selVer = jsonObj.get("selectorsVersion")?.asString
                Log.d(TAG, "Crawler HELLO: script=$scriptVer, selectors=$selVer")
            }

            "META" -> {
                val title = jsonObj.get("title")?.asString
                val exp = if (jsonObj.has("expectedCount") && !jsonObj.get("expectedCount").isJsonNull) {
                    jsonObj.get("expectedCount").asInt
                } else null

                if (exp != null) expectedCount = exp
                val current = dao.getPlaylist(playlistId)
                if (current != null) {
                    dao.updatePlaylist(
                        current.copy(
                            title = title ?: current.title,
                            expectedCount = exp ?: current.expectedCount,
                            status = CrawlStatus.CRAWLING,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }

            "BATCH" -> {
                val tracksArray = jsonObj.getAsJsonArray("tracks") ?: return
                val validEntities = mutableListOf<PlaylistTrackEntity>()

                for (elem in tracksArray) {
                    val obj = elem.asJsonObject
                    val rawIdx = if (obj.has("rowIndex") && !obj.get("rowIndex").isJsonNull) obj.get("rowIndex").asInt else null
                    val rowRaw = if (obj.has("rowRaw") && !obj.get("rowRaw").isJsonNull) obj.get("rowRaw").asInt else null
                    val calculatedIndex = rawIdx ?: rowRaw ?: continue

                    // Index normalization validation (1..10,000)
                    if (calculatedIndex < 1 || calculatedIndex > 10000) continue

                    val titleRaw = obj.get("title")?.asString ?: ""
                    val titleClean = sanitizeString(titleRaw, 500)
                    if (titleClean.isEmpty()) continue

                    val artistsClean = sanitizeString(obj.get("artists")?.asString ?: "Unknown Artist", 500)
                    val albumClean = sanitizeString(obj.get("album")?.asString ?: "", 500).ifEmpty { null }

                    val rawId = obj.get("spotifyId")?.asString
                    val validId = if (rawId != null && SPOTIFY_ID_REGEX.matches(rawId)) rawId else null

                    val durationMs = if (obj.has("durationMs") && !obj.get("durationMs").isJsonNull) {
                        val d = obj.get("durationMs").asLong
                        if (d > 0) d else null
                    } else null

                    val rawRowType = obj.get("rowType")?.asString ?: "TRACK"
                    val rowType = when (rawRowType.uppercase()) {
                        "EPISODE" -> RowType.EPISODE
                        "LOCAL" -> RowType.LOCAL
                        "UNAVAILABLE" -> RowType.UNAVAILABLE
                        else -> if (validId != null) RowType.TRACK else RowType.UNAVAILABLE
                    }

                    val thumbRaw = if (obj.has("thumbnailUrl") && !obj.get("thumbnailUrl").isJsonNull) obj.get("thumbnailUrl").asString else null

                    validEntities.add(
                        PlaylistTrackEntity(
                            playlistId = playlistId,
                            rowIndex = calculatedIndex,
                            rowType = rowType,
                            spotifyId = validId,
                            title = titleClean,
                            artists = artistsClean,
                            album = albumClean,
                            thumbnailUrl = thumbRaw,
                            durationMs = durationMs,
                            explicit = obj.get("explicit")?.asBoolean ?: false,
                            resolveState = ResolveState.PENDING,
                            capturedGeneration = activeGeneration
                        )
                    )
                }

                if (validEntities.isNotEmpty()) {
                    dao.insertBatchTransactional(playlistId, validEntities, expectedCount)
                    val captured = dao.getCapturedCount(playlistId)
                    _pipelineState.value = CrawlPipelineStatus.Running(
                        playlistId, captured, expectedCount, CrawlStatus.CRAWLING
                    )
                }
            }

            "PROGRESS" -> {
                val dbCount = dao.getCapturedCount(playlistId)
                val msgCount = if (jsonObj.has("captured") && !jsonObj.get("captured").isJsonNull) jsonObj.get("captured").asInt else 0
                val totalCaptured = maxOf(dbCount, msgCount)
                _pipelineState.value = CrawlPipelineStatus.Running(
                    playlistId, totalCaptured, expectedCount, CrawlStatus.CRAWLING
                )
            }

            "ERROR" -> {
                val code = jsonObj.get("code")?.asString ?: "UNKNOWN"
                Log.e(TAG, "Crawler posted ERROR: $code")
                val status = if (code == "NO_ROWS" || code == "BOT_WALL") CrawlStatus.FAILED_RECOVERABLE else CrawlStatus.FAILED_TERMINAL
                dao.finalizeCrawlTransactional(playlistId, status, expectedCount)
                _pipelineState.value = CrawlPipelineStatus.Failed(playlistId, code)
            }

            "FINISHED" -> {
                val reason = jsonObj.get("reason")?.asString ?: "UNKNOWN"
                val exp = if (jsonObj.has("expected") && !jsonObj.get("expected").isJsonNull) jsonObj.get("expected").asInt else expectedCount

                val finalStatus = when (reason) {
                    "COMPLETE" -> CrawlStatus.COMPLETED
                    "STALLED", "CEILING" -> CrawlStatus.INCOMPLETE_HALTED
                    "USER_HALTED" -> CrawlStatus.PAUSED_PARTIAL
                    else -> CrawlStatus.COMPLETED_WITH_GAPS
                }

                dao.finalizeCrawlTransactional(playlistId, finalStatus, exp)
                val totalCaptured = dao.getCapturedCount(playlistId)
                _pipelineState.value = CrawlPipelineStatus.Finished(playlistId, totalCaptured, exp, finalStatus)
            }
        }
    }

    private fun expandShortLinkIfNeeded(url: String): String {
        if (!url.contains("spotify.link")) return url
        try {
            val req = Request.Builder().url(url).head().build()
            httpClient.newCall(req).execute().use { response ->
                val redirected = response.request.url.toString()
                if (redirected.contains("open.spotify.com")) return redirected
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed expanding short link: $url", e)
        }
        return url
    }

    private fun extractPlaylistId(url: String): String? {
        val matcher = PLAYLIST_ID_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun parseHeaderMetaFromHtml(html: String): Pair<String?, Int?> {
        val titleMatch = Pattern.compile("<meta property=\"og:title\" content=\"(.*?)\"").matcher(html)
        val title = if (titleMatch.find()) titleMatch.group(1) else null

        val descMatch = Pattern.compile("<meta property=\"og:description\" content=\"(.*?)\"").matcher(html)
        val desc = if (descMatch.find()) descMatch.group(1) else ""

        val countMatch = Pattern.compile("([0-9][0-9,.\\s\\u00a0]*)\\s*(songs|items|tracks)", Pattern.CASE_INSENSITIVE).matcher(desc)
        val count = if (countMatch.find()) {
            val numStr = countMatch.group(1)?.replace("[^0-9]".toRegex(), "") ?: ""
            numStr.toIntOrNull()
        } else null

        return Pair(title, count)
    }

    private fun sanitizeString(input: String, maxLen: Int): String {
        val clean = input
            .replace("[\u200B-\u200D\uFEFF]".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
        return if (clean.length > maxLen) clean.substring(0, maxLen) else clean
    }
}

sealed class CrawlPipelineStatus {
    data object Idle : CrawlPipelineStatus()
    data class Running(
        val playlistId: String,
        val capturedCount: Int,
        val expectedCount: Int?,
        val status: CrawlStatus
    ) : CrawlPipelineStatus()
    data class Failed(val playlistId: String, val errorCode: String) : CrawlPipelineStatus()
    data class Finished(
        val playlistId: String,
        val capturedCount: Int,
        val expectedCount: Int?,
        val finalStatus: CrawlStatus
    ) : CrawlPipelineStatus()
}
