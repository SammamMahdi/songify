package com.songify.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songify.app.data.LIKED_ID
import com.songify.app.data.Library
import com.songify.app.data.Playlist
import com.songify.app.data.PlaylistShare
import com.songify.app.data.Track

private enum class LibrarySort(val label: String) { RECENT("Recents"), NAME("Alphabetical"), SIZE("Most songs") }

@Composable
fun LibraryScreen() {
    val ui = LocalUi.current
    val library by Library.data.collectAsState()
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENT) }
    val playlists = when (sort) {
        LibrarySort.RECENT -> library.playlists.sortedByDescending { it.updatedAt }
        LibrarySort.NAME -> library.playlists.sortedBy { it.name.lowercase() }
        LibrarySort.SIZE -> library.playlists.sortedByDescending { it.tracks.size }
    }
    // Liked Songs is always pinned on top.
    val rows = listOf(Playlist(LIKED_ID, "Liked Songs", library.liked)) + playlists

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Your Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { ui.importMenu = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Create or import a playlist", modifier = Modifier.size(28.dp))
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { sort = option },
                        label = { Text(option.label) },
                        shape = RoundedCornerShape(50),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
        items(rows, key = { it.id }) { playlist ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { ui.nav.navigate(Routes.playlist(playlist.id)) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaylistCover(playlist.tracks, Modifier.size(64.dp), liked = playlist.id == LIKED_ID)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("Playlist · ")
                            append(if (playlist.tracks.size == 1) "1 song" else "${playlist.tracks.size} songs")
                            if (playlist.missing.isNotEmpty()) append(" · ${playlist.missing.size} not found")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (library.playlists.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Create your first playlist", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Start fresh, or bring one over from Spotify or Apple Music.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { ui.importMenu = true }) { Text("Create or import") }
                }
            }
        }
    }
}

private enum class TrackSort(val label: String) { CUSTOM("Custom order"), TITLE("Title"), ARTIST("Artist") }

@Composable
fun PlaylistScreen(id: String) {
    val ui = LocalUi.current
    val app = LocalAppViewModel.current
    val player = LocalPlayer.current
    val context = LocalContext.current
    val library by Library.data.collectAsState()
    val playlist = library.playlist(id)
    if (playlist == null) {
        EmptyMessage("This playlist was deleted.")
        return
    }
    val liked = id == LIKED_ID
    val base = rememberArtworkColor(playlist.tracks.firstOrNull()?.artworkUrl, if (liked) Color(0xFF4A2FBD) else Color(0xFF3A3A3A))
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TrackSort.CUSTOM) }
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    // What's shown (and what plays) follows the current filter and sort.
    val shown: List<Track> = playlist.tracks
        .let { all ->
            when (sort) {
                TrackSort.CUSTOM -> all
                TrackSort.TITLE -> all.sortedBy { it.title.lowercase() }
                TrackSort.ARTIST -> all.sortedBy { it.artist.lowercase() }
            }
        }
        .let { all ->
            val q = filter.trim().lowercase()
            if (q.isEmpty()) all else all.filter { q in it.title.lowercase() || q in it.artist.lowercase() }
        }
    val playingThis = LocalCurrentKey.current?.let { key -> playlist.tracks.any { it.key == key } } == true

    Box {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(lerp(base, Color.Black, 0.2f), MaterialTheme.colorScheme.background)))
                        .statusBarsPadding()
                        .padding(top = 48.dp),
                ) {
                    PlaylistCover(playlist.tracks, Modifier.align(Alignment.CenterHorizontally).size(220.dp), liked = liked, corner = 6.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatTotal(playlist.tracks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { context.startActivity(PlaylistShare.fileIntent(context, playlist)) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share playlist")
                        }
                        IconButton(onClick = { filtering = !filtering; if (!filtering) filter = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Find in playlist")
                        }
                        Box {
                            IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort") }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                TrackSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                option.label,
                                                color = if (sort == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            )
                                        },
                                        onClick = { sort = option; sortOpen = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Add songs") },
                                    leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                    onClick = { menuOpen = false; ui.nav.navigate(Routes.SEARCH_ROOT) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Share as text list") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null) },
                                    onClick = {
                                        menuOpen = false
                                        context.startActivity(PlaylistShare.textIntent(playlist))
                                    },
                                )
                                if (!liked) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                        onClick = { menuOpen = false; renaming = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete playlist") },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                        onClick = { menuOpen = false; deleting = true },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { player?.playTracks(playlist.tracks, shuffle = true) }, enabled = playlist.tracks.isNotEmpty()) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle play", modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        PlayCircle(playing = playingThis && LocalIsPlaying.current, onClick = {
                            when {
                                playingThis -> player?.togglePlay()
                                shown.isNotEmpty() -> player?.playTracks(shown)
                            }
                        })
                    }
                    if (filtering) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = { Text("Find in playlist") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                IconButton(onClick = { filter = ""; filtering = false }) { Icon(Icons.Rounded.Close, "Close search") }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (playlist.tracks.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (liked) "Songs you like will appear here" else "Let's find something for your playlist",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { ui.nav.navigate(Routes.SEARCH_ROOT) }) { Text("Find songs") }
                    }
                }
            } else if (shown.isEmpty()) {
                item { EmptyMessage("No songs match “${filter.trim()}”") }
            }
            itemsIndexed(shown, key = { i, t -> "$i/${t.key}" }) { i, track ->
                TrackRow(
                    track,
                    onClick = { player?.playTracks(shown, i) },
                    onMore = { ui.trackMenu = TrackMenu(track, playlist.id) },
                )
            }
            if (playlist.missing.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Text(
                            "Couldn't find ${playlist.missing.size}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                        )
                        TextButton(onClick = { app.retryMissing(playlist.id) }) { Text("Try again") }
                    }
                    Text(
                        "Tap Find to pick the right version yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(playlist.missing, key = { "missing/${it.title}/${it.artist}" }) { wanted ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(wanted.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (wanted.artist.isNotBlank()) {
                                Text(
                                    wanted.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        OutlinedButton(onClick = { ui.replace = Replace(playlist.id, wanted) }) { Text("Find") }
                        IconButton(onClick = { Library.resolveMissing(playlist.id, wanted, null) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }

        // Top bar: back button always, title fades in once the header scrolls away.
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (collapsed) lerp(base, Color.Black, 0.55f) else Color.Transparent)
                .statusBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { ui.nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            AnimatedVisibility(collapsed, enter = fadeIn(), exit = fadeOut()) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (renaming) {
        NameDialog("Rename playlist", playlist.name, "Save", onDismiss = { renaming = false }) {
            Library.renamePlaylist(playlist.id, it)
            renaming = false
        }
    }
    if (deleting) {
        ConfirmDialog(
            title = "Delete “${playlist.name}”?",
            text = "This removes the playlist from this phone. Share it first if you want to keep a copy.",
            confirm = "Delete",
            onDismiss = { deleting = false },
        ) {
            deleting = false
            ui.nav.popBackStack()
            Library.deletePlaylist(playlist.id)
        }
    }
}

@Composable
fun CircleIcon(content: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        content()
    }
}
