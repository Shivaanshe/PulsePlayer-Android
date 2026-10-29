package com.example.song.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.example.song.LocalBottomBarHeight
import com.example.song.data.model.Song
import com.example.song.ui.components.ImmersivePlaylistHeader
import com.example.song.ui.components.SongListItem
import com.example.song.util.dragGestureHandler
import com.example.song.viewmodel.SongViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Int,
    playlistName: String,
    viewModel: SongViewModel,
    onBackClick: () -> Unit,
    onSongClick: () -> Unit
) {
    val songsInPlaylist by if (playlistId == -1) {
        viewModel.favoriteSongs.collectAsState(initial = emptyList())
    } else {
        viewModel.getSongsInPlaylist(playlistId).collectAsState(initial = emptyList())
    }
    
    var localSongsInPlaylist by remember { mutableStateOf(emptyList<Song>()) }
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var activeDraggedItem by remember { mutableStateOf<Song?>(null) }
    var currentDragY by remember { mutableFloatStateOf(0f) }
    var itemTouchOffset by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    var measuredItemHeightPx by remember { mutableFloatStateOf(0f) }
    var isManualOrder by remember { mutableStateOf(false) }
    
    LaunchedEffect(songsInPlaylist, draggedItemIndex, isManualOrder) {
        if (draggedItemIndex == null && !isManualOrder) {
            localSongsInPlaylist = songsInPlaylist
        }
    }
    
    val isArrangeModeEnabled by viewModel.isArrangeModeEnabled.collectAsState()

    LaunchedEffect(isArrangeModeEnabled) {
        if (!isArrangeModeEnabled) {
            draggedItemIndex = null
            activeDraggedItem = null
            targetIndex = null
        }
    }

    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedSongIds by viewModel.selectedSongIds.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val repeatMode by viewModel.repeatMode.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .dragGestureHandler(
                            listState = listState,
                            isReorderMode = isArrangeModeEnabled,
                            onSelectStart = { key ->
                                if (key is Int) {
                                    viewModel.startRangeSelection(key, localSongsInPlaylist.map { it.id })
                                }
                            },
                            onSelectUpdate = { key ->
                                if (key is Int) {
                                    viewModel.updateRangeSelection(key, localSongsInPlaylist.map { it.id })
                                }
                            },
                            onSelectEnd = {
                                viewModel.endRangeSelection()
                            },
                            onReorderStart = { key, fingerY, itemTop ->
                                if (key is Int && playlistId != -1) {
                                    val index = localSongsInPlaylist.indexOfFirst { it.id == key }
                                    if (index != -1) {
                                        draggedItemIndex = index
                                        activeDraggedItem = localSongsInPlaylist[index]
                                        targetIndex = index
                                        currentDragY = fingerY
                                        itemTouchOffset = fingerY - itemTop
                                    }
                                }
                            },
                            onReorderUpdate = { y ->
                                if (draggedItemIndex != null) {
                                    currentDragY = y
                                    val info = listState.layoutInfo
                                    val itemUnderFinger = info.visibleItemsInfo.find { 
                                        y.toInt() in it.offset..(it.offset + it.size)
                                    }
                                    itemUnderFinger?.let { hitItem ->
                                        val hitKey = hitItem.key
                                        if (hitKey is Int) {
                                            val newTarget = localSongsInPlaylist.indexOfFirst { it.id == hitKey }
                                            if (newTarget != -1 && newTarget != targetIndex) {
                                                targetIndex = newTarget
                                            }
                                        }
                                    }
                                }
                            },
                            onReorderEnd = {
                                if (draggedItemIndex != null && targetIndex != null) {
                                    isManualOrder = true
                                    val mutable = localSongsInPlaylist.toMutableList()
                                    val song = mutable.removeAt(draggedItemIndex!!)
                                    mutable.add(targetIndex!!, song)
                                    localSongsInPlaylist = mutable
                                    viewModel.updatePlaylistSongOrder(playlistId, localSongsInPlaylist)
                                    scope.launch {
                                        delay(800)
                                        isManualOrder = false
                                    }
                                }
                                draggedItemIndex = null
                                activeDraggedItem = null
                                targetIndex = null
                            }
                        ),
                    contentPadding = PaddingValues(bottom = LocalBottomBarHeight.current + 16.dp)
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (!isSelectionMode && !isArrangeModeEnabled) {
                                IconButton(
                                    onClick = onBackClick,
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color.White
                                    )
                                }
                            }

                            if (isArrangeModeEnabled) {
                                TextButton(
                                    onClick = { viewModel.toggleArrangeMode(false) },
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                ) {
                                    Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                }
                            } else {
                                IconButton(
                                    onClick = { viewModel.toggleRepeatMode() },
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .background(Color.White.copy(alpha = 0.15f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = when (repeatMode) {
                                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                                            else -> Icons.Default.Repeat
                                        },
                                        contentDescription = "Repeat Mode",
                                        tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.6f) else Color(0xFF00E676)
                                    )
                                }
                            }
                        }

                        val coverImage = localSongsInPlaylist.firstOrNull { !it.imageUrl.isNullOrEmpty() }?.imageUrl
                        val totalDurationMs = remember(localSongsInPlaylist) { localSongsInPlaylist.sumOf { it.duration } }

                        ImmersivePlaylistHeader(
                            title = if (isArrangeModeEnabled) "Arrange Songs" else playlistName,
                            subtitle = null,
                            coverUrl = coverImage,
                            songCount = localSongsInPlaylist.size,
                            totalDurationMs = totalDurationMs,
                            onPlayAllClick = {
                                if (localSongsInPlaylist.isNotEmpty()) {
                                    viewModel.playSong(localSongsInPlaylist.first(), localSongsInPlaylist)
                                    onSongClick()
                                }
                            },
                            onShuffleClick = {
                                if (localSongsInPlaylist.isNotEmpty()) {
                                    viewModel.setShuffle(true)
                                    val randomSong = localSongsInPlaylist.random()
                                    viewModel.playSong(randomSong, localSongsInPlaylist)
                                    onSongClick()
                                }
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Songs in Playlist",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${localSongsInPlaylist.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                )
                            }
                        }
                    }

                    itemsIndexed(localSongsInPlaylist, key = { _, song -> song.id }) { index, song ->
                        val isDragging = draggedItemIndex == index
                        val itemHeightPx = measuredItemHeightPx
                        val targetDisplacement = when {
                            isDragging -> 0f
                            draggedItemIndex == null || targetIndex == null || itemHeightPx == 0f -> 0f
                            draggedItemIndex!! < targetIndex!! && index > draggedItemIndex!! && index <= targetIndex!! -> -itemHeightPx
                            draggedItemIndex!! > targetIndex!! && index < draggedItemIndex!! && index >= targetIndex!! -> itemHeightPx
                            else -> 0f
                        }

                        val itemTranslationY by animateFloatAsState(
                            targetValue = targetDisplacement,
                            label = "DragTranslation",
                            animationSpec = spring(stiffness = Spring.StiffnessLow)
                        )
                        val isGhostSlot = !isDragging && targetIndex == index

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .zIndex(if (isGhostSlot) 1f else 0f)
                                .onGloballyPositioned { 
                                    if (measuredItemHeightPx == 0f) measuredItemHeightPx = it.size.height.toFloat()
                                }
                                .graphicsLayer { translationY = itemTranslationY }
                        ) {
                            if (isGhostSlot) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(LocalDensity.current) { measuredItemHeightPx.toDp() })
                                        .graphicsLayer { translationY = -itemTranslationY }
                                        .padding(horizontal = 24.dp, vertical = 8.dp)
                                        .border(
                                            width = 2.dp,
                                            brush = Brush.linearGradient(colors = listOf(Color(0xFF4CAF50).copy(alpha = 0.5f), Color(0xFF4CAF50).copy(alpha = 0.2f))),
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("DROP SONG HERE", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, color = Color(0xFF4CAF50).copy(alpha = 0.6f), letterSpacing = 2.sp))
                                }
                            }

                            Box(modifier = Modifier.graphicsLayer { alpha = if (isDragging) 0f else 1f }) {
                                SongListItem(
                                    song = song,
                                    onPlayClick = {
                                        if (isSelectionMode) viewModel.toggleSongSelection(song.id)
                                        else { viewModel.playSong(song, localSongsInPlaylist); onSongClick() }
                                    },
                                    onFavoriteToggle = { viewModel.updateFavorite(song, !song.isFavorite) },
                                    onDelete = { if (playlistId == -1) viewModel.updateFavorite(song, false) else viewModel.removeSongFromPlaylist(song.id, playlistId) },
                                    isSelected = selectedSongIds.contains(song.id),
                                    onLongClick = { viewModel.toggleSelectionMode(true); viewModel.toggleSongSelection(song.id) },
                                    onOptionsClick = { viewModel.openSongOptions(song) },
                                    selectionMode = isSelectionMode,
                                    isPlaying = currentSong?.id == song.id,
                                    isArrangeMode = isArrangeModeEnabled,
                                    isDragging = false
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Overlay
        activeDraggedItem?.let { draggedItem ->
            Box(modifier = Modifier.fillMaxWidth().offset { IntOffset(0, (currentDragY - itemTouchOffset).roundToInt()) }.zIndex(100f)) {
                val itemScale by animateFloatAsState(targetValue = 1.1f, label = "FloatingScale", animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                Box(modifier = Modifier.graphicsLayer { scaleX = itemScale; scaleY = itemScale; shadowElevation = 32.dp.toPx(); shape = RoundedCornerShape(20.dp); clip = true }) {
                    SongListItem(song = draggedItem, onPlayClick = {}, onFavoriteToggle = {}, onDelete = {}, isArrangeMode = true, isDragging = true)
                }
            }
        }

        // Selection Action Bar
        AnimatedVisibility(visible = isSelectionMode, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(), modifier = Modifier.zIndex(10f)) {
            Surface(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.85f), tonalElevation = 8.dp, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))) {
                Row(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.toggleSelectionMode(false) }) { Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF424242)) }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "${selectedSongIds.size} Selected", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF333333)), modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.addSelectedToQueue(playNext = false) }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Add Selected to Queue", tint = Color(0xFF4CAF50)) }
                    IconButton(onClick = { viewModel.removeSelectedFromPlaylist(playlistId) }) { Icon(Icons.Default.Delete, contentDescription = "Remove Selected", tint = Color.Red) }
                }
            }
        }
    }
}
