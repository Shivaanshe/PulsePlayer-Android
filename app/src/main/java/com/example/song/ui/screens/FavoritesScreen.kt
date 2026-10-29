package com.example.song.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.song.LocalBottomBarHeight
import com.example.song.data.model.Song
import com.example.song.ui.components.SongListItem
import com.example.song.util.dragGestureHandler
import com.example.song.viewmodel.SongViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(viewModel: SongViewModel, onSongClick: () -> Unit) {
    val favoriteSongs by viewModel.favoriteSongs.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedSongIds by viewModel.selectedSongIds.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isArrangeModeEnabled by viewModel.isArrangeModeEnabled.collectAsState()
    var localSongs by remember { mutableStateOf(emptyList<Song>()) }
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var activeDraggedItem by remember { mutableStateOf<Song?>(null) }
    var currentDragY by remember { mutableFloatStateOf(0f) }
    var itemTouchOffset by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    var measuredItemHeightPx by remember { mutableFloatStateOf(0f) }
    var isManualOrder by remember { mutableStateOf(false) }

    LaunchedEffect(favoriteSongs, draggedItemIndex, isManualOrder) { if (draggedItemIndex == null && !isManualOrder) localSongs = favoriteSongs }
    LaunchedEffect(isArrangeModeEnabled) { if (!isArrangeModeEnabled) { draggedItemIndex = null; activeDraggedItem = null; targetIndex = null } }

    LaunchedEffect(listState) {
        snapshotFlow { 
            (listState.firstVisibleItemIndex * 200f) + listState.firstVisibleItemScrollOffset 
        }.collect { offset ->
            viewModel.updateGlobalScrollOffset(offset)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    title = { 
                        Text(
                            if (isArrangeModeEnabled) "Arrange Songs" else "Favorites", 
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold, 
                                color = Color.White
                            )
                        ) 
                    },
                    actions = { 
                        if (isArrangeModeEnabled) {
                            TextButton(onClick = { viewModel.toggleArrangeMode(false) }, modifier = Modifier.padding(end = 8.dp)) {
                                Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFFE91E63))
                            }
                        }
                    }
                )
            }
        ) { padding ->
            if (localSongs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("No favorite songs yet", style = MaterialTheme.typography.bodyLarge, color = Color.White) }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).dragGestureHandler(listState = listState, isReorderMode = isArrangeModeEnabled,
                    onSelectStart = { key -> if (key is Int) viewModel.startRangeSelection(key, localSongs.map { it.id }) },
                    onSelectUpdate = { key -> if (key is Int) viewModel.updateRangeSelection(key, localSongs.map { it.id }) },
                    onSelectEnd = { viewModel.endRangeSelection() },
                    onReorderStart = { key, fingerY, itemTop -> if (key is Int) { val index = localSongs.indexOfFirst { it.id == key }; if (index != -1) { draggedItemIndex = index; activeDraggedItem = localSongs[index]; targetIndex = index; currentDragY = fingerY; itemTouchOffset = fingerY - itemTop } } },
                    onReorderUpdate = { y -> currentDragY = y; if (draggedItemIndex != null) { val info = listState.layoutInfo; val itemUnderFinger = info.visibleItemsInfo.find { y.toInt() in it.offset..(it.offset + it.size) }; itemUnderFinger?.let { hitItem -> val hitKey = hitItem.key; if (hitKey is Int) { val newTarget = localSongs.indexOfFirst { it.id == hitKey }; if (newTarget != -1 && newTarget != targetIndex) targetIndex = newTarget } } } },
                    onReorderEnd = { if (draggedItemIndex != null && targetIndex != null) { isManualOrder = true; val mutable = localSongs.toMutableList(); val song = mutable.removeAt(draggedItemIndex!!); mutable.add(targetIndex!!, song); localSongs = mutable; viewModel.reorderFavorites(localSongs); scope.launch { delay(800); isManualOrder = false } }; draggedItemIndex = null; activeDraggedItem = null; targetIndex = null }
                ), contentPadding = PaddingValues(bottom = LocalBottomBarHeight.current + 16.dp)) {
                    itemsIndexed(localSongs, key = { _, song -> song.id }) { index, song ->
                        val isDragging = draggedItemIndex == index
                        val itemHeightPx = measuredItemHeightPx
                        val targetDisplacement = when { isDragging -> 0f; draggedItemIndex == null || targetIndex == null || itemHeightPx == 0f -> 0f; draggedItemIndex!! < targetIndex!! && index > draggedItemIndex!! && index <= targetIndex!! -> -itemHeightPx; draggedItemIndex!! > targetIndex!! && index < draggedItemIndex!! && index >= targetIndex!! -> itemHeightPx; else -> 0f }
                        val itemTranslationY by animateFloatAsState(targetValue = targetDisplacement, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "DragTranslation")
                        val isGhostSlot = !isDragging && targetIndex == index
                        Box(modifier = Modifier.fillMaxWidth().animateItem().zIndex(if (isGhostSlot) 1f else 0f).onGloballyPositioned { if (measuredItemHeightPx == 0f) measuredItemHeightPx = it.size.height.toFloat() }.graphicsLayer { translationY = itemTranslationY }) {
                            if (isGhostSlot) { Box(modifier = Modifier.fillMaxWidth().height(with(LocalDensity.current) { measuredItemHeightPx.toDp() }).graphicsLayer { translationY = -itemTranslationY }.padding(horizontal = 24.dp, vertical = 8.dp).border(width = 2.dp, brush = Brush.linearGradient(colors = listOf(Color(0xFFFF4081).copy(alpha = 0.5f), Color(0xFFFF4081).copy(alpha = 0.2f))), shape = RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text("DROP SONG HERE", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF4081).copy(alpha = 0.6f), letterSpacing = 2.sp)) } }
                            Box(modifier = Modifier.graphicsLayer { alpha = if (isDragging) 0f else 1f }) { SongListItem(song = song, onPlayClick = { if (isSelectionMode) viewModel.toggleSongSelection(song.id) else { viewModel.playSong(song, localSongs); onSongClick() } }, onFavoriteToggle = { viewModel.updateFavorite(song, !song.isFavorite) }, onDelete = { viewModel.deleteSong(song.id) }, isSelected = selectedSongIds.contains(song.id), onLongClick = { viewModel.toggleSelectionMode(true); viewModel.toggleSongSelection(song.id) }, onOptionsClick = { viewModel.openSongOptions(song) }, selectionMode = isSelectionMode, isPlaying = currentSong?.id == song.id, isArrangeMode = isArrangeModeEnabled, isDragging = false) }
                        }
                    }
                }
            }
        }
        activeDraggedItem?.let { draggedItem ->
            Box(modifier = Modifier.fillMaxWidth().offset { IntOffset(0, (currentDragY - itemTouchOffset).roundToInt()) }.zIndex(100f)) {
                val itemScale by animateFloatAsState(targetValue = 1.1f, label = "FloatingScale", animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                Box(modifier = Modifier.graphicsLayer { scaleX = itemScale; scaleY = itemScale; shadowElevation = 32.dp.toPx(); shape = RoundedCornerShape(20.dp); clip = true }) {
                    SongListItem(song = draggedItem, onPlayClick = {}, onFavoriteToggle = {}, onDelete = {}, isArrangeMode = true, isDragging = true)
                }
            }
        }
        AnimatedVisibility(visible = isSelectionMode, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(), modifier = Modifier.zIndex(10f)) {
            Surface(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.85f), tonalElevation = 8.dp, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))) {
                Row(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.toggleSelectionMode(false) }) { Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF424242)) }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "${selectedSongIds.size} Selected", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF333333)), modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.addSelectedToQueue(playNext = false) }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Add Selected to Queue", tint = Color(0xFF4CAF50)) }
                    IconButton(onClick = { val count = selectedSongIds.size; viewModel.deleteSelectedItems(); scope.launch { snackbarHostState.showSnackbar("Deleted $count items") } }) { Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = Color.Red) }
                }
            }
        }
    }
}
