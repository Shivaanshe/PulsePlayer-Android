package com.example.song

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.ui.Alignment
import com.example.song.ui.components.QueueBottomSheet
import com.example.song.ui.components.SongOptionsMenuSheet
import com.example.song.ui.components.QueueSnackbar
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.song.ui.components.CompactPlayerPane
import com.example.song.ui.components.DebugOverlay
import com.example.song.ui.components.FluidMeshBackground
import com.example.song.ui.components.GlassNavigationBar
import com.example.song.ui.components.GlassNavigationRail
import com.example.song.ui.components.NowPlayingBar
import com.example.song.ui.components.OtaInstallPermissionDialog
import com.example.song.ui.components.OtaSettingsDialog
import com.example.song.ui.components.OtaUpdateAvailableDialog
import com.example.song.ui.components.OtaWhatsNewDialog
import com.example.song.ui.screens.*
import com.example.song.ui.theme.SongTheme
import com.example.song.viewmodel.OtaUpdateViewModel
import com.example.song.viewmodel.SongViewModel
import com.example.song.data.model.Song
import com.example.song.data.preferences.TourPreferences
import com.example.song.ui.spotlight.SpotlightController
import com.example.song.ui.spotlight.SpotlightOverlay
import com.example.song.ui.spotlight.TourStep
import com.example.song.util.CrashTracker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: SongViewModel by viewModels()
    private val otaViewModel: OtaUpdateViewModel by viewModels()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId != -1L) {
                    otaViewModel.onDownloadCompleted(downloadId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)

        // Register DownloadManager broadcast receiver with RECEIVER_EXPORTED for API 34+
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )

        setContent {
            SongTheme {
                var hasPermission by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                        } else {
                            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                        }
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasPermission = isGranted
                }

                LaunchedEffect(hasPermission) {
                    if (!hasPermission) {
                        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_AUDIO
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        permissionLauncher.launch(permission)
                    } else {
                        viewModel.initMediaController(this@MainActivity)
                    }
                }

                if (hasPermission) {
                    MainApp(viewModel, otaViewModel)
                } else {
                    Surface {
                        Text("Please grant storage permission to access songs.")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        otaViewModel.checkPermissionAndResumeInstall()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Receiver might already be unregistered
        }
    }
}

val LocalBottomBarHeight = compositionLocalOf { 0.dp }

@Composable
fun MainApp(viewModel: SongViewModel, otaViewModel: OtaUpdateViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableStateOf(0.dp) }

    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    BackHandler(enabled = currentDestination?.route != "main" && currentDestination?.route != null) {
        navController.popBackStack()
    }

    // Pager State & Coroutine Scope
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // Track active screen and page in Crashlytics
    val currentRoute = currentDestination?.route ?: "main"
    LaunchedEffect(currentRoute, pagerState.currentPage) {
        val pageName = when (pagerState.currentPage) {
            0 -> "Discover"
            1 -> "Favorites"
            2 -> "Library"
            else -> "Page_${pagerState.currentPage}"
        }
        val fullScreenName = if (currentRoute == "main") "Main_$pageName" else currentRoute
        CrashTracker.trackScreen(fullScreenName)
    }

    // Spotlight Walkthrough State & Preferences
    val tourPreferences = remember { TourPreferences(context) }
    val hasCompletedTour by tourPreferences.hasCompletedTour.collectAsState(initial = true)
    val spotlightController = remember { SpotlightController() }
    val activeStep by spotlightController.activeStep.collectAsState()

    LaunchedEffect(hasCompletedTour) {
        if (!hasCompletedTour) {
            spotlightController.startTour()
        }
    }

    LaunchedEffect(spotlightController) {
        spotlightController.onPageChangeRequested = { targetPage ->
            scope.launch {
                if (pagerState.currentPage != targetPage) {
                    pagerState.animateScrollToPage(targetPage)
                }
                snapshotFlow { pagerState.isScrollInProgress }.first { isScrolling -> !isScrolling }
                delay(150)
                spotlightController.setTransitioning(false)
            }
        }
        spotlightController.onTourFinished = {
            scope.launch {
                tourPreferences.setTourCompleted(true)
            }
        }
    }

    val currentSong by viewModel.currentPlayingSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    val isStep6Active = activeStep == TourStep.STEP_6_NOW_PLAYING
    val effectiveSong = currentSong ?: if (isStep6Active) {
        Song(
            id = -1,
            title = "Pulse Music Preview",
            artist = "Tap to expand player & queue",
            audioUri = "",
            imageUrl = null,
            isFavorite = false,
            duration = 180000L,
            position = 0
        )
    } else null

    val showUpdateModal by otaViewModel.showUpdateModal.collectAsState()
    val showWhatsNewModal by otaViewModel.showWhatsNewModal.collectAsState()
    val showPermissionModal by otaViewModel.showPermissionModal.collectAsState()
    val showSettingsModal by otaViewModel.showSettingsModal.collectAsState()
    val toastMessage by otaViewModel.toastMessage.collectAsState()

    val showQueueSheet by viewModel.showQueueSheet.collectAsState()
    val selectedSongForOptions by viewModel.selectedSongForOptions.collectAsState()
    val queueSnackbarMessage by viewModel.queueSnackbarMessage.collectAsState()

    LaunchedEffect(Unit) {
        otaViewModel.initializeOnStart()
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            otaViewModel.clearToast()
        }
    }

    // Determine which screens should hide the bottom bar.
    // The player screen should hide it.
    // Add any other full-screen routes here if necessary.
    val hideBottomBarRoutes = listOf("player") 
    
    // Check if the current route starts with any of the hidden routes.
    // This handles routes with arguments like "playlist/{id}".
    val showBottomBar = currentDestination?.route?.let { route ->
        hideBottomBarRoutes.none { route.startsWith(it) }
    } ?: true

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        FluidMeshBackground(
            pagerOffset = { pagerState.currentPage + pagerState.currentPageOffsetFraction }
        ) {
            if (isLandscape && showBottomBar) {
                // --- LANDSCAPE ADAPTIVE LAYOUT ---
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    GlassNavigationRail(
                        pagerOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                        onPageSelected = { page ->
                            scope.launch { pagerState.animateScrollToPage(page) }
                        },
                        spotlightController = spotlightController
                    )

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        if (effectiveSong != null) {
                            CompactPlayerPane(
                                song = effectiveSong,
                                isPlaying = isPlaying,
                                onTogglePlay = { viewModel.togglePlayPause() },
                                onSkipNext = { viewModel.skipToNext() },
                                onSkipPrevious = { viewModel.skipToPrevious() },
                                onClick = { navController.navigate("player") },
                                modifier = Modifier.weight(0.45f),
                                spotlightController = spotlightController
                            )
                        }

                        Box(modifier = Modifier.weight(0.55f)) {
                            MainPagerContent(
                                pagerState = pagerState,
                                viewModel = viewModel,
                                otaViewModel = otaViewModel,
                                navController = navController,
                                scope = scope,
                                spotlightController = spotlightController
                            )
                        }
                    }
                }
            } else {
                // --- PORTRAIT OR NESTED LANDSCAPE LAYOUT ---
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        if (showBottomBar) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } }
                            ) {
                                if (currentDestination?.route != "player" && effectiveSong != null) {
                                    NowPlayingBar(
                                        song = effectiveSong,
                                        isPlaying = isPlaying,
                                        onTogglePlay = { viewModel.togglePlayPause() },
                                        onClick = { navController.navigate("player") },
                                        spotlightController = spotlightController
                                    )
                                }
                                GlassNavigationBar(
                                    pagerOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                                    onPageSelected = { page ->
                                        scope.launch {
                                            pagerState.animateScrollToPage(page)
                                        }
                                    },
                                    spotlightController = spotlightController
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    CompositionLocalProvider(
                        LocalBottomBarHeight provides (if (showBottomBar) bottomBarHeight else 0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    if (showBottomBar) PaddingValues(top = innerPadding.calculateTopPadding())
                                    else PaddingValues()
                                )
                                .consumeWindowInsets(WindowInsets.navigationBars)
                        ) {
                            NavHost(
                            navController = navController,
                            startDestination = "main",
                            modifier = Modifier.fillMaxSize(),
                            enterTransition = { fadeIn(animationSpec = tween(300)) },
                            exitTransition = { fadeOut(animationSpec = tween(300)) },
                            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
                            popExitTransition = { fadeOut(animationSpec = tween(300)) }
                        ) {
                            composable("main") {
                                MainPagerContent(
                                    pagerState = pagerState,
                                    viewModel = viewModel,
                                    otaViewModel = otaViewModel,
                                    navController = navController,
                                    scope = scope,
                                    spotlightController = spotlightController
                                )
                            }
                            composable(
                                "playlist/{playlistId}/{playlistName}",
                                arguments = listOf(
                                    navArgument("playlistId") { type = NavType.IntType },
                                    navArgument("playlistName") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val playlistId = backStackEntry.arguments?.getInt("playlistId") ?: 0
                                val playlistName = Uri.decode(backStackEntry.arguments?.getString("playlistName") ?: "")
                                PlaylistDetailScreen(
                                    playlistId = playlistId,
                                    playlistName = playlistName,
                                    viewModel = viewModel,
                                    onBackClick = { navController.popBackStack() },
                                    onSongClick = { navController.navigate("player") }
                                )
                            }
                            composable("player") {
                                PlayerScreen(
                                    viewModel = viewModel,
                                    onBackClick = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
            }

            // --- OTA MODAL DIALOGS ---
            if (showUpdateModal) {
                OtaUpdateAvailableDialog(otaViewModel)
            }
            if (showWhatsNewModal) {
                OtaWhatsNewDialog(otaViewModel)
            }
            if (showPermissionModal) {
                OtaInstallPermissionDialog(otaViewModel)
            }
            if (showSettingsModal) {
                OtaSettingsDialog(otaViewModel)
            }

            // Queue Bottom Sheet
            if (showQueueSheet) {
                QueueBottomSheet(
                    viewModel = viewModel,
                    onDismissRequest = { viewModel.closeQueueSheet() }
                )
            }

            // Song Context Menu Options Sheet
            selectedSongForOptions?.let { song ->
                SongOptionsMenuSheet(
                    song = song,
                    viewModel = viewModel,
                    onDismissRequest = { viewModel.closeSongOptions() }
                )
            }

            // Floating Queue Confirmation Toast Pill
            Box(modifier = Modifier.fillMaxSize()) {
                val snackbarBottomPadding = when {
                    showBottomBar && effectiveSong != null -> 168.dp
                    showBottomBar -> 88.dp
                    else -> 24.dp
                }
                QueueSnackbar(
                    message = queueSnackbarMessage,
                    onOpenQueue = { viewModel.openQueueSheet() },
                    onDismiss = { viewModel.clearQueueSnackbar() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = snackbarBottomPadding)
                )
            }

            // Floating Debug Overlay
            DebugOverlay(viewModel, spotlightController)

            // Interactive Spotlight Walkthrough Overlay
            SpotlightOverlay(controller = spotlightController)
        }
    }
}

@Composable
fun MainPagerContent(
    pagerState: PagerState,
    viewModel: SongViewModel,
    otaViewModel: OtaUpdateViewModel,
    navController: NavController,
    scope: CoroutineScope,
    spotlightController: SpotlightController? = null
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { it }
    ) { page ->
        when (page) {
            0 -> DiscoverScreen(
                viewModel = viewModel,
                onSongClick = { navController.navigate("player") },
                onSettingsClick = { otaViewModel.openSettingsModal() },
                spotlightController = spotlightController
            )
            1 -> FavoritesScreen(
                viewModel = viewModel,
                onSongClick = { navController.navigate("player") }
            )
            2 -> LibraryScreen(
                viewModel = viewModel,
                onPlaylistClick = { navController.navigate("playlist/${it.id}/${Uri.encode(it.name)}") },
                onFavoritesClick = { 
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                onSongClick = { navController.navigate("player") },
                spotlightController = spotlightController
            )
        }
    }
}
