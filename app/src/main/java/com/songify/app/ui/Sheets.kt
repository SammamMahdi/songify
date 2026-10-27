package com.songify.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import com.songify.app.data.Library
import com.songify.app.data.MusicSearch
import com.songify.app.data.PlaylistImport
import com.songify.app.data.PlaylistShare
import com.songify.app.data.Track
import com.songify.app.data.YouTubeMusic
import com.songify.app.playback.Downloads
import com.songify.app.playback.Offline
import com.songify.app.playback.Sleep
import com.songify.app.playback.SleepTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Hosts every app-wide sheet and dialog; screens open them through [UiState]. */
@Composable
fun Sheets(nowPlaying: NowPlaying?, player: Player?) {
    val ui = LocalUi.current
    val app = LocalAppViewModel.current
    // Registered here, not inside the sheet, so the result still arrives after the sheet closes.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(app::importFile)
    }

    ui.trackMenu?.let { TrackMenuSheet(it) { ui.trackMenu = null } }
    ui.addToPlaylist?.let { AddToPlaylistSheet(it) { ui.addToPlaylist = null } }
    ui.replace?.let { ReplaceSheet(it) { ui.replace = null } }
    ui.createPlaylist?.let { tracks ->
        val count = Library.data.collectAsState().value.playlists.size
        NameDialog("Give your playlist a name", "My playlist #${count + 1}", "Create", onDismiss = { ui.createPlaylist = null }) { name ->
            ui.createPlaylist = null
            val playlist = Library.createPlaylist(name, tracks)
            if (tracks.isEmpty()) ui.open(Routes.playlist(playlist.id)) else ui.toast("Added to ${playlist.name}")
        }
    }
    if (ui.importMenu) ImportMenuSheet(onPickFile = { pickFile.launch(arrayOf("*/*")) }) { ui.importMenu = false }
    if (ui.linkDialog) LinkDialog { ui.linkDialog = false }
    if (ui.pasteDialog) PasteDialog { ui.pasteDialog = false }
    if (ui.queueOpen && player != null) QueueSheet(player, nowPlaying) { ui.queueOpen = false }
    if (ui.sleepOpen) SleepSheet { ui.sleepOpen = false }
    ImportProgress()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackMenuSheet(menu: TrackMenu, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val player = LocalPlayer.current
    val context = LocalContext.current
    val track = menu.track
    val liked = Library.data.collectAsState().value.isLiked(track)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artworkUrl, Modifier.size(52.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${track.artist} · ${track.source.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SheetAction(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Remove from Liked Songs" else "Add to Liked Songs") {
            val nowLiked = Library.toggleLike(track)
            ui.toast(if (nowLiked) "Added to Liked Songs" else "Removed from Liked Songs")
            onDismiss()
        }
        SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") {
            onDismiss()
            ui.addToPlaylist = listOf(track)
        }
        when (LocalDownloads.current[track.key]) {
            Offline.Done -> SheetAction(Icons.Rounded.DownloadDone, "Remove download") {
                Downloads.remove(listOf(track))
                ui.toast("Removed from downloads")
                onDismiss()
            }
            Offline.Queued, is Offline.Downloading -> SheetAction(Icons.Rounded.Close, "Cancel download") {
                Downloads.remove(listOf(track))
                onDismiss()
            }
            Offline.Failed -> SheetAction(Icons.Rounded.Refresh, "Couldn't download · Try again") {
                Downloads.download(listOf(track))
                ui.toast("Trying again")
                onDismiss()
            }
            null -> SheetAction(Icons.Rounded.Download, "Download") {
                Downloads.download(listOf(track))
                ui.toast("Downloading ${track.title}")
                onDismiss()
            }
        }
        SheetAction(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") {
            player?.playNext(track)
            ui.toast("Playing next")
            onDismiss()
        }
        SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") {
            player?.enqueue(track)
            ui.toast("Added to queue")
            onDismiss()
        }
        SheetAction(Icons.Rounded.Radio, "Start radio") {
            player?.startRadio(track)
            ui.toast("Playing songs like ${track.title}")
            onDismiss()
        }
        SheetAction(Icons.Rounded.Person, "Go to artist") {
            onDismiss()
            ui.open(Routes.artist(track.artist))
        }
        menu.playlistId?.let { id ->
            SheetAction(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist") {
                Library.removeFromPlaylist(id, track)
                ui.toast("Removed")
                onDismiss()
            }
        }
        SheetAction(Icons.Rounded.Share, "Share") {
            context.startActivity(PlaylistShare.trackIntent(track))
            onDismiss()
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(tracks: List<Track>, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val playlists = Library.data.collectAsState().value.playlists.sortedByDescending { it.updatedAt }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("Add to playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        SheetAction(Icons.Rounded.Add, "New playlist") {
            onDismiss()
            ui.createPlaylist = tracks
        }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val added = Library.addToPlaylist(playlist.id, tracks)
                            ui.toast(if (added == 0) "Already in ${playlist.name}" else "Added to ${playlist.name}")
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistCover(playlist.tracks, Modifier.size(48.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${playlist.tracks.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Lets the user pick the right recording for a song the import couldn't match. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaceSheet(replace: Replace, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val player = LocalPlayer.current
    val wanted = replace.wanted
    var query by remember { mutableStateOf(listOf(wanted.title, wanted.artist).filter { it.isNotBlank() }.joinToString(" ")) }
    val results by produceState<List<Track>?>(null, query) {
        value = null
        delay(350) // wait for typing to pause
        value = withContext(Dispatchers.IO) {
            val q = query.trim()
            val pasted = YouTubeMusic.videoId(q)
            when {
                q.isEmpty() -> emptyList()
                // The user pasted the exact song they want.
                pasted != null -> listOfNotNull(runCatching { YouTubeMusic.track(pasted) }.getOrNull())
                else -> (runCatching { MusicSearch.youtube(q) }.getOrDefault(emptyList()) +
                    runCatching { MusicSearch.audius(q) }.getOrDefault(emptyList())).distinctBy { it.key }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text("Find the right song", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "For “$wanted”. Search, or paste a link to the song. Tap a result to add it, or ▶ to preview.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val list = results
        if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        else if (list.isEmpty()) EmptyMessage("No results. Try fewer words, or paste a link to the song.")
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(list.orEmpty(), key = { it.key }) { track ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Library.resolveMissing(replace.playlistId, wanted, track)
                            ui.toast("Added ${track.title}")
                            onDismiss()
                        }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(track.artworkUrl, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${track.artist} · ${formatTime(track.durationSec * 1000)} · ${track.source.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { player?.playTracks(listOf(track)) }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = "Preview")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Up next, in play order (respects shuffle). Tap to jump, ✕ to remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(player: Player, nowPlaying: NowPlaying?, onDismiss: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val upNext = remember(nowPlaying?.track?.key, nowPlaying?.shuffle, version) {
        val timeline = player.currentTimeline
        buildList {
            var index = player.currentMediaItemIndex
            while (size < 100) {
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                if (index == androidx.media3.common.C.INDEX_UNSET) break
                Track.fromMediaItem(player.getMediaItemAt(index))?.let { add(index to it) }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        nowPlaying?.track?.let { current ->
            Text("Now playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(current.artworkUrl, Modifier.size(48.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(current.title, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            if (upNext.isEmpty()) "Nothing up next. Autoplay adds similar songs when the queue ends." else "Next up",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(upNext, key = { (index, track) -> "$index/${track.key}" }) { (index, track) ->
                Row(
                    Modifier.fillMaxWidth().clickable { player.seekTo(index, 0); onDismiss() }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(track.artworkUrl, Modifier.size(44.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { player.removeMediaItem(index); version++ }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove from queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val state by SleepTimer.state.collectAsState()
    val remaining by produceState("", state) {
        while (true) {
            value = when (val s = state) {
                is Sleep.At -> "Stops in ${formatTime(s.atMs - System.currentTimeMillis())}"
                Sleep.EndOfSong -> "Stops at the end of this song"
                Sleep.Off -> ""
            }
            delay(1000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("Sleep timer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        if (remaining.isNotEmpty()) {
            Text(remaining, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
            SheetText(if (minutes == 60) "1 hour" else "$minutes minutes") {
                SleepTimer.start(minutes)
                ui.toast("Music will stop in ${if (minutes == 60) "1 hour" else "$minutes minutes"}")
                onDismiss()
            }
        }
        SheetText("End of song") {
            SleepTimer.endOfSong()
            ui.toast("Music will stop at the end of this song")
            onDismiss()
        }
        if (state != Sleep.Off) {
            SheetText("Turn off timer") {
                SleepTimer.cancel()
                ui.toast("Sleep timer off")
                onDismiss()
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportMenuSheet(onPickFile: () -> Unit, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        SheetOption(Icons.Rounded.School, "Move playlists from Spotify", "Step-by-step guide · Recommended") {
            onDismiss()
            ui.guidePage = GUIDE_IMPORT_PAGE
        }
        SheetOption(Icons.AutoMirrored.Rounded.PlaylistAdd, "Create a playlist", "Start from scratch") {
            onDismiss()
            ui.createPlaylist = emptyList()
        }
        SheetOption(Icons.Rounded.FileOpen, "Import a file", "Exportify CSV or ZIP, Spotify data export, Songify playlist, M3U") {
            onDismiss()
            onPickFile()
        }
        SheetOption(Icons.Rounded.Link, "Import from a link", "Spotify (first 100 songs), Apple Music, other playlist links") {
            onDismiss()
            ui.linkDialog = true
        }
        SheetOption(Icons.Rounded.ContentPaste, "Paste a song list", "“Artist - Title” or song links, one per line") {
            onDismiss()
            ui.pasteDialog = true
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit) {
    val app = LocalAppViewModel.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(clipboard.getText()?.text?.let(PlaylistImport::findLink).orEmpty()) }
    val link = PlaylistImport.findLink(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from a link") },
        text = {
            Column {
                Text(
                    "Paste a playlist or album link from Spotify, Apple Music or another music service.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("https://open.spotify.com/playlist/…") },
                    singleLine = true,
                    isError = text.isNotBlank() && link == null,
                    supportingText = if (text.isNotBlank() && link == null) {
                        { Text("That doesn't look like a playlist link") }
                    } else null,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = link != null, onClick = {
                onDismiss()
                link?.let(app::importLink)
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit) {
    val app = LocalAppViewModel.current
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a song list") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Songs") },
                    placeholder = { Text("Artist - Title\nArtist - Title\n…") },
                    minLines = 6,
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                onDismiss()
                app.importText(name.ifBlank { "Imported songs" }, text)
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportProgress() {
    val ui = LocalUi.current
    val app = LocalAppViewModel.current
    when (val state = app.importState.collectAsState().value) {
        ImportState.Idle -> Unit
        is ImportState.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = { Text("Importing") },
            text = {
                Column {
                    Text(state.message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    if (state.total > 0) {
                        LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${state.done} of ${state.total} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = app::cancelImport) { Text("Cancel") } },
        )
        is ImportState.Done -> ImportReport(state, onOpen = { id ->
            app.dismissImport()
            ui.open(Routes.playlist(id))
        }, onClose = app::dismissImport)
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = app::dismissImport,
            title = { Text("Couldn't import") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = app::dismissImport) { Text("OK") } },
        )
    }
}

/** Shows exactly which songs made it and every song that didn't. */
@Composable
private fun ImportReport(state: ImportState.Done, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val missing = state.missing
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(state.title) },
        text = {
            Column {
                val single = state.playlists.singleOrNull()
                Text(
                    if (single != null) "“${single.name}”: ${state.added} songs added."
                    else "${state.added} songs added across ${state.playlists.size} playlists.",
                )
                if (state.truncated) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Spotify links only share the first 100 songs. For the whole playlist, export it with Exportify (see the guide in Settings) and import the file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (missing.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Couldn't find ${missing.size}:", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.heightIn(max = 220.dp).padding(top = 4.dp)) {
                        items(missing) {
                            Text(
                                "• $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "They're saved at the bottom of the playlist, where you can search for each one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            state.playlists.firstOrNull()?.let { first ->
                TextButton(onClick = { onOpen(first.id) }) { Text(if (state.playlists.size == 1) "Open playlist" else "Open first") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp))
    }
}

@Composable
private fun SheetText(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
private fun SheetOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIcon { Icon(icon, contentDescription = null) }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
