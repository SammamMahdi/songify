package com.songify.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.songify.app.data.LIKED_ID
import com.songify.app.data.Library
import com.songify.app.data.Lyrics
import com.songify.app.data.LyricsRepo
import com.songify.app.data.Track
import com.songify.desktop.DesktopDownloads
import com.songify.desktop.DesktopPlayer
import com.songify.desktop.DesktopSettings
import com.songify.desktop.DesktopSleep
import com.songify.desktop.Repeat
import com.songify.desktop.Sleep
import com.songify.desktop.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Below this the sidebar collapses to icons; the panel limits keep the main area usable.
private const val COMPACT_SIDEBAR = 72f
private const val MIN_SIDEBAR = 220f
private const val MAX_SIDEBAR = 460f
private const val MIN_PANEL = 260f
private const val MAX_PANEL = 560f

@Composable
fun SongifyDesktop(crashReport: String?) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ui = remember { DesktopUi(snackbar, scope) }
    val library by Library.data.collectAsState()
    val downloads by DesktopDownloads.states.collectAsState()
    val player by DesktopPlayer.state.collectAsState()
    val likedKeys = remember(library.liked) { library.liked.map { it.key }.toSet() }

    LaunchedEffect(Unit) {
        if (!DesktopSettings.prefs.value.guideSeen) ui.guidePage = 0
        if (DesktopSettings.updateCheckDue()) Updates.check(manual = false)
    }
    LaunchedEffect(Unit) { DesktopPlayer.messages.collect { ui.toast(it) } }
    LaunchedEffect(Unit) { DesktopDownloads.failures.collect { ui.toast("Couldn't download “${it.title}”") } }

    // Widths follow the drag live and are saved when the drag ends.
    var sidebarRaw by remember { mutableFloatStateOf(DesktopSettings.prefs.value.sidebarWidth) }
    var panelWidth by remember { mutableFloatStateOf(DesktopSettings.prefs.value.panelWidth) }
    val sidebarWidth = if (sidebarRaw < (COMPACT_SIDEBAR + MIN_SIDEBAR) / 2) COMPACT_SIDEBAR else sidebarRaw.coerceIn(MIN_SIDEBAR, MAX_SIDEBAR)
    val compact = sidebarWidth == COMPACT_SIDEBAR

    CompositionLocalProvider(
        LocalUi provides ui,
        LocalLikedKeys provides likedKeys,
        LocalDownloadStates provides downloads,
        LocalCurrentKey provides player.current?.key,
    ) {
        // A Surface (not a plain background) so icons and text default to white everywhere.
        Surface(color = Color.Black, contentColor = Color.White) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                        Sidebar(compact, Modifier.width(sidebarWidth.dp).fillMaxHeight())
                        Splitter(
                            // Accumulate the raw drag so the sidebar can be pulled back out of icon mode.
                            onDrag = { sidebarRaw = (sidebarRaw + it.value).coerceIn(COMPACT_SIDEBAR, MAX_SIDEBAR) },
                            onDragEnd = {
                                sidebarRaw = sidebarWidth
                                DesktopSettings.update { p -> p.copy(sidebarWidth = sidebarWidth) }
                            },
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        ) {
                            Content()
                        }
                        ui.rightPanel?.let { panel ->
                            Splitter(
                                onDrag = { panelWidth = (panelWidth - it.value).coerceIn(MIN_PANEL, MAX_PANEL) },
                                onDragEnd = { DesktopSettings.update { p -> p.copy(panelWidth = panelWidth) } },
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.width(panelWidth.dp).fillMaxHeight(),
                            ) {
                                when (panel) {
                                    RightPanel.QUEUE -> QueuePanel()
                                    RightPanel.LYRICS -> LyricsPanel()
                                }
                            }
                        }
                    }
                    PlayerBar()
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
                Dialogs(crashReport)
            }
        }
    }
}

@Composable
private fun Content() {
    val ui = LocalUi.current
    Box(Modifier.fillMaxSize()) {
        when (val screen = ui.screen) {
            Screen.Home -> HomeScreen()
            Screen.Search -> SearchScreen()
            is Screen.PlaylistScreen -> PlaylistScreen(screen.id)
            is Screen.ArtistScreen -> ArtistScreen(screen.name)
            Screen.SettingsScreen -> SettingsScreen()
        }
        if (ui.stack.size > 1) {
            Surface(
                onClick = ui::back,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.padding(16.dp).size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            }
        }
    }
}

@Composable
private fun Sidebar(compact: Boolean, modifier: Modifier) {
    val ui = LocalUi.current
    val library by Library.data.collectAsState()
    val downloaded by DesktopDownloads.downloaded.collectAsState()
    var addMenu by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                NavItem(Icons.Rounded.Home, "Home", ui.screen == Screen.Home, compact) { ui.go(Screen.Home) }
                NavItem(Icons.Rounded.Search, "Search", ui.screen == Screen.Search, compact) { ui.go(Screen.Search) }
                NavItem(Icons.Rounded.Settings, "Settings", ui.screen == Screen.SettingsScreen, compact) { ui.go(Screen.SettingsScreen) }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = if (compact) 0.dp else 20.dp, end = if (compact) 0.dp else 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
                ) {
                    if (!compact) Text("Your Library", style = MaterialTheme.typography.titleMedium, color = TextSecondary, modifier = Modifier.weight(1f))
                    Box {
                        IconAction(Icons.Rounded.Add, "Create or import") { addMenu = true }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(text = { Text("Create a playlist") }, onClick = { addMenu = false; ui.createPlaylist = emptyList() })
                            DropdownMenuItem(text = { Text("Move playlists from Spotify (guide)") }, onClick = { addMenu = false; ui.guidePage = GUIDE_IMPORT_PAGE })
                            DropdownMenuItem(text = { Text("Import a file…") }, onClick = { addMenu = false; ui.importTab = ImportTab.FILE })
                            DropdownMenuItem(text = { Text("Import from a link…") }, onClick = { addMenu = false; ui.importTab = ImportTab.LINK })
                            DropdownMenuItem(text = { Text("Paste a song list…") }, onClick = { addMenu = false; ui.importTab = ImportTab.PASTE })
                        }
                    }
                }
                ScrollColumn(contentPadding = PaddingValues(8.dp)) {
                    item { LibraryItem("Liked Songs", "Playlist · ${library.liked.size} songs", library.liked, liked = true, LIKED_ID, compact) }
                    if (downloaded.isNotEmpty()) item { LibraryItem("Downloaded", "${downloaded.size} songs", downloaded, false, DOWNLOADS_ID, compact) }
                    items(library.playlists.sortedByDescending { it.updatedAt }, key = { it.id }) { p ->
                        LibraryItem(p.name, "Playlist · ${p.tracks.size} songs", p.tracks, false, p.id, compact)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Color.White else TextSecondary
    if (compact) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { IconAction(icon, label, tint = tint, onClick = onClick) }
        return
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryItem(name: String, subtitle: String, tracks: List<Track>, liked: Boolean, id: String, compact: Boolean) {
    val ui = LocalUi.current
    val selected = (ui.screen as? Screen.PlaylistScreen)?.id == id
    TooltipArea(
        tooltip = {
            if (compact) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(4.dp)) {
                    Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        },
        delayMillis = 400,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                .clickable { ui.go(Screen.PlaylistScreen(id)) }
                .padding(if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        ) {
            PlaylistCover(tracks, Modifier.size(48.dp), liked = liked)
            if (!compact) {
                Column(Modifier.padding(start = 12.dp)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) MaterialTheme.colorScheme.primary else Color.White)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun PlayerBar() {
    val ui = LocalUi.current
    val s by DesktopPlayer.state.collectAsState()
    val sleep by DesktopSleep.state.collectAsState()
    val liked = LocalLikedKeys.current
    val track = s.current
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        // Now playing
        Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically) {
            if (track != null) {
                Artwork(track.artworkUrl, Modifier.size(56.dp))
                Column(Modifier.weight(1f, fill = false).padding(horizontal = 12.dp)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        track.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.clickable { ui.go(Screen.ArtistScreen(track.artist)) },
                    )
                }
                val isLiked = track.key in liked
                IconAction(
                    if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (isLiked) "Remove from Liked Songs" else "Add to Liked Songs",
                    tint = if (isLiked) accent else TextSecondary,
                    size = 20.dp,
                ) { Library.toggleLike(track) }
                IconAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = TextSecondary, size = 20.dp) { ui.addToPlaylist = listOf(track) }
                SongMenuButton(track)
            }
        }
        // Controls and progress
        Column(Modifier.weight(0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconAction(Icons.Rounded.Shuffle, if (s.shuffle) "Shuffle on" else "Shuffle", tint = if (s.shuffle) accent else TextSecondary, onClick = DesktopPlayer::toggleShuffle)
                IconAction(Icons.Rounded.SkipPrevious, "Previous (Ctrl+←)", onClick = DesktopPlayer::previous)
                Surface(onClick = DesktopPlayer::togglePlay, shape = CircleShape, color = Color.White, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (s.isLoading) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (s.isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                            )
                        }
                    }
                }
                IconAction(Icons.Rounded.SkipNext, "Next (Ctrl+→)", onClick = DesktopPlayer::next)
                IconAction(
                    if (s.repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (s.repeat) { Repeat.OFF -> "Repeat"; Repeat.ALL -> "Repeat all"; Repeat.ONE -> "Repeat one" },
                    tint = if (s.repeat == Repeat.OFF) TextSecondary else accent,
                    onClick = DesktopPlayer::cycleRepeat,
                )
            }
            SeekBar(s.durationMs, enabled = track != null)
        }
        // Extras and volume
        Row(Modifier.weight(0.3f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.Lyrics, "Lyrics", tint = if (ui.rightPanel == RightPanel.LYRICS) accent else TextSecondary) { ui.toggle(RightPanel.LYRICS) }
            IconAction(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = if (ui.rightPanel == RightPanel.QUEUE) accent else TextSecondary) { ui.toggle(RightPanel.QUEUE) }
            SleepButton(sleep)
            IconAction(
                if (s.volume > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                if (s.volume > 0f) "Mute" else "Unmute",
                tint = TextSecondary,
            ) { DesktopPlayer.setVolume(if (s.volume > 0f) 0f else 0.8f) }
            Slider(
                value = s.volume,
                onValueChange = DesktopPlayer::setVolume,
                modifier = Modifier.width(120.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
            )
        }
    }
}

// Isolated so the ticking position only redraws the seek bar.
@Composable
private fun SeekBar(durationMs: Long, enabled: Boolean) {
    val position by produceState(0L) {
        while (true) {
            value = DesktopPlayer.positionMs
            delay(250)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shown = dragging?.let { (it * durationMs).toLong() } ?: position
        Text(formatTime(shown), style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.width(40.dp))
        Slider(
            value = dragging ?: fraction,
            enabled = enabled,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { DesktopPlayer.seek((it * durationMs).toLong()) }
                dragging = null
            },
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
        )
        Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.padding(start = 8.dp).width(40.dp))
    }
}

@Composable
private fun SleepButton(sleep: Sleep) {
    val ui = LocalUi.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(Icons.Rounded.Bedtime, "Sleep timer", tint = if (sleep != Sleep.Off) MaterialTheme.colorScheme.primary else TextSecondary) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(5, 15, 30, 45, 60).forEach { minutes ->
                val label = if (minutes == 60) "1 hour" else "$minutes minutes"
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    DesktopSleep.start(minutes)
                    ui.toast("Music will stop in $label")
                })
            }
            DropdownMenuItem(text = { Text("End of song") }, onClick = { open = false; DesktopSleep.endOfSong(); ui.toast("Music will stop after this song") })
            if (sleep != Sleep.Off) DropdownMenuItem(text = { Text("Turn off timer") }, onClick = { open = false; DesktopSleep.cancel() })
        }
    }
}

@Composable
private fun PanelHeader(title: String) {
    val ui = LocalUi.current
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconAction(Icons.Rounded.Close, "Close") { ui.rightPanel = null }
    }
}

@Composable
private fun QueuePanel() {
    val s by DesktopPlayer.state.collectAsState()
    val upcoming = s.queue.withIndex().drop(s.index + 1)
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Queue")
        ScrollColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            s.current?.let { current ->
                item { Text("Now playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                item { QueueRow(current, highlight = true, onClick = {}, onRemove = null) }
            }
            item {
                Text(
                    if (upcoming.isEmpty()) "Nothing up next. Autoplay adds similar songs." else "Next up",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(upcoming, key = { (i, t) -> "$i/${t.key}" }) { (i, t) ->
                QueueRow(t, highlight = false, onClick = { DesktopPlayer.playAt(i) }, onRemove = { DesktopPlayer.removeAt(i) })
            }
        }
    }
}

@Composable
private fun QueueRow(track: Track, highlight: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artworkUrl, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (highlight) MaterialTheme.colorScheme.primary else Color.White)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        onRemove?.let { IconAction(Icons.Rounded.Close, "Remove from queue", tint = TextSecondary, size = 18.dp, onClick = it) }
    }
}

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object None : LyricsUi
    data object Failed : LyricsUi
    data class Loaded(val lyrics: Lyrics) : LyricsUi
}

@Composable
private fun LyricsPanel() {
    val track = DesktopPlayer.state.collectAsState().value.current
    val state by produceState<LyricsUi>(LyricsUi.Loading, track?.key) {
        value = LyricsUi.Loading
        value = if (track == null) LyricsUi.None else withContext(Dispatchers.IO) {
            runCatching { LyricsRepo.get(track) }.fold({ it?.let(LyricsUi::Loaded) ?: LyricsUi.None }, { LyricsUi.Failed })
        }
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Lyrics")
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (val s = state) {
                LyricsUi.Loading -> CircularProgressIndicator()
                LyricsUi.None -> Text("No lyrics for this song yet", color = TextSecondary)
                LyricsUi.Failed -> Text("Couldn't load lyrics. Check your connection.", color = TextSecondary)
                is LyricsUi.Loaded -> if (s.lyrics.synced.isNotEmpty()) SyncedLyrics(s.lyrics) else {
                    Text(s.lyrics.plain.orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics) {
    val position by produceState(0L) {
        while (true) {
            value = DesktopPlayer.positionMs
            delay(200)
        }
    }
    val lines = lyrics.synced
    val current by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= position + 300 }.coerceAtLeast(0) } }
    val listState = rememberLazyListState()
    LaunchedEffect(current) { listState.animateScrollToItem((current - 3).coerceAtLeast(0)) }
    ScrollColumn(state = listState) {
        itemsIndexed(lines) { i, line ->
            Text(
                line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = when {
                    i == current -> Color.White
                    i < current -> Color.White.copy(alpha = 0.5f)
                    else -> Color.White.copy(alpha = 0.3f)
                },
                modifier = Modifier.fillMaxWidth().clickable { DesktopPlayer.seek(line.timeMs) }.padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
    }
}
