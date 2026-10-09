package com.example.song.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import com.example.song.ui.components.ImmersivePlaylistHeader
import com.example.song.ui.components.OnlineSearchResultCard
import com.example.song.viewmodel.ItemActionState
import androidx.compose.animation.core.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.example.song.SongApplication
import com.example.song.data.model.StreamingItem
import com.example.song.util.dragGestureHandler
import com.example.song.util.horizontalDragGestureHandler
import com.example.song.viewmodel.SongViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.song.data.model.Song

import com.example.song.ui.spotlight.SpotlightController
import com.example.song.ui.spotlight.TourStep
import com.example.song.ui.spotlight.spotlightTarget

import com.example.song.viewmodel.DownloadState
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DiscoverScreen(
    viewModel: SongViewModel,
    onSongClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    spotlightController: SpotlightController? = null
) {
    val items by viewModel.topLevelStreamingItems.collectAsState()
    val isExtracting by viewModel.isExtracting.collectAsState()
    val extractionStatus by viewModel.extractionStatus.collectAsState()
    val extractionProgress by viewModel.extractionProgress.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val isEngineReady by SongApplication.getInstance().isReady.collectAsState()
    val resolvingUrlId by viewModel.resolvingUrlId.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPlayingSong by viewModel.currentPlayingSong.collectAsState()
    val pendingItems by viewModel.pendingStreamingItems.collectAsState()
    val duplicatePlaylistState by viewModel.duplicatePlaylistState.collectAsState()
    val extractionError by viewModel.extractionError.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddMenu by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var youtubeUrl by remember { mutableStateOf("") }
    var selectedPlaylist by rememberSaveable { mutableStateOf<StreamingItem?>(null) }
    BackHandler(enabled = selectedPlaylist != null) {
        selectedPlaylist = null
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            viewModel.clearOnlineSearchResults()
        }
    }

    val onlineSearchResults by viewModel.onlineSearchResults.collectAsState()
    val isOnlineSearching by viewModel.isOnlineSearching.collectAsState()
    val itemActionStates by viewModel.itemActionStates.collectAsState()

    var showAddSongDialog by remember { mutableStateOf(false) }
    val allStreamingSongs by viewModel.allStreamingSongs.collectAsState()
    val isArrangeModeEnabled by viewModel.isArrangeModeEnabled.collectAsState()

    val filteredItems = remember(items, searchQuery) { if (searchQuery.isBlank()) items else items.filter { it.title.contains(searchQuery, ignoreCase = true) } }
    val playlists = remember(filteredItems) { filteredItems.filter { it.isPlaylist } }
    val singleSongs = remember(filteredItems) { filteredItems.filter { !it.isPlaylist } }
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedStreamingIds by viewModel.selectedStreamingIds.collectAsState()
    val isUrlValid = remember(youtubeUrl) { youtubeUrl.isBlank() || (youtubeUrl.startsWith("http") && (youtubeUrl.contains("youtube.com") || youtubeUrl.contains("youtu.be") || youtubeUrl.contains("spotify.com"))) }
    val addIconRotation by animateFloatAsState(targetValue = if (showAddMenu) 135f else 0f, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "AddIconRotation")
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var activeDraggedItem by remember { mutableStateOf<StreamingItem?>(null) }
    var currentDragY by remember { mutableFloatStateOf(0f) }
    var itemTouchOffset by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    var measuredItemHeightPx by remember { mutableFloatStateOf(0f) }
    var isManualOrder by remember { mutableStateOf(false) }

    LaunchedEffect(isArrangeModeEnabled) { if (!isArrangeModeEnabled) { draggedItemIndex = null; activeDraggedItem = null; targetIndex = null } }

    LaunchedEffect(listState) {
        snapshotFlow { 
            (listState.firstVisibleItemIndex * 200f) + listState.firstVisibleItemScrollOffset 
        }
        .distinctUntilChanged { old, new -> abs(old - new) < 15f }
        .collect { offset ->
            viewModel.updateGlobalScrollOffset(offset)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (selectedPlaylist != null) {
                    CenterAlignedTopAppBar(colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                        title = { Text(if (isArrangeModeEnabled) "Arrange Songs" else selectedPlaylist?.title ?: "Playlist", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { if (!isArrangeModeEnabled) { IconButton(onClick = { selectedPlaylist = null }, modifier = Modifier.padding(8.dp).background(Color.White.copy(alpha = 0.3f), CircleShape)) { Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF424242)) } } },
                        actions = { if (isArrangeModeEnabled) { TextButton(onClick = { viewModel.toggleArrangeMode(false) }, modifier = Modifier.padding(end = 8.dp)) { Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFFE91E63)) } } }
                    )
                } else {
                    CenterAlignedTopAppBar(colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                        title = { 
                            if (isSearching) { 
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    placeholder = { Text("Search streaming...") },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.White.copy(alpha = 0.2f), unfocusedContainerColor = Color.White.copy(alpha = 0.1f), focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                                    shape = RoundedCornerShape(24.dp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { if (searchQuery.isNotBlank()) viewModel.searchOnline(searchQuery, isLibrary = false) }),
                                    trailingIcon = { IconButton(onClick = { isSearching = false; searchQuery = ""; viewModel.clearOnlineSearchResults() }) { Icon(Icons.Default.Close, contentDescription = "Close search") } }
                                ) 
                            } else { 
                                Text(
                                    if (isArrangeModeEnabled) "Arrange Songs" else "Discover", 
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold, 
                                        color = Color.White
                                    )
                                ) 
                            } 
                        },
                        navigationIcon = { if (!isSearching && !isArrangeModeEnabled) { IconButton(onClick = { isSearching = true }, modifier = Modifier.background(Color.White.copy(alpha = 0.3f), CircleShape).spotlightTarget(TourStep.STEP_1_SEARCH, spotlightController)) { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF424242)) } } },
                        actions = { 
                            if (!isSearching) { 
                                if (isArrangeModeEnabled) {
                                    TextButton(onClick = { viewModel.toggleArrangeMode(false) }, modifier = Modifier.padding(end = 8.dp)) {
                                        Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = onSettingsClick,
                                            modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.3f), CircleShape).spotlightTarget(TourStep.STEP_3_OTA_UPDATES, spotlightController)
                                        ) {
                                            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings & Updates", tint = Color(0xFF424242))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(onClick = { if (isEngineReady) showAddMenu = !showAddMenu }, modifier = Modifier.size(48.dp).background(if (isEngineReady) Color.White.copy(alpha = 0.3f) else Color.Gray.copy(alpha = 0.2f), CircleShape).spotlightTarget(TourStep.STEP_2_CACHE_ENGINE, spotlightController), enabled = isEngineReady) { Icon(imageVector = Icons.Default.Add, contentDescription = "Add Options", tint = Color(0xFF424242), modifier = Modifier.size(28.dp).rotate(addIconRotation)) } 
                                    }
                                }
                            } 
                        }
                    )
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Dynamic Real-time Extraction & Download Progress Card
                AnimatedVisibility(
                    visible = isExtracting || downloadState !is DownloadState.Idle,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF121216).copy(alpha = 0.88f),
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (isExtracting) {
                                // Extraction Phase (Smoothly animated percentage progress + live status)
                                val targetProgress = extractionProgress ?: 0.15f
                                val animatedProgress by animateFloatAsState(
                                    targetValue = targetProgress,
                                    animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                                    label = "extraction_progress"
                                )
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = Color(0xFF00E676),
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val percentInt = (animatedProgress * 100).toInt().coerceIn(0, 100)
                                    Text(
                                        text = "$percentInt% - ${extractionStatus ?: "Extracting metadata..."}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            } else {
                                // Download Phase (Real-time % progress & track counter)
                                AnimatedContent(
                                    targetState = downloadState,
                                    transitionSpec = { fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200)) },
                                    label = "DiscoverDownloadContent"
                                ) { state ->
                                    Column {
                                        when (state) {
                                            is DownloadState.Checking -> {
                                                LinearProgressIndicator(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = Color(0xFFE91E63),
                                                    trackColor = Color.White.copy(alpha = 0.1f)
                                                )
                                                Text(
                                                    text = "Preparing media engine...",
                                                    modifier = Modifier.padding(top = 8.dp),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = Color.White
                                                )
                                            }
                                            is DownloadState.Downloading -> {
                                                val progressFraction = state.progress / 100f
                                                LinearProgressIndicator(
                                                    progress = { progressFraction },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = Color(0xFF00E676),
                                                    trackColor = Color.White.copy(alpha = 0.1f)
                                                )
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val progressText = if (state.total > 1) {
                                                        "Batch Download: ${state.current} of ${state.total} (${state.progress.toInt()}%)"
                                                    } else {
                                                        "Downloading audio... ${state.progress.toInt()}%"
                                                    }
                                                    Text(
                                                        text = progressText,
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = Color.White
                                                    )
                                                    IconButton(
                                                        onClick = { viewModel.cancelDownload() },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Close,
                                                            contentDescription = "Cancel Download",
                                                            tint = Color.White.copy(alpha = 0.7f),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            is DownloadState.Success -> {
                                                LinearProgressIndicator(
                                                    progress = { 1f },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = Color(0xFF00E676),
                                                    trackColor = Color.White.copy(alpha = 0.1f)
                                                )
                                                Text(
                                                    text = "Download Complete!",
                                                    modifier = Modifier.padding(top = 8.dp),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = Color(0xFF00E676)
                                                )
                                            }
                                            is DownloadState.Error -> {
                                                LinearProgressIndicator(
                                                    progress = { 0f },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = Color(0xFFF44336),
                                                    trackColor = Color.White.copy(alpha = 0.1f)
                                                )
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = state.message,
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = Color(0xFFF44336),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    IconButton(
                                                        onClick = { viewModel.resetDownloadState() },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Close,
                                                            contentDescription = "Dismiss",
                                                            tint = Color.White.copy(alpha = 0.7f),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            is DownloadState.Idle -> {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (selectedPlaylist == null) {
                    var localSingleSongs by remember { mutableStateOf(emptyList<StreamingItem>()) }
                    LaunchedEffect(singleSongs, draggedItemIndex, isManualOrder) { if (draggedItemIndex == null && !isManualOrder) localSingleSongs = singleSongs }

                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().dragGestureHandler(listState = listState, isReorderMode = isArrangeModeEnabled,
                        onSelectStart = { key -> if (key is Int) viewModel.startRangeSelection(key, localSingleSongs.map { it.id }, isStreaming = true) },
                        onSelectUpdate = { key -> if (key is Int) viewModel.updateRangeSelection(key, localSingleSongs.map { it.id }, isStreaming = true) },
                        onSelectEnd = { viewModel.endRangeSelection() },
                        onReorderStart = { key, fingerY, itemTop -> if (key is Int) { val index = localSingleSongs.indexOfFirst { it.id == key }; if (index != -1) { draggedItemIndex = index; activeDraggedItem = localSingleSongs[index]; targetIndex = index; currentDragY = fingerY; itemTouchOffset = fingerY - itemTop } } },
                        onReorderUpdate = { y -> currentDragY = y; if (draggedItemIndex != null) { val info = listState.layoutInfo; val itemUnderFinger = info.visibleItemsInfo.find { y.toInt() in it.offset..(it.offset + it.size) }; itemUnderFinger?.let { hitItem -> val newTarget = localSingleSongs.indexOfFirst { it.id == hitItem.key }; if (newTarget != -1 && newTarget != targetIndex) targetIndex = newTarget } } },
                        onReorderEnd = { if (draggedItemIndex != null && targetIndex != null) { isManualOrder = true; val mutable = localSingleSongs.toMutableList(); val item = mutable.removeAt(draggedItemIndex!!); mutable.add(targetIndex!!, item); localSingleSongs = mutable; viewModel.updateStreamingItems(localSingleSongs.mapIndexed { index, si -> si.copy(position = index) }); scope.launch { delay(800); isManualOrder = false } }; draggedItemIndex = null; activeDraggedItem = null; targetIndex = null }
                    ), contentPadding = PaddingValues(bottom = 80.dp)) {
                        if (playlists.isNotEmpty()) {
                            item {
                                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                                    Text(
                                        "Collections",
                                        modifier = Modifier.padding(horizontal = 24.dp),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))

                                    var draggedPlaylistIndex by remember { mutableStateOf<Int?>(null) }
                                    var currentDragX by remember { mutableFloatStateOf(0f) }
                                    var playlistTouchOffset by remember { mutableFloatStateOf(0f) }
                                    var targetPlaylistIndex by remember { mutableStateOf<Int?>(null) }
                                    val playlistListState = rememberLazyListState()
                                    var localPlaylists by remember { mutableStateOf(emptyList<StreamingItem>()) }
                                    var isPlaylistManualOrder by remember { mutableStateOf(false) }

                                    LaunchedEffect(playlists, draggedPlaylistIndex, isPlaylistManualOrder) {
                                        if (draggedPlaylistIndex == null && !isPlaylistManualOrder) localPlaylists = playlists
                                    }

                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        LazyRow(
                                            state = playlistListState,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            contentPadding = PaddingValues(horizontal = 24.dp),
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
                                                        viewModel.updateStreamingItems(localPlaylists.mapIndexed { index, si -> si.copy(position = index) })
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
                                                        StreamingPlaylistCard(item = playlist, isSelected = selectedStreamingIds.contains(playlist.id), selectionMode = isSelectionMode, onClick = { if (isSelectionMode) viewModel.toggleStreamingSelection(playlist.id) else selectedPlaylist = playlist }, onLongClick = { viewModel.toggleSelectionMode(true); viewModel.toggleStreamingSelection(playlist.id) })
                                                    }
                                                }
                                            }
                                        }

                                        // Dragged Item Overlay
                                        draggedPlaylistIndex?.let { index ->
                                            localPlaylists.getOrNull(index)?.let { draggedItem ->
                                                Box(modifier = Modifier.offset { IntOffset((currentDragX - playlistTouchOffset).roundToInt(), 0) }.zIndex(100f)) {
                                                    val itemScale by animateFloatAsState(targetValue = 1.1f, label = "FloatingScale")
                                                    Box(modifier = Modifier.graphicsLayer { scaleX = itemScale; scaleY = itemScale; shadowElevation = 32.dp.toPx() }) {
                                                        StreamingPlaylistCard(item = draggedItem, isSelected = false, selectionMode = false, onClick = {})
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (localSingleSongs.isNotEmpty()) {
                            item {
                                Text(
                                    "Recommended",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )
                            }
                            itemsIndexed(localSingleSongs, key = { _, item -> item.id }) { index, item ->
                                val isCurrentItemPlaying = currentPlayingSong?.id == (1_000_000 + item.id)
                                val isDragging = draggedItemIndex == index
                                val itemHeightPx = measuredItemHeightPx
                                val targetDisplacement = when { isDragging -> 0f; draggedItemIndex == null || targetIndex == null || itemHeightPx == 0f -> 0f; draggedItemIndex!! < targetIndex!! && index > draggedItemIndex!! && index <= targetIndex!! -> -itemHeightPx; draggedItemIndex!! > targetIndex!! && index < draggedItemIndex!! && index >= targetIndex!! -> itemHeightPx; else -> 0f }
                                val itemTranslationY by animateFloatAsState(targetValue = targetDisplacement, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "DragTranslation")
                                val isGhostSlot = !isDragging && targetIndex == index
                                Box(modifier = Modifier.fillMaxWidth().animateItem().zIndex(if (isGhostSlot) 1f else 0f).onGloballyPositioned { if (isArrangeModeEnabled && measuredItemHeightPx == 0f) measuredItemHeightPx = it.size.height.toFloat() }.graphicsLayer { translationY = itemTranslationY }) {
                                    if (isGhostSlot) { Box(modifier = Modifier.fillMaxWidth().height(with(LocalDensity.current) { measuredItemHeightPx.toDp() }).graphicsLayer { translationY = -itemTranslationY }.padding(horizontal = 24.dp, vertical = 8.dp).border(width = 2.dp, brush = Brush.linearGradient(colors = listOf(Color(0xFFFF4081).copy(alpha = 0.5f), Color(0xFFFF4081).copy(alpha = 0.2f))), shape = RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text("DROP SONG HERE", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF4081).copy(alpha = 0.6f), letterSpacing = 2.sp)) } }
                                    Box(modifier = Modifier.graphicsLayer { alpha = if (isDragging) 0f else 1f }) { StreamingItemCard(item = item, enabled = isEngineReady, isResolving = resolvingUrlId == item.id, isPlaying = isPlaying && isCurrentItemPlaying, isArrangeMode = isArrangeModeEnabled, isDragging = false, onClick = { if (isSelectionMode) viewModel.toggleStreamingSelection(item.id) else if (isEngineReady) { if (isCurrentItemPlaying) viewModel.togglePlayPause() else { viewModel.playStreamingItem(item, localSingleSongs); onSongClick() } } }, onFavoriteToggle = { viewModel.toggleStreamingFavorite(item) }, onDelete = { viewModel.deleteStreamingItem(item) }, isSelected = selectedStreamingIds.contains(item.id), onLongClick = { viewModel.toggleSelectionMode(true); viewModel.toggleStreamingSelection(item.id) }, onOptionsClick = { viewModel.openSongOptions(
                                        Song(id = 1_000_000 + item.id, title = item.title, artist = item.artist ?: "Unknown Artist", audioUri = item.youtubeUrl, imageUrl = item.thumbnailUrl, isFavorite = item.isFavorite, duration = item.duration)
                                    ) }, selectionMode = isSelectionMode) }
                                }
                            }
                        } else if (playlists.isEmpty() && singleSongs.isEmpty() && !isExtracting) {
                            if (isSearching && searchQuery.isNotBlank()) {
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp).padding(horizontal = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (isOnlineSearching) {
                                            CircularProgressIndicator(color = Color(0xFF4CAF50))
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text("Searching YouTube...", color = Color.White.copy(alpha = 0.72f))
                                        } else if (onlineSearchResults.isEmpty()) {
                                            Text("No local results found.", color = Color.White.copy(alpha = 0.72f))
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Button(
                                                onClick = { viewModel.searchOnline(searchQuery, isLibrary = false) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                                            ) {
                                                Text("Search Online on YouTube", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                if (onlineSearchResults.isNotEmpty()) {
                                    item {
                                        Text(
                                            "Online Search Results",
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                                        )
                                    }
                                    items(onlineSearchResults, key = { it.youtubeUrl }) { item ->
                                        OnlineSearchResultCard(
                                            item = item,
                                            onAddClick = { viewModel.ingestOnlineItem(item, isLibrary = false) }
                                        )
                                    }
                                }
                            } else {
                                item { Box(modifier = Modifier.fillMaxWidth().padding(top = 100.dp), contentAlignment = Alignment.Center) { Text("No items added yet", color = Color.White.copy(alpha = 0.5f)) } }
                            }
                        }
                    }
                } else {
                    val playlistItems by viewModel.getItemsForStreamingPlaylist(selectedPlaylist!!.youtubeUrl).collectAsState(initial = emptyList())
                    var localPlaylistItems by remember { mutableStateOf(emptyList<StreamingItem>()) }
                    LaunchedEffect(playlistItems, draggedItemIndex, isManualOrder) { if (draggedItemIndex == null && !isManualOrder) localPlaylistItems = playlistItems.filter { !it.isPlaylist } }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().dragGestureHandler(listState = listState, isReorderMode = isArrangeModeEnabled,
                        onSelectStart = { key -> if (key is Int) viewModel.startRangeSelection(key, localPlaylistItems.map { it.id }, isStreaming = true) },
                        onSelectUpdate = { key -> if (key is Int) viewModel.updateRangeSelection(key, localPlaylistItems.map { it.id }, isStreaming = true) },
                        onSelectEnd = { viewModel.endRangeSelection() },
                        onReorderStart = { key, fingerY, itemTop -> if (key is Int) { val index = localPlaylistItems.indexOfFirst { it.id == key }; if (index != -1) { draggedItemIndex = index; activeDraggedItem = localPlaylistItems[index]; targetIndex = index; currentDragY = fingerY; itemTouchOffset = fingerY - itemTop } } },
                        onReorderUpdate = { y -> currentDragY = y; if (draggedItemIndex != null) { val info = listState.layoutInfo; val itemUnderFinger = info.visibleItemsInfo.find { y.toInt() in it.offset..(it.offset + it.size) }; itemUnderFinger?.let { hitItem -> val newTarget = localPlaylistItems.indexOfFirst { it.id == hitItem.key }; if (newTarget != -1 && newTarget != targetIndex) targetIndex = newTarget } } },
                        onReorderEnd = { if (draggedItemIndex != null && targetIndex != null) { isManualOrder = true; val mutable = localPlaylistItems.toMutableList(); val item = mutable.removeAt(draggedItemIndex!!); mutable.add(targetIndex!!, item); localPlaylistItems = mutable; viewModel.updateStreamingItems(localPlaylistItems.mapIndexed { index, si -> si.copy(position = index) }); scope.launch { delay(800); isManualOrder = false } }; draggedItemIndex = null; activeDraggedItem = null; targetIndex = null }
                    ), contentPadding = PaddingValues(bottom = 80.dp)) {
                        item {
                            val playlistCover = selectedPlaylist?.thumbnailUrl
                                ?: localPlaylistItems.firstOrNull { !it.thumbnailUrl.isNullOrEmpty() }?.thumbnailUrl
                            val totalDurationMs = remember(localPlaylistItems) { localPlaylistItems.sumOf { it.duration } }
                            
                            ImmersivePlaylistHeader(
                                title = if (isArrangeModeEnabled) "Arrange Songs" else (selectedPlaylist?.title ?: "Playlist"),
                                subtitle = selectedPlaylist?.artist,
                                coverUrl = playlistCover,
                                songCount = localPlaylistItems.size,
                                totalDurationMs = totalDurationMs,
                                onPlayAllClick = {
                                    if (localPlaylistItems.isNotEmpty()) {
                                        viewModel.playStreamingItem(
                                            item = localPlaylistItems.first(),
                                            queue = localPlaylistItems,
                                            contextTitle = selectedPlaylist?.title ?: "Playlist"
                                        )
                                        onSongClick()
                                    }
                                },
                                onShuffleClick = {
                                    if (localPlaylistItems.isNotEmpty()) {
                                        val shuffled = localPlaylistItems.shuffled()
                                        viewModel.playStreamingItem(
                                            item = shuffled.first(),
                                            queue = shuffled,
                                            contextTitle = "Shuffled - ${selectedPlaylist?.title ?: "Playlist"}"
                                        )
                                        onSongClick()
                                    }
                                },
                                onAddClick = { showAddSongDialog = true }
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Playlist Content",
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
                                        text = "${localPlaylistItems.size}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.9f)
                                        )
                                    )
                                }
                            }
                        }
                        itemsIndexed(localPlaylistItems, key = { _, item -> item.id }) { index, item ->
                            val isCurrentItemPlaying = currentPlayingSong?.id == (1_000_000 + item.id)
                            val isDragging = draggedItemIndex == index
                            val itemHeightPx = measuredItemHeightPx
                            val targetDisplacement = when { isDragging -> 0f; draggedItemIndex == null || targetIndex == null || itemHeightPx == 0f -> 0f; draggedItemIndex!! < targetIndex!! && index > draggedItemIndex!! && index <= targetIndex!! -> -itemHeightPx; draggedItemIndex!! > targetIndex!! && index < draggedItemIndex!! && index >= targetIndex!! -> itemHeightPx; else -> 0f }
                            val itemTranslationY by animateFloatAsState(targetValue = targetDisplacement, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "DragTranslation")
                            val isGhostSlot = !isDragging && targetIndex == index
                            Box(modifier = Modifier.fillMaxWidth().animateItem().zIndex(if (isGhostSlot) 1f else 0f).onGloballyPositioned { if (isArrangeModeEnabled && measuredItemHeightPx == 0f) measuredItemHeightPx = it.size.height.toFloat() }.graphicsLayer { translationY = itemTranslationY }) {
                                if (isGhostSlot) { Box(modifier = Modifier.fillMaxWidth().height(with(LocalDensity.current) { measuredItemHeightPx.toDp() }).graphicsLayer { translationY = -itemTranslationY }.padding(horizontal = 24.dp, vertical = 8.dp).border(width = 2.dp, brush = Brush.linearGradient(colors = listOf(Color(0xFFFF4081).copy(alpha = 0.5f), Color(0xFFFF4081).copy(alpha = 0.2f))), shape = RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text("DROP SONG HERE", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF4081).copy(alpha = 0.6f), letterSpacing = 2.sp)) } }
                                Box(modifier = Modifier.graphicsLayer { alpha = if (isDragging) 0f else 1f }) { StreamingItemCard(item = item, enabled = isEngineReady, isResolving = resolvingUrlId == item.id, isPlaying = isPlaying && isCurrentItemPlaying, isArrangeMode = isArrangeModeEnabled, isDragging = false, onClick = { if (isSelectionMode) viewModel.toggleStreamingSelection(item.id) else if (isEngineReady) { if (isCurrentItemPlaying) viewModel.togglePlayPause() else { viewModel.playStreamingItem(item, localPlaylistItems); onSongClick() } } }, onFavoriteToggle = { viewModel.toggleStreamingFavorite(item) }, onDelete = { viewModel.deleteStreamingItem(item) }, isSelected = selectedStreamingIds.contains(item.id), onLongClick = { viewModel.toggleSelectionMode(true); viewModel.toggleStreamingSelection(item.id) }, onOptionsClick = { viewModel.openSongOptions(
                                    Song(id = 1_000_000 + item.id, title = item.title, artist = item.artist ?: "Unknown Artist", audioUri = item.youtubeUrl, imageUrl = item.thumbnailUrl, isFavorite = item.isFavorite, duration = item.duration)
                                ) }, selectionMode = isSelectionMode) }
                            }
                        }
                    }
                }
            }
        }
        activeDraggedItem?.let { draggedItem ->
            Box(modifier = Modifier.fillMaxWidth().offset { IntOffset(0, (currentDragY - itemTouchOffset).roundToInt()) }.zIndex(100f)) {
                val itemScale by animateFloatAsState(targetValue = 1.1f, label = "FloatingScale", animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                Box(modifier = Modifier.graphicsLayer { scaleX = itemScale; scaleY = itemScale; shadowElevation = 32.dp.toPx(); shape = RoundedCornerShape(20.dp); clip = true }) { StreamingItemCard(item = draggedItem, enabled = true, isResolving = false, isPlaying = false, isArrangeMode = true, isDragging = true, onClick = {}, onFavoriteToggle = {}, onDelete = {}, isSelected = false) }
            }
        }
        if (showAddMenu) Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showAddMenu = false })
        AnimatedVisibility(visible = showAddMenu, enter = fadeIn() + scaleIn(initialScale = 0.4f, transformOrigin = TransformOrigin(0.9f, 0.1f)), exit = fadeOut() + scaleOut(targetScale = 0.4f, transformOrigin = TransformOrigin(0.9f, 0.1f)), modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 16.dp).zIndex(10f)) {
            Box(modifier = Modifier.width(260.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.4f)).border(1.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(28.dp))) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Surface(modifier = Modifier.fillMaxWidth().clickable { showAddMenu = false; showAddDialog = true }.clip(RoundedCornerShape(18.dp)), color = Color.White.copy(alpha = 0.2f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))) { Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.05f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFF424242), modifier = Modifier.size(20.dp)) }; Spacer(modifier = Modifier.width(16.dp)); Text(text = "Add from YouTube & Spotify", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = Color(0xFF424242))) } } } }
        }
        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { if (!isExtracting) showAddDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF00E676))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Stream from Link", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White))
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
                            Text("Enter a YouTube or Spotify URL to add to your Discover list.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = youtubeUrl,
                                onValueChange = { youtubeUrl = it },
                                placeholder = { Text("https://youtube.com/...", color = Color.White.copy(alpha = 0.4f)) },
                                singleLine = true,
                                isError = (!isUrlValid && youtubeUrl.isNotBlank()) || extractionError != null,
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
                            if (!isUrlValid && youtubeUrl.isNotBlank()) {
                                Text("Invalid URL (YouTube or Spotify only)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp, start = 8.dp))
                            }
                            extractionError?.let { error ->
                                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp, start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    if (!isExtracting) {
                        Button(
                            onClick = {
                                if (youtubeUrl.isNotBlank() && isUrlValid && isEngineReady) {
                                    val urlToAdd = youtubeUrl.trim()
                                    youtubeUrl = ""
                                    viewModel.fetchStreamingMetadata(urlToAdd)
                                    showAddDialog = false
                                }
                            },
                            enabled = youtubeUrl.isNotBlank() && isUrlValid && isEngineReady,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E676),
                                contentColor = Color.Black,
                                disabledContainerColor = Color(0xFF00E676).copy(alpha = 0.3f),
                                disabledContentColor = Color.White.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(if (isEngineReady) "Add to Discover" else "Initializing...", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    if (!isExtracting) {
                        TextButton(onClick = { showAddDialog = false }) {
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
        if (pendingItems.isNotEmpty()) {
            val playlistItem = pendingItems.find { it.isPlaylist }
            val firstTrack = pendingItems.find { !it.isPlaylist }
            val trackCount = pendingItems.count { !it.isPlaylist }
            val trackCountText = if (trackCount > 0) " ($trackCount tracks)" else ""

            Dialog(
                onDismissRequest = { viewModel.clearPendingStreamingItems() },
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
                                scope.launch { delay(300); viewModel.clearPendingStreamingItems() }
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
                                    Text("Import Playlist", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = Color.White))
                                    playlistItem?.let {
                                        Text(it.title, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                    }
                                }

                                Text(
                                    text = "How would you like to add this playlist$trackCountText to your Discover screen?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.85f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { viewModel.addPendingStreamingItems(asCollection = true) },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Folder, contentDescription = null, tint = Color.Black)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text("Add as Collection", fontWeight = FontWeight.Bold, color = Color.Black)
                                        }
                                    }

                                    Surface(
                                        onClick = { viewModel.addPendingStreamingItems(asCollection = false) },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        color = Color.White.copy(alpha = 0.10f),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                                    ) {
                                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color.White)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text("Add Individual Items", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color.White))
                                        }
                                    }

                                    TextButton(
                                        onClick = { isVisible = false; scope.launch { delay(300); viewModel.clearPendingStreamingItems() } },
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

        if (duplicatePlaylistState is com.example.song.viewmodel.DuplicatePlaylistState.AlreadyExists) {
            val state = duplicatePlaylistState as com.example.song.viewmodel.DuplicatePlaylistState.AlreadyExists
            Dialog(
                onDismissRequest = { viewModel.clearDuplicatePlaylistState() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF141A16).copy(alpha = 0.98f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(56.dp).background(Color(0xFFFFB74D).copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(32.dp))
                        }

                        Text(
                            text = "Already in Library",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White),
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "\"${state.title}\" is already present in your library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = { viewModel.clearDuplicatePlaylistState() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Got It", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (duplicatePlaylistState is com.example.song.viewmodel.DuplicatePlaylistState.UpdateAvailable) {
            val state = duplicatePlaylistState as com.example.song.viewmodel.DuplicatePlaylistState.UpdateAvailable
            Dialog(
                onDismissRequest = { viewModel.clearDuplicatePlaylistState() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF141A16).copy(alpha = 0.98f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(56.dp).background(Color(0xFF00E676).copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Update, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(32.dp))
                        }

                        Text(
                            text = "Update Playlist?",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White),
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "\"${state.existingPlaylist.title}\" is already in your library (${state.existingCount} tracks).\n\nThe imported link has ${state.newCount} tracks. Would you like to update the existing playlist?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center
                        )

                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.confirmUpdatePlaylist() },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Update Playlist", fontWeight = FontWeight.Bold)
                            }

                            TextButton(
                                onClick = { viewModel.clearDuplicatePlaylistState() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel", color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Medium)
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
                    Text(text = "${selectedStreamingIds.size} Selected", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF333333)), modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.addSelectedToQueue(playNext = false) }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Add Selected to Queue", tint = Color(0xFF4CAF50)) }
                    IconButton(onClick = { val count = selectedStreamingIds.size; viewModel.deleteSelectedItems(); scope.launch { snackbarHostState.showSnackbar("Deleted $count items") } }) { Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = Color.Red) }
                }
            }
        }
        if (showAddSongDialog) {
            val currentPlaylistItems by viewModel.getItemsForStreamingPlaylist(selectedPlaylist?.youtubeUrl ?: "").collectAsState(initial = emptyList())
            val availableTracksToAdd = remember(singleSongs, selectedPlaylist, currentPlaylistItems) {
                if (selectedPlaylist == null) emptyList()
                else {
                    val currentPlaylistUrls = currentPlaylistItems.map { it.youtubeUrl }.toSet()
                    val currentPlaylistIds = currentPlaylistItems.map { it.id }.toSet()
                    singleSongs.filter { candidate ->
                        candidate.id !in currentPlaylistIds &&
                        candidate.youtubeUrl !in currentPlaylistUrls
                    }
                }
            }

            androidx.compose.ui.window.Dialog(onDismissRequest = { showAddSongDialog = false }) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.9f).height(550.dp),
                    shape = RoundedCornerShape(32.dp),
                    color = Color(0xFF121216).copy(alpha = 0.95f),
                    border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.2f)),
                    shadowElevation = 16.dp
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Column { 
                            Text(
                                text = "Add to Collection", 
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = Color.White, fontSize = 26.sp), 
                                modifier = Modifier.padding(bottom = 20.dp)
                            )
                            Surface(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                color = Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                            ) {
                                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                                    if (availableTracksToAdd.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(32.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "No additional recommended tracks available.",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = Color.White.copy(alpha = 0.6f),
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    } else {
                                        items(availableTracksToAdd, key = { it.id }) { item -> 
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { 
                                                        viewModel.addStreamingItemToPlaylist(item.id, selectedPlaylist?.youtubeUrl)
                                                        showAddSongDialog = false 
                                                    }
                                                    .padding(10.dp), 
                                                verticalAlignment = Alignment.CenterVertically
                                            ) { 
                                                AsyncImage(
                                                    model = item.thumbnailUrl, 
                                                    contentDescription = null, 
                                                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.2f)), 
                                                    contentScale = ContentScale.Crop
                                                )
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Column(modifier = Modifier.weight(1f)) { 
                                                    Text(
                                                        text = item.title, 
                                                        style = MaterialTheme.typography.bodyLarge.copy(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold), 
                                                        maxLines = 1, 
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = item.artist ?: "YouTube Stream", 
                                                        style = MaterialTheme.typography.bodySmall.copy(color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp), 
                                                        maxLines = 1, 
                                                        overflow = TextOverflow.Ellipsis
                                                    ) 
                                                } 
                                            } 
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(
                                onClick = { showAddSongDialog = false }, 
                                modifier = Modifier.align(Alignment.End)
                            ) { 
                                Text(
                                    "Close", 
                                    style = MaterialTheme.typography.labelLarge.copy(color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                ) 
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StreamingItemCard(item: StreamingItem, enabled: Boolean = true, isResolving: Boolean = false, isPlaying: Boolean = false, onClick: () -> Unit, onFavoriteToggle: () -> Unit, onDelete: () -> Unit, isSelected: Boolean = false, onLongClick: () -> Unit = {}, onOptionsClick: (() -> Unit)? = null, selectionMode: Boolean = false, isArrangeMode: Boolean = false, isDragging: Boolean = false) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val targetScale = when {
        isDragging -> 1.05f
        isSelected -> 0.95f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (isDragging || isSelected) spring(dampingRatio = Spring.DampingRatioMediumBouncy) else tween(200),
        label = "SelectionScale"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; clip = true; if (isDragging) shadowElevation = 16.dp.toPx() }
            .combinedClickable(enabled = enabled && !isResolving && !isArrangeMode, onClick = onClick, onLongClick = onLongClick)
            .border(
                width = if (isDragging) 3.dp else if (isSelected || isPlaying) 2.dp else 0.dp,
                brush = when {
                    isDragging -> Brush.linearGradient(colors = listOf(Color(0xFF4CAF50), Color(0xFF4CAF50)))
                    isSelected -> Brush.linearGradient(colors = listOf(Color(0xFF4CAF50), Color(0xFF00E676)))
                    isPlaying -> Brush.linearGradient(colors = listOf(Color(0xFF00E676), Color(0xFF1DE9B6)))
                    else -> Brush.linearGradient(colors = listOf(Color.Transparent, Color.Transparent))
                },
                shape = RoundedCornerShape(20.dp)
            ),
        color = when {
            isSelected -> Color.White.copy(alpha = 0.4f)
            isPlaying -> Color.White.copy(alpha = 0.25f)
            else -> Color(0xFF121216).copy(alpha = 0.55f)
        },
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isArrangeMode) Icon(imageVector = Icons.Default.DragIndicator, contentDescription = "Reorder", tint = Color(0xFF424242).copy(alpha = 0.6f), modifier = Modifier.padding(end = 12.dp).size(24.dp))
            Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = 0.2f))) {
                if (item.thumbnailUrl != null) AsyncImage(model = item.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.5f)) }
                if (item.isPlaylist) Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White) }
                androidx.compose.animation.AnimatedVisibility(visible = isSelected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) { Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp).shadow(4.dp, CircleShape)) } }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                val itemSubtitle = when {
                    !item.artist.isNullOrBlank() && item.artist != "Unknown Artist" -> item.artist
                    item.isPlaylist && item.youtubeUrl.contains("spotify.com") -> "Spotify Playlist"
                    item.isPlaylist -> "YouTube Playlist"
                    item.youtubeUrl.contains("spotify.com") -> "Spotify Track"
                    else -> "YouTube Stream"
                }
                Text(text = item.title, style = MaterialTheme.typography.bodyLarge, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = itemSubtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isResolving) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color(0xFF4CAF50))
                else { IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(32.dp)) { Icon(imageVector = if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favorite", tint = if (item.isFavorite) Color.Red else Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) }; Spacer(modifier = Modifier.width(8.dp)); IconButton(onClick = onClick, modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape).size(32.dp), enabled = enabled) { Icon(imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(20.dp)) } }
                if (onOptionsClick != null && !selectionMode && !isArrangeMode) {
                    IconButton(onClick = onOptionsClick) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
    if (showDeleteDialog) AlertDialog(onDismissRequest = { showDeleteDialog = false }, title = { Text("Delete Stream") }, text = { Text("Are you sure you want to remove '${item.title}' from your Discover list?") }, confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("Delete", color = Color.Red) } }, dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StreamingPlaylistCard(item: StreamingItem, isSelected: Boolean = false, selectionMode: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val scale by animateFloatAsState(targetValue = if (isSelected) 0.92f else 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "SelectionScale")
    val playlistSubtitle = when {
        item.youtubeUrl.contains("spotify.com") || item.artist?.equals("Spotify", ignoreCase = true) == true -> "Spotify Playlist"
        !item.artist.isNullOrBlank() && item.artist != "Unknown Artist" && item.artist != "YouTube" -> item.artist
        else -> "YouTube Playlist"
    }
    Column(modifier = Modifier.width(120.dp).graphicsLayer(scaleX = scale, scaleY = scale).combinedClickable(onClick = onClick, onLongClick = onLongClick), horizontalAlignment = Alignment.Start) {
        Box(modifier = Modifier.size(120.dp).shadow(if (isSelected) 4.dp else 12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(brush = Brush.verticalGradient(colors = listOf(Color(0xFF2B2C38), Color(0xFF14151E)))).border(width = if (isSelected) 3.dp else 1.5.dp, brush = if (isSelected) Brush.linearGradient(colors = listOf(Color(0xFF4CAF50), Color(0xFF00E676))) else Brush.linearGradient(colors = listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.3f))), shape = RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            if (!item.thumbnailUrl.isNullOrEmpty()) AsyncImage(model = item.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(56.dp), tint = Color.White.copy(alpha = 0.5f))
            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomEnd) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White) }
            androidx.compose.animation.AnimatedVisibility(visible = isSelected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) { Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp).shadow(8.dp, CircleShape)) } }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = playlistSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
