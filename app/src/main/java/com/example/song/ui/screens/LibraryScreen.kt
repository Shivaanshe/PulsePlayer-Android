package com.example.song.ui.screens

import android.content.Intent
import android.net.Uri
import com.example.song.ui.components.OnlineSearchResultCard
import com.example.song.viewmodel.ItemActionState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import coil.compose.AsyncImage
import com.example.song.SongApplication
import com.example.song.data.model.Playlist
import com.example.song.data.model.Song
import com.example.song.ui.components.SongListItem
import com.example.song.util.dragGestureHandler
import com.example.song.util.horizontalDragGestureHandler
import com.example.song.viewmodel.DownloadState
import com.example.song.viewmodel.SongViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.example.song.ui.spotlight.SpotlightController
import com.example.song.ui.spotlight.TourStep
import com.example.song.ui.spotlight.spotlightTarget

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: SongViewModel,
    onPlaylistClick: (Playlist) -> Unit,
    onFavoritesClick: () -> Unit,
    onSongClick: () -> Unit,
    spotlightController: SpotlightController? = null
) {
    val songs by viewModel.filteredSongs.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favoriteSongs by viewModel.favoriteSongs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()
    val pendingDownloadItems by viewModel.pendingDownloadItems.collectAsState()
    val isEngineReady by SongApplication.getInstance().isReady.collectAsState()

    var isSearching by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var youtubeUrl by remember { mutableStateOf("") }
    val isUrlValid = remember(youtubeUrl) { 
        val trimmed = youtubeUrl.trim()
        trimmed.isBlank() || (trimmed.startsWith("http") && (trimmed.contains("youtube.com") || trimmed.contains("youtu.be") || trimmed.contains("spotify.com")))
    }
    val addIconRotation by animateFloatAsState(targetValue = if (showAddMenu) 135f else 0f, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "AddIconRotation")
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument(), onResult = { uri: Uri? -> uri?.let { try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { e.printStackTrace() }; val song = Song(title = it.lastPathSegment ?: "Unknown", audioUri = it.toString(), artist = "Unknown Artist"); viewModel.addSong(song) } })

    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedSongIds by viewModel.selectedSongIds.collectAsState()

    val onlineSearchResults by viewModel.onlineSearchResults.collectAsState()
    val isOnlineSearching by viewModel.isOnlineSearching.collectAsState()
    val itemActionStates by viewModel.itemActionStates.collectAsState()
    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val isArrangeModeEnabled by viewModel.isArrangeModeEnabled.collectAsState()
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var activeDraggedItem by remember { mutableStateOf<Song?>(null) }
    var currentDragY by remember { mutableFloatStateOf(0f) }
    var itemTouchOffset by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    var measuredItemHeightPx by remember { mutableFloatStateOf(0f) }
    var isManualOrder by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            viewModel.clearOnlineSearchResults()
        }
    }
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
                        if (isSearching) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                placeholder = { Text("Search songs...") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.White.copy(alpha = 0.2f), unfocusedContainerColor = Color.White.copy(alpha = 0.1f), focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                                shape = RoundedCornerShape(24.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { if (searchQuery.isNotBlank()) viewModel.searchOnline(searchQuery, isLibrary = true) }),
                                trailingIcon = { IconButton(onClick = { isSearching = false; viewModel.setSearchQuery(""); viewModel.clearOnlineSearchResults() }) { Icon(Icons.Default.Close, contentDescription = "Close search") } }
                            )
                        } else {
                            Text(
                                if (isArrangeModeEnabled) "Arrange Songs" else "My Library", 
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold, 
                                    color = Color.White
                                )
                            )
                        }
                    },
                    navigationIcon = { if (!isSearching && !isArrangeModeEnabled) IconButton(onClick = { isSearching = true }, modifier = Modifier.background(Color.White.copy(alpha = 0.3f), CircleShape)) { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF424242)) } },
                    actions = { if (!isSearching) { 
                        if (isArrangeModeEnabled) {
                            TextButton(onClick = { viewModel.toggleArrangeMode(false) }, modifier = Modifier.padding(end = 8.dp)) { 
                                Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFFE91E63)) 
                            }
                        } else {
                            IconButton(onClick = { showAddMenu = !showAddMenu }, modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.3f), CircleShape).spotlightTarget(TourStep.STEP_5_LIBRARY_IMPORT, spotlightController)) { Icon(imageVector = Icons.Default.Add, contentDescription = "Add Options", tint = Color(0xFF424242), modifier = Modifier.size(28.dp).rotate(addIconRotation)) }
                        }
                    } }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(
                    visible = downloadState !is DownloadState.Idle,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF121216).copy(alpha = 0.85f),
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            AnimatedContent(
                                targetState = downloadState,
                                transitionSpec = { fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200)) },
                                label = "DownloadContent"
                            ) { state ->
                                Column {
                                    when (state) {
                                        is DownloadState.Checking -> {
                                            LinearProgressIndicator(
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                color = Color(0xFFE91E63),
                                                trackColor = Color.White.copy(alpha = 0.1f)
                                            )
                                            Text(
                                                text = "Checking link...",
                                                modifier = Modifier.padding(top = 8.dp),
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color.White
                                            )
                                        }
                                        is DownloadState.Downloading -> {
                                            val progressFraction = state.progress / 100f
                                            LinearProgressIndicator(
                                                progress = { progressFraction },
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                color = Color(0xFFE91E63),
                                                trackColor = Color.White.copy(alpha = 0.1f)
                                            )
                                            val progressText = if (state.total > 1) {
                                                "Batch Download: ${state.current} of ${state.total} (${state.progress.toInt()}%)"
                                            } else {
                                                "Downloading... ${state.progress.toInt()}%"
                                            }
                                            Text(
                                                text = progressText,
                                                modifier = Modifier.padding(top = 8.dp),
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color.White
                                            )
                                        }
                                        is DownloadState.Success -> {
                                            LinearProgressIndicator(
                                                progress = { 1f },
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                color = Color(0xFF00E676),
                                                trackColor = Color.White.copy(alpha = 0.1f)
                                            )
                                            Text(
                                                text = "Download Complete",
                                                modifier = Modifier.padding(top = 8.dp),
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color(0xFF00E676)
                                            )
                                        }
                                        is DownloadState.Error -> {
                                            LinearProgressIndicator(
                                                progress = { 0f },
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                color = Color(0xFFF44336),
                                                trackColor = Color.White.copy(alpha = 0.1f)
                                            )
                                            Text(
                                                text = state.message,
                                                modifier = Modifier.padding(top = 8.dp),
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color(0xFFF44336)
                                            )
                                        }
                                        is DownloadState.Idle -> {}
                                    }
                                }
                            }
                        }
                    }
                }

                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().dragGestureHandler(listState = listState, isReorderMode = isArrangeModeEnabled,
                    onSelectStart = { key -> if (key is Int) viewModel.startRangeSelection(key, songs.map { it.id }) },
                    onSelectUpdate = { key -> if (key is Int) viewModel.updateRangeSelection(key, songs.map { it.id }) },
                    onSelectEnd = { viewModel.endRangeSelection() },
                    onReorderStart = { key, fingerY, itemTop -> if (key is Int) { val index = songs.indexOfFirst { it.id == key }; if (index != -1) { draggedItemIndex = index; activeDraggedItem = songs[index]; targetIndex = index; currentDragY = fingerY; itemTouchOffset = fingerY - itemTop } } },
                    onReorderUpdate = { y -> currentDragY = y; if (draggedItemIndex != null) { val info = listState.layoutInfo; val itemUnderFinger = info.visibleItemsInfo.find { y.toInt() in it.offset..(it.offset + it.size) }; itemUnderFinger?.let { hitItem -> val hitKey = hitItem.key; if (hitKey is Int) { val newTarget = songs.indexOfFirst { it.id == hitKey }; if (newTarget != -1 && newTarget != targetIndex) targetIndex = newTarget } } } },
                    onReorderEnd = { if (draggedItemIndex != null && targetIndex != null) { isManualOrder = true; val mutable = songs.toMutableList(); val song = mutable.removeAt(draggedItemIndex!!); mutable.add(targetIndex!!, song); viewModel.updateSongs(mutable.mapIndexed { index, item -> item.copy(position = index) }); scope.launch { delay(800); isManualOrder = false } }; draggedItemIndex = null; activeDraggedItem = null; targetIndex = null }
                ), contentPadding = PaddingValues(bottom = 80.dp)) {
                    if (!isSearching) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                                Text("Collections", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White))
                                Spacer(modifier = Modifier.height(16.dp))

                                var draggedPlaylistIndex by remember { mutableStateOf<Int?>(null) }
                                var currentDragX by remember { mutableFloatStateOf(0f) }
                                var playlistTouchOffset by remember { mutableFloatStateOf(0f) }
                                var targetPlaylistIndex by remember { mutableStateOf<Int?>(null) }
                                val playlistListState = rememberLazyListState()
                                var localPlaylists by remember { mutableStateOf(emptyList<Playlist>()) }
                                var isPlaylistManualOrder by remember { mutableStateOf(false) }

                                LaunchedEffect(playlists, draggedPlaylistIndex, isPlaylistManualOrder) {
                                    if (draggedPlaylistIndex == null && !isPlaylistManualOrder) localPlaylists = playlists
                                }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    LazyRow(
                                        state = playlistListState,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        contentPadding = PaddingValues(end = 24.dp),
                                        modifier = Modifier.fillMaxWidth().horizontalDragGestureHandler(
                                            listState = playlistListState,
                                            isReorderMode = isArrangeModeEnabled,
                                            onReorderStart = { key, fingerX, itemLeft ->
                                                if (key is Int) {
                                                    val index = localPlaylists.indexOfFirst { it.id == key }
                                                    if (index != -1) {
                                                        draggedPlaylistIndex = index
                                                        targetPlaylistIndex = index
                                                        currentDragX = fingerX
                                                        playlistTouchOffset = fingerX - itemLeft
                                                    }
                                                }
                                            },
                                            onReorderUpdate = { x ->
                                                currentDragX = x
                                                if (draggedPlaylistIndex != null) {
                                                    val info = playlistListState.layoutInfo
                                                    val itemUnderFinger = info.visibleItemsInfo.find { x.toInt() in it.offset..(it.offset + it.size) }
                                                    itemUnderFinger?.let { hitItem ->
                                                        val newTarget = localPlaylists.indexOfFirst { it.id == hitItem.key }
                                                        if (newTarget != -1 && newTarget != targetPlaylistIndex) targetPlaylistIndex = newTarget
                                                    }
                                                }
                                            },
                                            onReorderEnd = {
                                                if (draggedPlaylistIndex != null && targetPlaylistIndex != null) {
                                                    isPlaylistManualOrder = true
                                                    val mutable = localPlaylists.toMutableList()
                                                    val item = mutable.removeAt(draggedPlaylistIndex!!)
                                                    mutable.add(targetPlaylistIndex!!, item)
                                                    localPlaylists = mutable
                                                    viewModel.updatePlaylistPositions(localPlaylists.mapIndexed { index, p -> p.copy(position = index) })
                                                    scope.launch { delay(800); isPlaylistManualOrder = false }
                                                }
                                                draggedPlaylistIndex = null
                                                targetPlaylistIndex = null
                                            }
                                        )
                                    ) {
                                        itemsIndexed(localPlaylists, key = { _, it -> it.id }) { index, playlist ->
                                            val isDragging = draggedPlaylistIndex == index
                                            val itemWidthPx = with(LocalDensity.current) { 120.dp.toPx() + 16.dp.toPx() }
                                            val targetDisplacement = when {
                                                isDragging -> 0f
                                                draggedPlaylistIndex == null || targetPlaylistIndex == null -> 0f
                                                draggedPlaylistIndex!! < targetPlaylistIndex!! && index > draggedPlaylistIndex!! && index <= targetPlaylistIndex!! -> -itemWidthPx
                                                draggedPlaylistIndex!! > targetPlaylistIndex!! && index < draggedPlaylistIndex!! && index >= targetPlaylistIndex!! -> itemWidthPx
                                                else -> 0f
                                            }
                                            val itemTranslationX by animateFloatAsState(targetValue = targetDisplacement, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "PlaylistDrag")
                                            val isGhostSlot = !isDragging && targetPlaylistIndex == index

                                            Box(modifier = Modifier.animateItem().zIndex(if (isGhostSlot) 1f else 0f).graphicsLayer { translationX = itemTranslationX }) {
                                                if (isGhostSlot) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(120.dp)
                                                            .graphicsLayer { translationX = -itemTranslationX }
                                                            .border(width = 2.dp, brush = Brush.linearGradient(colors = listOf(Color(0xFFFF4081).copy(alpha = 0.5f), Color(0xFFFF4081).copy(alpha = 0.2f))), shape = RoundedCornerShape(24.dp))
                                                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text("DROP\nHERE", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF4081).copy(alpha = 0.6f)))
                                                    }
                                                }
                                                Box(modifier = Modifier.graphicsLayer { alpha = if (isDragging) 0f else 1f }) {
                                                    PlaylistCard(playlist, viewModel) { onPlaylistClick(playlist) }
                                                }
                                            }
                                        }
                                        item { FavoritesCollectionCard(favoriteSongs.size) { onFavoritesClick() } }
                                    }

                                    // Dragged Overlay
                                    draggedPlaylistIndex?.let { index ->
                                        localPlaylists.getOrNull(index)?.let { draggedItem ->
                                            Box(modifier = Modifier.offset { IntOffset((currentDragX - playlistTouchOffset).roundToInt(), 0) }.zIndex(100f)) {
                                                val itemScale by animateFloatAsState(targetValue = 1.1f, label = "FloatingScale")
                                                Box(modifier = Modifier.graphicsLayer { scaleX = itemScale; scaleY = itemScale; shadowElevation = 32.dp.toPx() }) {
                                                    PlaylistCard(draggedItem, viewModel, {})
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (isSearching && searchQuery.isNotBlank() && songs.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 60.dp).padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (isOnlineSearching) {
                                    CircularProgressIndicator(color = Color(0xFF00E676))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Searching YouTube (<10 mins)...", color = Color.White.copy(alpha = 0.72f))
                                } else if (onlineSearchResults.isEmpty()) {
                                    Text("No local tracks found.", color = Color.White.copy(alpha = 0.72f))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { viewModel.searchOnline(searchQuery, isLibrary = true) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                                    ) {
                                        Text("Search & Download Offline (<10m)", fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                            }
                        }
                        if (onlineSearchResults.isNotEmpty()) {
                            item {
                                Text(
                                    "Online Download Results (<10 mins)",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                                )
                            }
                            items(onlineSearchResults, key = { it.youtubeUrl }) { item ->
                                OnlineSearchResultCard(
                                    item = item,
                                    onAddClick = { viewModel.ingestOnlineItem(item, isLibrary = true) }
                                )
                            }
                        }
                    } else {
                            item { Text("Library Tracks", modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)) }
                            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                                val isDragging = draggedItemIndex == index
                                val itemHeightPx = measuredItemHeightPx
                                val targetDisplacement = when { isDragging -> 0f; draggedItemIndex == null || targetIndex == null || itemHeightPx == 0f -> 0f; draggedItemIndex!! < targetIndex!! && index > draggedItemIndex!! && index <= targetIndex!! -> -itemHeightPx; draggedItemIndex!! > targetIndex!! && index < draggedItemIndex!! && index >= targetIndex!! -> itemHeightPx; else -> 0f }
                                val itemTranslationY by animateFloatAsState(targetValue = targetDisplacement, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "DragTranslation")
                                val isGhostSlot = !isDragging && targetIndex == index
                                Box(modifier = Modifier.fillMaxWidth().animateItem().zIndex(if (isGhostSlot) 1f else 0f).onGloballyPositioned { if (measuredItemHeightPx == 0f) measuredItemHeightPx = it.size.height.toFloat() }.graphicsLayer { translationY = itemTranslationY }) {
                                    if (isGhostSlot) { Box(modifier = Modifier.fillMaxWidth().height(with(LocalDensity.current) { measuredItemHeightPx.toDp() }).graphicsLayer { translationY = -itemTranslationY }.padding(horizontal = 24.dp, vertical = 8.dp).border(width = 2.dp, brush = Brush.linearGradient(colors = listOf(Color(0xFFFF4081).copy(alpha = 0.5f), Color(0xFFFF4081).copy(alpha = 0.2f))), shape = RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text("DROP SONG HERE", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF4081).copy(alpha = 0.6f), letterSpacing = 2.sp)) } }
                                    Box(modifier = Modifier.graphicsLayer { alpha = if (isDragging) 0f else 1f }) { SongListItem(song = song, onPlayClick = { if (isSelectionMode) viewModel.toggleSongSelection(song.id) else { viewModel.playSong(song, songs); onSongClick() } }, onFavoriteToggle = { viewModel.updateFavorite(song, !song.isFavorite) }, onDelete = { viewModel.deleteSong(song.id) }, isSelected = selectedSongIds.contains(song.id), onLongClick = { viewModel.toggleSelectionMode(true); viewModel.toggleSongSelection(song.id) }, onOptionsClick = { viewModel.openSongOptions(song) }, selectionMode = isSelectionMode, isPlaying = currentSong?.id == song.id, isArrangeMode = isArrangeModeEnabled, isDragging = false) }
                                }
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
        if (showAddMenu) Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showAddMenu = false })
        AnimatedVisibility(visible = showAddMenu, enter = fadeIn() + scaleIn(initialScale = 0.4f, transformOrigin = TransformOrigin(0.9f, 0.1f)), exit = fadeOut() + scaleOut(targetScale = 0.4f, transformOrigin = TransformOrigin(0.9f, 0.1f)), modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 16.dp).zIndex(10f)) {
            Box(modifier = Modifier.width(260.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.4f)).border(1.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(28.dp))) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { AddMenuOption(text = "Create Playlist", icon = Icons.AutoMirrored.Filled.PlaylistAdd, onClick = { showAddMenu = false; showPlaylistDialog = true }); AddMenuOption(text = "Add Songs", icon = Icons.Default.LibraryMusic, onClick = { showAddMenu = false; launcher.launch(arrayOf("audio/*")) }); AddMenuOption(text = "Download from YT & Spotify", icon = Icons.Default.CloudDownload, onClick = { showAddMenu = false; showDownloadDialog = true }) } }
        }
        if (showPlaylistDialog) {
            AlertDialog(
                onDismissRequest = { showPlaylistDialog = false },
                title = { Text("New Playlist", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White)) },
                text = {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        placeholder = { Text("Playlist Name", color = Color.White.copy(alpha = 0.4f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Black.copy(alpha = 0.40f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.25f),
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newPlaylistName.isNotBlank()) {
                                viewModel.createPlaylist(newPlaylistName)
                                newPlaylistName = ""
                                showPlaylistDialog = false
                            }
                        },
                        enabled = newPlaylistName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Create", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showPlaylistDialog = false }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                shape = RoundedCornerShape(32.dp),
                containerColor = Color(0xFF141A16).copy(alpha = 0.95f),
                modifier = Modifier.border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = RoundedCornerShape(32.dp)
                )
            )
        }
        if (showDownloadDialog) {
            AlertDialog(
                onDismissRequest = { if (downloadState !is DownloadState.Downloading) showDownloadDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF00E676))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Import from Link", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White))
                    }
                },
                text = {
                    Column {
                        if (isExtracting) {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xFF00E676))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Extracting...", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                        } else {
                            Text("Enter a YouTube or Spotify URL to extract and download audio.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = youtubeUrl,
                                onValueChange = { youtubeUrl = it },
                                placeholder = { Text("https://youtube.com/...", color = Color.White.copy(alpha = 0.4f)) },
                                singleLine = true,
                                isError = !isUrlValid && youtubeUrl.trim().isNotBlank(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Black.copy(alpha = 0.40f),
                                    unfocusedContainerColor = Color.Black.copy(alpha = 0.25f),
                                    focusedBorderColor = Color(0xFF00E676),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (!isUrlValid && youtubeUrl.trim().isNotBlank()) {
                                Text("Invalid YouTube URL", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.Start).padding(top = 4.dp, start = 8.dp))
                            }
                            if (downloadState is DownloadState.Error) {
                                Text((downloadState as DownloadState.Error).message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    if (downloadState is DownloadState.Downloading) {
                        TextButton(onClick = { viewModel.cancelDownload() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("Cancel Download")
                        }
                    } else {
                        Button(
                            onClick = {
                                val urlToDownload = youtubeUrl.trim()
                                if (urlToDownload.isNotBlank() && isUrlValid && isEngineReady) {
                                    youtubeUrl = ""
                                    viewModel.fetchDownloadMetadata(urlToDownload)
                                    showDownloadDialog = false
                                }
                            },
                            enabled = youtubeUrl.trim().isNotBlank() && isUrlValid && isEngineReady,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E676),
                                contentColor = Color.Black,
                                disabledContainerColor = Color(0xFF00E676).copy(alpha = 0.3f),
                                disabledContentColor = Color.White.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(if (isEngineReady) "Download" else "Initializing...", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    if (downloadState !is DownloadState.Downloading) {
                        TextButton(onClick = { showDownloadDialog = false; viewModel.resetDownloadState() }) {
                            Text("Close", color = Color.White.copy(alpha = 0.60f), fontWeight = FontWeight.Medium)
                        }
                    }
                },
                shape = RoundedCornerShape(32.dp),
                containerColor = Color(0xFF141A16).copy(alpha = 0.95f),
                modifier = Modifier.border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = RoundedCornerShape(32.dp)
                )
            )
        }
        if (pendingDownloadItems.isNotEmpty()) {
            val playlistItem = pendingDownloadItems.find { it.isPlaylist }
            val firstTrack = pendingDownloadItems.find { !it.isPlaylist }
            val trackCount = pendingDownloadItems.count { !it.isPlaylist }
            val trackCountText = if (trackCount > 0) " ($trackCount tracks)" else ""

            Dialog(
                onDismissRequest = { viewModel.clearPendingDownloadItems() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                var isVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { isVisible = true }

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400)) + scaleIn(initialScale = 0.8f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                    exit = fadeOut(tween(300)) + scaleOut(targetScale = 0.8f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                isVisible = false
                                scope.launch { delay(300); viewModel.clearPendingDownloadItems() }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .border(
                                    width = 1.dp,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.25f),
                                            Color.White.copy(alpha = 0.05f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(32.dp)
                                )
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { /* Prevent dismiss */ },
                            shape = RoundedCornerShape(32.dp),
                            color = Color(0xFF141A16).copy(alpha = 0.95f)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(modifier = Modifier.height(180.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    firstTrack?.thumbnailUrl?.let { url ->
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            modifier = Modifier.size(130.dp).rotate(-10f).offset(x = (-30).dp).clip(RoundedCornerShape(24.dp)).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).shadow(8.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    (playlistItem?.thumbnailUrl ?: firstTrack?.thumbnailUrl)?.let { url ->
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            modifier = Modifier.size(140.dp).rotate(5f).offset(x = 20.dp).clip(RoundedCornerShape(24.dp)).border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp)).shadow(16.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Download Playlist", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = Color.White))
                                    playlistItem?.let {
                                        Text(it.title, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                    }
                                }

                                Text(
                                    text = "How would you like to save these songs$trackCountText in your Library?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.85f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { viewModel.startBatchDownload(asPlaylist = true) },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Folder, contentDescription = null, tint = Color.Black)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text("Save as Collection", fontWeight = FontWeight.Bold, color = Color.Black)
                                        }
                                    }

                                    Surface(
                                        onClick = { viewModel.startBatchDownload(asPlaylist = false) },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        color = Color.White.copy(alpha = 0.10f),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                                    ) {
                                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text("Individual Tracks", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color.White))
                                        }
                                    }

                                    TextButton(
                                        onClick = { isVisible = false; scope.launch { delay(300); viewModel.clearPendingDownloadItems() } },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
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

@Composable
fun AddMenuOption(text: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable { onClick() }.clip(RoundedCornerShape(18.dp)), color = Color.White.copy(alpha = 0.2f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.05f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = Color(0xFF424242), modifier = Modifier.size(20.dp)) }
            Spacer(modifier = Modifier.width(16.dp)); Text(text = text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF333333)))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(playlist: Playlist, viewModel: SongViewModel, onClick: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val playlistSongs by viewModel.getSongsInPlaylist(playlist.id).collectAsState(initial = emptyList())
    val coverImage = remember(playlistSongs) { playlistSongs.firstOrNull()?.imageUrl }
    Column(modifier = Modifier.width(120.dp).combinedClickable(onClick = onClick, onLongClick = { showDeleteDialog = true }), horizontalAlignment = Alignment.Start) {
        Box(modifier = Modifier.size(120.dp).shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.2f)).border(1.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            if (coverImage != null) AsyncImage(model = coverImage, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Playlist",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (showDeleteDialog) { AlertDialog(onDismissRequest = { showDeleteDialog = false }, title = { Text("Delete Playlist") }, text = { Text("Are you sure you want to delete '${playlist.name}'?") }, confirmButton = { TextButton(onClick = { viewModel.deletePlaylist(playlist.id); showDeleteDialog = false }) { Text("Delete", color = Color.Red) } }, dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }) }
}

@Composable
fun FavoritesCollectionCard(count: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.width(120.dp).clickable { onClick() }, horizontalAlignment = Alignment.Start) {
        Box(modifier = Modifier.size(120.dp).shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(brush = Brush.linearGradient(colors = listOf(Color(0xFFFF4081).copy(alpha = 0.8f), Color(0xFFE040FB).copy(alpha = 0.8f)))).border(1.5.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(24.dp))) { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.align(Alignment.Center).size(48.dp), tint = Color.White) }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "$count songs",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
