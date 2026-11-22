package com.example.song.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.media3.common.Player
import coil.compose.AsyncImage

import com.example.song.viewmodel.SongViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: SongViewModel,
    onBackClick: () -> Unit
) {
    val song by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val playbackError by viewModel.playbackError.collectAsState()

    var sliderPosition by remember { mutableStateOf<Float?>(null) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
                            "Now Playing",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF424242)
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .padding(8.dp)
                                .background(Color.White.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Back",
                                tint = Color(0xFF424242)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.openQueueSheet() },
                            modifier = Modifier
                                .padding(8.dp)
                                .background(Color.White.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Open Queue",
                                tint = Color(0xFF424242)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.toggleRepeatMode() },
                            modifier = Modifier
                                .padding(8.dp)
                                .background(Color.White.copy(alpha = 0.3f), CircleShape)
                        ) {
                            Icon(
                                imageVector = when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                                    Player.REPEAT_MODE_ALL -> Icons.Default.Repeat
                                    else -> Icons.Default.Repeat
                                },
                                contentDescription = "Repeat Mode",
                                tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color(0xFF424242) else Color(0xFF4CAF50)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (isLandscape) {
                    // --- LANDSCAPE LAYOUT ---
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(40.dp)
                    ) {
                        // Artwork (Left)
                        Box(
                            modifier = Modifier
                                .size(280.dp)
                                .shadow(24.dp, RoundedCornerShape(32.dp))
                                .clip(RoundedCornerShape(32.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(32.dp))
                        ) {
                            androidx.compose.animation.Crossfade(targetState = song?.imageUrl, animationSpec = androidx.compose.animation.core.tween(450), label = "artwork_crossfade") { url ->
                                AsyncImage(
                                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                        .data(url)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        // Info & Controls (Right)
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            SongInfoSection(song, isLandscape = true)
                            
                            Spacer(modifier = Modifier.height(16.dp))

                            GlassSlider(
                                value = sliderPosition ?: currentPosition.toFloat(),
                                onValueChange = { 
                                    sliderPosition = it
                                    viewModel.setUserSeeking(true)
                                    viewModel.updateSeekPosition(it.toLong())
                                },
                                onValueChangeFinished = {
                                    viewModel.seekTo(sliderPosition?.toLong() ?: currentPosition)
                                    sliderPosition = null
                                },
                                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                                currentPosition = sliderPosition?.toLong() ?: currentPosition,
                                totalDuration = duration
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            PlaybackControls(
                                isPlaying = isPlaying,
                                isLandscape = true,
                                onSkipPrevious = { viewModel.skipToPrevious() },
                                onSkipNext = { viewModel.skipToNext() },
                                onTogglePlay = { viewModel.togglePlayPause() }
                            )
                        }
                    }
                } else {
                    // --- PORTRAIT LAYOUT ---
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.weight(0.1f))

                        // Album Art
                        Box(
                            modifier = Modifier
                                .size(320.dp)
                                .shadow(32.dp, RoundedCornerShape(40.dp))
                                .clip(RoundedCornerShape(40.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(40.dp))
                        ) {
                            androidx.compose.animation.Crossfade(targetState = song?.imageUrl, animationSpec = androidx.compose.animation.core.tween(450), label = "artwork_crossfade") { url ->
                                AsyncImage(
                                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                        .data(url)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(0.1f))

                        SongInfoSection(song, isFavorite = song?.isFavorite == true, onFavoriteToggle = { song?.let { viewModel.updateFavorite(it, !it.isFavorite) } })

                        Spacer(modifier = Modifier.height(32.dp))

                        GlassSlider(
                            value = sliderPosition ?: currentPosition.toFloat(),
                            onValueChange = { 
                                sliderPosition = it
                                viewModel.setUserSeeking(true)
                                viewModel.updateSeekPosition(it.toLong())
                            },
                            onValueChangeFinished = {
                                viewModel.seekTo(sliderPosition?.toLong() ?: currentPosition)
                                sliderPosition = null
                            },
                            valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                            currentPosition = sliderPosition?.toLong() ?: currentPosition,
                            totalDuration = duration
                        )

                        Spacer(modifier = Modifier.height(40.dp))

                        PlaybackControls(
                            isPlaying = isPlaying,
                            onSkipPrevious = { viewModel.skipToPrevious() },
                            onSkipNext = { viewModel.skipToNext() },
                            onTogglePlay = { viewModel.togglePlayPause() }
                        )

                        Spacer(modifier = Modifier.weight(0.2f))
                    }
                }

                // Error Snackbar
                playbackError?.let { error ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Red.copy(alpha = 0.8f))
                            .clickable { viewModel.clearPlaybackError() }
                            .padding(16.dp)
                    ) {
                        Text(
                            text = error,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SongInfoSection(
    song: com.example.song.data.model.Song?,
    isLandscape: Boolean = false,
    isFavorite: Boolean = false,
    onFavoriteToggle: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedContent(
            targetState = song?.title ?: "No Song",
            transitionSpec = {
                fadeIn() + slideInVertically { it / 2 } togetherWith
                        fadeOut() + slideOutVertically { -it / 2 }
            },
            label = "SongTitle"
        ) { title ->
            Text(
                text = title,
                style = (if (isLandscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium).copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333),
                    textAlign = TextAlign.Center
                ),
                maxLines = if (isLandscape) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            AnimatedContent(
                targetState = song?.artist ?: "Unknown Artist",
                modifier = Modifier.weight(1f, fill = false),
                label = "ArtistName"
            ) { artist ->
                Text(
                    text = artist,
                    style = (if (isLandscape) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge).copy(
                        color = Color(0xFF666666),
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (song != null) {
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(if (isLandscape) 24.dp else 32.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFE91E63) else Color(0xFF424242),
                        modifier = Modifier.size(if (isLandscape) 20.dp else 28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    isLandscape: Boolean = false,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onTogglePlay: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 24.dp else 32.dp)
    ) {
        IconButton(
            onClick = onSkipPrevious,
            modifier = Modifier
                .size(if (isLandscape) 48.dp else 64.dp)
                .background(Color.White.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = null,
                tint = Color(0xFF424242),
                modifier = Modifier.size(if (isLandscape) 24.dp else 32.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(if (isLandscape) 64.dp else 88.dp)
                .shadow(12.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFF4081), Color(0xFFE040FB))
                    )
                )
                .noRippleClickable { onTogglePlay() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (isLandscape) 32.dp else 48.dp)
            )
        }

        IconButton(
            onClick = onSkipNext,
            modifier = Modifier
                .size(if (isLandscape) 48.dp else 64.dp)
                .background(Color.White.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = null,
                tint = Color(0xFF424242),
                modifier = Modifier.size(if (isLandscape) 24.dp else 32.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    currentPosition: Long,
    totalDuration: Long
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color(0xFFE91E63),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.4f))
                        .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(10.dp).clip(RoundedCornerShape(5.dp)),
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color(0xFFE91E63),
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatTime(currentPosition),
                color = Color(0xFF555555),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp)
            )
            Text(
                formatTime(totalDuration),
                color = Color(0xFF555555),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp)
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
)
