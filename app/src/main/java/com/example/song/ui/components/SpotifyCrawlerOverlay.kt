package com.example.song.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.song.data.crawler.CrawlPipelineStatus
import com.example.song.data.crawler.SpotifyCrawlPipeline
import com.example.song.data.model.CrawlStatus

@Composable
fun SpotifyCrawlerOverlay(
    targetUrl: String,
    pipeline: SpotifyCrawlPipeline,
    pipelineState: CrawlPipelineStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onImportToLibrary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Enabled by default so user sees the spider crawler in full visual glory!
    var isWebVisible by remember { mutableStateOf(true) }

    val capturedCount = when (pipelineState) {
        is CrawlPipelineStatus.Running -> pipelineState.capturedCount
        is CrawlPipelineStatus.Finished -> pipelineState.capturedCount
        else -> 0
    }

    val expectedCount = when (pipelineState) {
        is CrawlPipelineStatus.Running -> pipelineState.expectedCount
        is CrawlPipelineStatus.Finished -> pipelineState.expectedCount
        else -> null
    }

    val currentStatus = when (pipelineState) {
        is CrawlPipelineStatus.Running -> pipelineState.status
        is CrawlPipelineStatus.Finished -> pipelineState.finalStatus
        is CrawlPipelineStatus.Failed -> CrawlStatus.FAILED_RECOVERABLE
        else -> CrawlStatus.IDLE
    }

    val isCrawling = currentStatus == CrawlStatus.CRAWLING || currentStatus == CrawlStatus.INITIALIZING

    val infiniteTransition = rememberInfiniteTransition(label = "SpiderPulse")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing)
        ),
        label = "SpinAngle"
    )

    Dialog(
        onDismissRequest = { if (!isCrawling) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isCrawling,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .fillMaxHeight(0.90f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF121815).copy(alpha = 0.95f))
                    .border(
                        1.5.dp,
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF00F0FF).copy(alpha = 0.6f),
                                Color(0xFF00E676).copy(alpha = 0.3f),
                                Color(0xFFFF2A85).copy(alpha = 0.4f)
                            )
                        ),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = "Spider Crawler",
                                tint = Color(0xFF00F0FF),
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(if (isCrawling) spinAngle else 0f)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Spotify Spider Crawler",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            Text(
                                text = "PRD v2.1 Visual Scraper",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }

                    IconButton(onClick = { isWebVisible = !isWebVisible }) {
                        Icon(
                            imageVector = if (isWebVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle View",
                            tint = Color(0xFF00F0FF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Expanded Prominent Viewport for Visual Spider Crawler
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                ) {
                    SpotifyCrawlerWebView(
                        targetUrl = targetUrl,
                        pipeline = pipeline,
                        isCrawling = isCrawling,
                        onFinished = {}
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progress Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xFF00F0FF).copy(alpha = 0.3f), Color(0xFF00E676).copy(alpha = 0.3f))
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when (currentStatus) {
                                CrawlStatus.INITIALIZING -> "Initializing Spider & Hydrating DOM..."
                                CrawlStatus.CRAWLING -> "Spider Crawling Tracklist..."
                                CrawlStatus.PAUSED_PARTIAL -> "Crawl Paused"
                                CrawlStatus.COMPLETED, CrawlStatus.COMPLETED_WITH_GAPS -> "Crawl Completed!"
                                CrawlStatus.INCOMPLETE_HALTED -> "Crawl Halted (Partial Captured)"
                                CrawlStatus.FAILED_RECOVERABLE -> "Bot Wall / Connection Stalled"
                                else -> "Ready to Crawl"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isCrawling) Color(0xFF00F0FF) else Color.White
                            ),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Progress Bar
                        if (expectedCount != null && expectedCount > 0) {
                            val progress = (capturedCount.toFloat() / expectedCount.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                                color = Color(0xFF00E676),
                                trackColor = Color.White.copy(alpha = 0.1f)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "$capturedCount / $expectedCount tracks captured (${(progress * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.85f))
                            )
                        } else {
                            if (isCrawling) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(CircleShape),
                                    color = Color(0xFF00F0FF),
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "$capturedCount tracks captured",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.85f))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCrawling) {
                        OutlinedButton(
                            onClick = onPause,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause", fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                    } else if (currentStatus == CrawlStatus.PAUSED_PARTIAL || currentStatus == CrawlStatus.INCOMPLETE_HALTED) {
                        Button(
                            onClick = onResume,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F0FF), contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    if (capturedCount > 0) {
                        Button(
                            onClick = onImportToLibrary,
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import ($capturedCount)", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                        }
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(0.8f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                    ) {
                        Text("Close", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
