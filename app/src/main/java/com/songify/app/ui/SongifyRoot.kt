package com.songify.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.songify.app.CrashReport
import com.songify.app.data.Settings
import com.songify.app.playback.Downloads
import androidx.compose.runtime.collectAsState
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext

@Composable
fun SongifyRoot(app: AppViewModel) {
    val controller = rememberMediaController()
    val nowPlaying = controller?.nowPlayingState()?.value
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ui = remember(nav) { UiState(nav, snackbar, scope) }

    LaunchedEffect(nowPlaying?.error) {
        nowPlaying?.error?.let { ui.toast("Skipped a song: $it") }
    }
    LaunchedEffect(Unit) {
        if (!Settings.guideSeen) ui.guidePage = 0
        if (Settings.updateCheckDue()) app.checkForUpdate(manual = false)
    }
    LaunchedEffect(Unit) {
        Downloads.failures.collect { ui.toast("Couldn't download “${it.title}”") }
    }
    val downloadStates by Downloads.states.collectAsState()
    var crash by remember { mutableStateOf(CrashReport.take()) }

    CompositionLocalProvider(
        LocalUi provides ui,
        LocalAppViewModel provides app,
        LocalPlayer provides controller,
        LocalCurrentKey provides nowPlaying?.track?.key,
        LocalIsPlaying provides (nowPlaying?.isPlaying == true),
        LocalDownloads provides downloadStates,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                        if (controller != null && nowPlaying?.track != null) {
                            MiniPlayer(nowPlaying, controller) { ui.playerOpen = true }
                        }
                        BottomNav(nav)
                    }
                },
            ) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
                    enterTransition = { fadeIn(tween(150)) },
                    exitTransition = { fadeOut(tween(150)) },
                ) {
                    composable(Routes.HOME) { HomeScreen() }
                    composable(
                        Routes.SEARCH,
                        arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { entry -> SearchScreen(initialQuery = entry.arguments?.getString("q")) }
                    composable(Routes.LIBRARY) { LibraryScreen() }
                    composable(Routes.SETTINGS) { SettingsScreen() }
                    composable(Routes.PLAYLIST) { entry -> PlaylistScreen(entry.arguments?.getString("id").orEmpty()) }
                    composable(Routes.ARTIST) { entry -> ArtistScreen(entry.arguments?.getString("name").orEmpty()) }
                }
            }

            AnimatedVisibility(
                visible = ui.playerOpen && controller != null && nowPlaying?.track != null,
                enter = slideInVertically(tween(260)) { it },
                exit = slideOutVertically(tween(220)) { it },
            ) {
                // The player draws its own gradient instead of a Surface, so set white content explicitly.
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    if (controller != null && nowPlaying != null) FullPlayer(nowPlaying, controller) { ui.playerOpen = false }
                }
            }
            ui.guidePage?.let { page -> GuideScreen(page) { ui.guidePage = null } }
            Sheets(nowPlaying, controller)
            UpdateDialog()
            crash?.let { report ->
                val context = LocalContext.current
                AlertDialog(
                    onDismissRequest = { crash = null },
                    title = { Text("Songify closed unexpectedly") },
                    text = { Text("Sorry about that. Sharing the error report helps get it fixed.") },
                    confirmButton = {
                        TextButton(onClick = {
                            crash = null
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                            context.startActivity(Intent.createChooser(send, "Share error report"))
                        }) { Text("Share report") }
                    },
                    dismissButton = { TextButton(onClick = { crash = null }) { Text("Dismiss") } },
                )
            }
        }
    }
}

private data class Tab(val route: String, val target: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val Tabs = listOf(
    Tab(Routes.HOME, Routes.HOME, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Routes.SEARCH, Routes.SEARCH_ROOT, "Search", Icons.Rounded.Search, Icons.Rounded.Search),
    Tab(Routes.LIBRARY, Routes.LIBRARY, "Your Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

/** Each tab keeps its own history; tapping the current tab again returns to its top. */
@Composable
private fun BottomNav(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    var currentTab by rememberSaveable { mutableStateOf(Routes.HOME) }
    val route = entry?.destination?.route
    LaunchedEffect(route) { Tabs.firstOrNull { it.route == route }?.let { currentTab = it.route } }

    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Tabs.forEach { tab ->
            val selected = currentTab == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (selected) {
                        if (!nav.popBackStack(tab.route, inclusive = false)) nav.navigate(tab.target)
                    } else {
                        currentTab = tab.route
                        nav.navigate(tab.target) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
