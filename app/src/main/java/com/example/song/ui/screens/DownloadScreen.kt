package com.example.song.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.song.SongApplication
import com.example.song.viewmodel.DownloadState
import com.example.song.viewmodel.SongViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(viewModel: SongViewModel) {
    val downloadState by viewModel.downloadState.collectAsState()
    val isEngineReady by SongApplication.getInstance().isReady.collectAsState()
    var youtubeUrl by remember { mutableStateOf("") }
    val isUrlValid = remember(youtubeUrl) {
        val trimmed = youtubeUrl.trim()
        trimmed.isBlank() || (trimmed.startsWith("http") && (trimmed.contains("youtube.com") || trimmed.contains("youtu.be")))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    title = {
                        Text(
                            "Download Music",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF333333)
                            )
                        )
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Glass Card for Input
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color(0xFFE91E63)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            "YouTube Downloader",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF333333)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            "Paste a YouTube link below to download audio directly to your library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF666666),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))

                        if (downloadState is DownloadState.Downloading) {
                            val progress = (downloadState as DownloadState.Downloading).progress
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LinearProgressIndicator(
                                    progress = { if (progress > 0) progress / 100f else 0f },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFFE91E63),
                                    trackColor = Color.White.copy(alpha = 0.3f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    if (progress > 0) "Downloading... ${progress.toInt()}%" else "Preparing...",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFF333333)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { viewModel.cancelDownload() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        } else {
                            TextField(
                                value = youtubeUrl,
                                onValueChange = { youtubeUrl = it },
                                placeholder = { Text("https://youtube.com/...") },
                                singleLine = true,
                                isError = !isUrlValid && youtubeUrl.trim().isNotBlank(),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.3f),
                                    unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
                                    focusedIndicatorColor = Color(0xFFE91E63),
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (!isUrlValid && youtubeUrl.trim().isNotBlank()) {
                                Text(
                                    "Invalid YouTube URL",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.Start).padding(top = 4.dp, start = 8.dp)
                                )
                            }

                            if (downloadState is DownloadState.Error) {
                                Text(
                                    (downloadState as DownloadState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            
                            if (downloadState is DownloadState.Success) {
                                Text(
                                    "Download successful!",
                                    color = Color(0xFF4CAF50),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                LaunchedEffect(downloadState) {
                                    kotlinx.coroutines.delay(3000)
                                    viewModel.resetDownloadState()
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = {
                                    val urlToDownload = youtubeUrl.trim()
                                    if (urlToDownload.isNotBlank() && isUrlValid && isEngineReady) {
                                        youtubeUrl = ""
                                        viewModel.downloadFromYoutube(urlToDownload)
                                    }
                                },
                                enabled = youtubeUrl.trim().isNotBlank() && isUrlValid && isEngineReady,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE91E63),
                                    disabledContainerColor = Color(0xFFE91E63).copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) {
                                Text(if (isEngineReady) "Download Now" else "Initializing Engine...")
                            }
                        }
                    }
                }
            }
        }
    }
}
