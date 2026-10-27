package com.songify.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.songify.app.data.AppJson
import com.songify.app.data.Library
import com.songify.app.data.MusicSearch
import com.songify.app.data.Playlist
import com.songify.app.data.PlaylistImport
import com.songify.app.data.SharedPlaylist
import com.songify.app.data.Track
import com.songify.app.data.YouTubeMusic
import com.songify.desktop.APP_VERSION
import com.songify.desktop.DesktopPlayer
import com.songify.desktop.DesktopSettings
import com.songify.desktop.ImportState
import com.songify.desktop.Imports
import com.songify.desktop.UpdateState
import com.songify.desktop.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

const val GUIDE_IMPORT_PAGE = 1

/** Hosts every app-wide dialog; screens open them through [DesktopUi]. */
@Composable
fun Dialogs(crashReport: String?) {
    val ui = LocalUi.current
    var crash by remember { mutableStateOf(crashReport) }

    ui.guidePage?.let { GuideDialog(it) { ui.guidePage = null } }
    ui.importTab?.let { tab -> ImportDialog(tab) { ui.importTab = null } }
    ui.addToPlaylist?.let { AddToPlaylistDialog(it) { ui.addToPlaylist = null } }
    ui.createPlaylist?.let { tracks ->
        val count = Library.data.collectAsState().value.playlists.size
        NameDialog("Give your playlist a name", "My playlist #${count + 1}", "Create", onDismiss = { ui.createPlaylist = null }) { name ->
            ui.createPlaylist = null
            val playlist = Library.createPlaylist(name, tracks)
            if (tracks.isEmpty()) ui.go(Screen.PlaylistScreen(playlist.id)) else ui.toast("Added to ${playlist.name}")
        }
    }
    ui.renaming?.let { p ->
        NameDialog("Rename playlist", p.name, "Save", onDismiss = { ui.renaming = null }) {
            Library.renamePlaylist(p.id, it)
            ui.renaming = null
        }
    }
    ui.deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { ui.deleting = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("This removes the playlist from this computer. Export it first if you want to keep a copy.") },
            confirmButton = {
                TextButton(onClick = {
                    ui.deleting = null
                    ui.back()
                    Library.deletePlaylist(p.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { ui.deleting = null }) { Text("Cancel") } },
        )
    }
    ui.replace?.let { ReplaceDialog(it) { ui.replace = null } }
    ImportProgress()
    UpdateDialog()
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { crash = null },
            title = { Text("Songify closed unexpectedly") },
            text = { Text("Sorry about that. Copying the error report and sending it helps get it fixed.") },
            confirmButton = {
                TextButton(onClick = {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(report), null)
                    crash = null
                    ui.toast("Error report copied")
                }) { Text("Copy report") }
            },
            dismissButton = { TextButton(onClick = { crash = null }) { Text("Dismiss") } },
        )
    }
}

private data class GuidePage(val title: String, val body: String, val steps: List<String> = emptyList(), val recommended: Boolean = false)

private val GuidePages = listOf(
    GuidePage("Welcome to Songify", "Full songs, streamed for free. No account needed: your playlists, likes and history stay on this computer."),
    GuidePage(
        "Bring your Spotify playlists",
        "The easiest way to move whole playlists, however long they are:",
        listOf(
            "Click Open Exportify below and log in with Spotify.",
            "Click Export next to a playlist (or Export All for everything).",
            "In Songify, click + next to Your Library → Import a file, and pick the download.",
        ),
        recommended = true,
    ),
    GuidePage(
        "Fix songs that weren't found",
        "After an import, any song Songify couldn't match waits at the bottom of the playlist.",
        listOf("Click Find next to the song.", "Search for it, or paste a link to the exact song.", "Click the right result and it's added."),
    ),
    GuidePage(
        "Share playlists with friends",
        "Playlists live on your computer, so sharing is just a file.",
        listOf("Open a playlist, click ⋯ → Export playlist file.", "Send the file to a friend.", "They import it in Songify on their phone or computer."),
    ),
)

@Composable
private fun GuideDialog(start: Int, onClose: () -> Unit) {
    val ui = LocalUi.current
    var page by remember { mutableStateOf(start.coerceIn(0, GuidePages.lastIndex)) }
    val finish = {
        DesktopSettings.update { it.copy(guideSeen = true) }
        onClose()
    }
    val p = GuidePages[page]
    AlertDialog(
        onDismissRequest = finish,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(560.dp),
        title = {
            Column {
                Text("Beginner's guide · ${page + 1} of ${GuidePages.size}", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                if (p.recommended) Text("RECOMMENDED", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Text(p.title, style = MaterialTheme.typography.headlineSmall)
            }
        },
        text = {
            Column {
                Text(p.body, color = TextSecondary)
                p.steps.forEachIndexed { i, step ->
                    Row(Modifier.padding(top = 12.dp)) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelLarge)
                        }
                        Text(step, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (page == GUIDE_IMPORT_PAGE) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { runCatching { Desktop.getDesktop().browse(URI("https://exportify.app")) } }) { Text("Open Exportify") }
                        OutlinedButton(onClick = { finish(); ui.importTab = ImportTab.FILE }) { Text("Import a file") }
                    }
                    Text(
                        "Other ways: paste a Spotify playlist link (first 100 songs only), or request your data from Spotify " +
                            "(Account → Privacy settings → Download your data) and import Playlist1.json.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (page < GuidePages.lastIndex) TextButton(onClick = { page++ }) { Text("Next") }
            else TextButton(onClick = finish) { Text("Start listening") }
        },
        dismissButton = { TextButton(onClick = finish) { Text("Skip") } },
    )
}

@Composable
private fun ImportDialog(tab: ImportTab, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    when (tab) {
        ImportTab.FILE -> LaunchedEffect(Unit) {
            // The native file picker is modal; open it straight away.
            val dialog = FileDialog(null as Frame?, "Import a playlist file", FileDialog.LOAD).apply {
                file = "*.csv;*.zip;*.json;*.m3u;*.m3u8;*.txt"
                isVisible = true
            }
            onClose()
            dialog.file?.let { Imports.file(File(dialog.directory, it)) }
        }
        ImportTab.LINK -> {
            var text by remember { mutableStateOf(clipboard.getText()?.text?.let(PlaylistImport::findLink).orEmpty()) }
            val link = PlaylistImport.findLink(text)
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Import from a link") },
                text = {
                    Column {
                        Text("Paste a playlist or album link from Spotify, Apple Music or another music service.", color = TextSecondary)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { Text("https://open.spotify.com/playlist/…") },
                            singleLine = true,
                            isError = text.isNotBlank() && link == null,
                            modifier = Modifier.padding(top = 12.dp).width(460.dp),
                        )
                    }
                },
                confirmButton = { TextButton(enabled = link != null, onClick = { onClose(); link?.let(Imports::link) }) { Text("Import") } },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            )
        }
        ImportTab.PASTE -> {
            var name by remember { mutableStateOf("") }
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Paste a song list") },
                text = {
                    Column(Modifier.width(460.dp)) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Songs") },
                            placeholder = { Text("Artist - Title, or song links, one per line") },
                            minLines = 8,
                            maxLines = 14,
                            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onClose(); Imports.text(name.ifBlank { "Imported songs" }, text) }) { Text("Import") } },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ImportProgress() {
    val ui = LocalUi.current
    when (val s = Imports.state.collectAsState().value) {
        ImportState.Idle -> Unit
        is ImportState.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = { Text("Importing") },
            text = {
                Column(Modifier.width(420.dp)) {
                    Text(s.message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    if (s.total > 0) {
                        LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                        Text("${s.done} of ${s.total} songs", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = Imports::cancel) { Text("Cancel") } },
        )
        is ImportState.Done -> AlertDialog(
            onDismissRequest = Imports::dismiss,
            title = { Text(s.title) },
            text = {
                Column(Modifier.width(460.dp)) {
                    val single = s.playlists.singleOrNull()
                    Text(if (single != null) "“${single.name}”: ${s.added} songs added." else "${s.added} songs added across ${s.playlists.size} playlists.")
                    if (s.truncated) {
                        Text(
                            "Spotify links only share the first 100 songs. For the whole playlist, export it with Exportify and import the file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (s.missing.isNotEmpty()) {
                        Text("Couldn't find ${s.missing.size}:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                        LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 4.dp)) {
                            items(s.missing) { Text("• $it", style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        Text("They're saved at the bottom of the playlist, where you can find each one.", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                s.playlists.firstOrNull()?.let { first ->
                    TextButton(onClick = { Imports.dismiss(); ui.go(Screen.PlaylistScreen(first.id)) }) { Text("Open playlist") }
                }
            },
            dismissButton = { TextButton(onClick = Imports::dismiss) { Text("Close") } },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = Imports::dismiss,
            title = { Text("Couldn't import") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = Imports::dismiss) { Text("OK") } },
        )
    }
}

@Composable
private fun AddToPlaylistDialog(tracks: List<Track>, onClose: () -> Unit) {
    val ui = LocalUi.current
    val playlists = Library.data.collectAsState().value.playlists.sortedByDescending { it.updatedAt }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.width(400.dp).heightIn(max = 420.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { onClose(); ui.createPlaylist = tracks }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null)
                        Text("New playlist", modifier = Modifier.padding(start = 12.dp))
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val added = Library.addToPlaylist(p.id, tracks)
                            ui.toast(if (added == 0) "Already in ${p.name}" else "Added to ${p.name}")
                            onClose()
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCover(p.tracks, Modifier.size(40.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.tracks.size} songs", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Lets the user pick the right recording for a song the import couldn't match. */
@Composable
private fun ReplaceDialog(replace: Replace, onClose: () -> Unit) {
    val ui = LocalUi.current
    val wanted = replace.wanted
    var query by remember { mutableStateOf(listOf(wanted.title, wanted.artist).filter { it.isNotBlank() }.joinToString(" ")) }
    val results by produceState<List<Track>?>(null, query) {
        value = null
        delay(350)
        value = withContext(Dispatchers.IO) {
            val q = query.trim()
            val pasted = YouTubeMusic.videoId(q)
            when {
                q.isEmpty() -> emptyList()
                pasted != null -> listOfNotNull(runCatching { YouTubeMusic.track(pasted) }.getOrNull())
                else -> (runCatching { MusicSearch.youtube(q) }.getOrDefault(emptyList()) + runCatching { MusicSearch.audius(q) }.getOrDefault(emptyList())).distinctBy { it.key }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Find the right song") },
        text = {
            Column(Modifier.width(520.dp)) {
                Text("For “$wanted”. Search, or paste a link to the song. Click a result to add it, or ▶ to preview.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                val list = results
                if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (list.isEmpty()) Text("No results. Try fewer words, or paste a link to the song.", color = TextSecondary)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list.orEmpty(), key = { it.key }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                Library.resolveMissing(replace.playlistId, wanted, t)
                                ui.toast("Added ${t.title}")
                                onClose()
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(t.artworkUrl, Modifier.size(40.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.artist} · ${formatTime(t.durationSec * 1000)} · ${t.source.label}", style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1)
                            }
                            IconButton(onClick = { DesktopPlayer.playTracks(listOf(t)) }) { Icon(Icons.Rounded.PlayArrow, "Preview") }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun UpdateDialog() {
    when (val s = Updates.state.collectAsState().value) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = Updates::dismiss,
            title = { Text("Checking for updates") },
            text = { LinearProgressIndicator(Modifier.width(360.dp)) },
            confirmButton = {},
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = Updates::dismiss,
            title = { Text("Songify ${s.release.version} is available") },
            text = {
                Column(Modifier.width(460.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("You have $APP_VERSION. Your playlists and downloads are kept. Songify closes while the installer runs.")
                    if (s.release.notes.isNotBlank()) {
                        Text(s.release.notes.replace("**", "").replace("## ", ""), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { Updates.install(s.release) }) { Text("Update now") } },
            dismissButton = { TextButton(onClick = Updates::dismiss) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = { LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.width(360.dp)) },
            confirmButton = {},
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = Updates::dismiss,
            title = { Text("Updates") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = Updates::dismiss) { Text("OK") } },
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.width(360.dp)) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Saves a .songify.json that Songify on any device can import. */
fun exportPlaylist(playlist: Playlist, ui: DesktopUi) {
    val safe = playlist.name.replace(Regex("""[^\p{L}\p{N} _-]"""), "").trim().ifEmpty { "playlist" }
    val dialog = FileDialog(null as Frame?, "Export playlist", FileDialog.SAVE).apply {
        file = "$safe.songify.json"
        isVisible = true
    }
    val name = dialog.file ?: return
    val file = File(dialog.directory, if (name.endsWith(".json")) name else "$name.songify.json")
    runCatching {
        file.writeText(AppJson.encodeToString(SharedPlaylist.serializer(), SharedPlaylist(name = playlist.name, tracks = playlist.tracks)))
    }.onSuccess { ui.toast("Saved ${file.name}") }.onFailure { ui.toast("Couldn't save the file") }
}

fun copyPlaylistText(playlist: Playlist, ui: DesktopUi) {
    val text = buildString {
        appendLine(playlist.name)
        appendLine()
        playlist.tracks.forEach { appendLine("${it.artist} - ${it.title}") }
    }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    ui.toast("Song list copied")
}
