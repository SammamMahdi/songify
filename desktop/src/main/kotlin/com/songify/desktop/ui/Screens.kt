package com.songify.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songify.app.data.Audius
import com.songify.app.data.AudioQuality
import com.songify.app.data.LIKED_ID
import com.songify.app.data.Library
import com.songify.app.data.LibraryData
import com.songify.app.data.MusicSearch
import com.songify.app.data.Playlist
import com.songify.app.data.PlaylistImport
import com.songify.app.data.Source
import com.songify.app.data.StreamResolver
import com.songify.app.data.Track
import com.songify.app.data.YouTubeMusic
import com.songify.desktop.APP_VERSION
import com.songify.desktop.AppDirs
import com.songify.desktop.DesktopDownloads
import com.songify.desktop.DesktopPlayer
import com.songify.desktop.DesktopSettings
import com.songify.desktop.Imports
import com.songify.desktop.Offline
import com.songify.desktop.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.util.Calendar

private val PagePadding = PaddingValues(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 32.dp)

private data class Shelf(val title: String, val tracks: List<Track>)

// Shelves survive navigating away and back; rebuilt only when listening taste changes.
private object HomeShelves {
    var artists: List<String>? = null
    var shelves: List<Shelf>? = null
}

@Composable
fun HomeScreen() {
    val ui = LocalUi.current
    val library by Library.data.collectAsState()
    val artists = remember(library.recent, library.liked) { topArtists(library) }
    val shelves by produceState(HomeShelves.shelves.takeIf { HomeShelves.artists == artists }, artists) {
        if (HomeShelves.artists == artists && HomeShelves.shelves != null) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            coroutineScope {
                val requests = buildList {
                    if (artists.isEmpty()) add("Popular right now" to { MusicSearch.youtube("top hits") })
                    artists.forEach { a -> add("More like $a" to { MusicSearch.youtube(a) }) }
                    add("Trending on Audius" to { Audius.trending() })
                }
                requests.map { (title, fetch) -> async { runCatching { Shelf(title, fetch()) }.getOrNull() } }
                    .awaitAll().filterNotNull().filter { it.tracks.isNotEmpty() }
            }
        }
        HomeShelves.artists = artists
        HomeShelves.shelves = loaded
        value = loaded
    }

    LazyColumn(contentPadding = PagePadding) {
        item { Text(greeting(), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 40.dp)) }
        item { QuickGrid(library) }
        if (library.playlists.isEmpty() && library.liked.isEmpty()) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 24.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Bring your music", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Import playlists from Spotify or Apple Music, or start your own. Everything is saved on this computer.",
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Button(onClick = { ui.guidePage = GUIDE_IMPORT_PAGE }) { Text("Show me how") }
                    }
                }
            }
        }
        if (library.recent.isNotEmpty()) item { CardShelf("Recently played", library.recent.take(20)) }
        val loaded = shelves
        if (loaded == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 32.dp)) }
        else items(loaded, key = { it.title }) { CardShelf(it.title, it.tracks) }
    }
}

private fun topArtists(library: LibraryData): List<String> =
    (library.recent + library.liked).map { it.artist.split(',', '&').first().trim() }.filter { it.isNotBlank() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }

private fun greeting() = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickGrid(library: LibraryData) {
    val ui = LocalUi.current
    data class Tile(val name: String, val tracks: List<Track>, val liked: Boolean, val open: () -> Unit)
    val tiles = buildList {
        if (library.liked.isNotEmpty()) add(Tile("Liked Songs", library.liked, true) { ui.go(Screen.PlaylistScreen(LIKED_ID)) })
        library.playlists.sortedByDescending { it.updatedAt }.forEach { p -> add(Tile(p.name, p.tracks, false) { ui.go(Screen.PlaylistScreen(p.id)) }) }
        library.recent.forEach { t -> add(Tile(t.title, listOf(t), false) { DesktopPlayer.playTracks(listOf(t)) }) }
    }.take(8)
    if (tiles.isEmpty()) return
    FlowRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 4) {
        tiles.forEach { tile ->
            Surface(
                onClick = tile.open,
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(6.dp),
                // A fixed quarter width keeps the grid even when the last row isn't full.
                modifier = Modifier.fillMaxWidth(0.235f).height(64.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaylistCover(tile.tracks, Modifier.size(64.dp), liked = tile.liked, corner = 0.dp)
                    Text(tile.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun CardShelf(title: String, tracks: List<Track>) {
    Column {
        SectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(tracks, key = { i, t -> "$i/${t.key}" }) { i, track ->
                Surface(
                    onClick = { DesktopPlayer.playTracks(tracks, i) },
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.width(176.dp),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Artwork(track.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 6.dp)
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
        }
    }
}

private val Genres = listOf(
    "Pop" to 0xFF8D67AB, "Hip-Hop" to 0xFFBA5D07, "Rock" to 0xFFE91429, "Lo-fi" to 0xFF477D95,
    "Bollywood" to 0xFFE8115B, "Bangla" to 0xFF148A08, "K-Pop" to 0xFFDC148C, "Chill" to 0xFF503750,
    "Workout" to 0xFF777777, "Party" to 0xFFAF2896, "Romantic" to 0xFFE1118C, "Indie" to 0xFF608108,
)

// The search text and filter survive leaving and returning to Search.
private object SearchMemory {
    var query by mutableStateOf("")
    var filter by mutableStateOf<Source?>(null)
}

private sealed interface SearchResult {
    data object Idle : SearchResult
    data object Loading : SearchResult
    data class Songs(val tracks: List<Track>) : SearchResult
    data class PlaylistLink(val link: String) : SearchResult
    data class Error(val message: String) : SearchResult
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen() {
    val ui = LocalUi.current
    val prefs by DesktopSettings.prefs.collectAsState()
    val query = SearchMemory.query
    val result by produceState<SearchResult>(SearchResult.Idle, query) {
        val q = query.trim()
        if (q.isEmpty()) { value = SearchResult.Idle; return@produceState }
        delay(300) // wait for typing to pause
        value = SearchResult.Loading
        val pastedSong = YouTubeMusic.videoId(q)
        val playlistLink = PlaylistImport.findLink(q)
        value = when {
            pastedSong != null -> withContext(Dispatchers.IO) { runCatching { YouTubeMusic.track(pastedSong) }.getOrNull() }
                ?.let { SearchResult.Songs(listOf(it)) } ?: SearchResult.Error("Couldn't open that song link")
            playlistLink != null -> SearchResult.PlaylistLink(playlistLink)
            else -> withContext(Dispatchers.IO) {
                coroutineScope {
                    val yt = async { runCatching { MusicSearch.youtube(q) }.getOrNull() }
                    val au = async { runCatching { MusicSearch.audius(q) }.getOrNull() }
                    val main = yt.await()
                    val extra = au.await()
                    if (main == null && extra == null) SearchResult.Error("Couldn't reach the music sources. Check your connection.")
                    else {
                        val seen = main.orEmpty().map(MusicSearch::dedupeKey).toSet()
                        val merged = main.orEmpty() + extra.orEmpty().filter { MusicSearch.dedupeKey(it) !in seen }
                        merged.take(2).forEach { StreamResolver.prefetch(it.uri) }
                        SearchResult.Songs(merged)
                    }
                }
            }
        }
    }

    LazyColumn(contentPadding = PagePadding) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { SearchMemory.query = it },
                placeholder = { Text("What do you want to listen to?") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { SearchMemory.query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(top = 40.dp).width(480.dp),
            )
        }
        when (val r = result) {
            SearchResult.Idle -> {
                if (prefs.recentSearches.isNotEmpty()) {
                    item {
                        SectionTitle("Recent searches") {
                            TextButton(onClick = { DesktopSettings.update { it.copy(recentSearches = emptyList()) } }) { Text("Clear") }
                        }
                    }
                    items(prefs.recentSearches, key = { "recent/$it" }) { q ->
                        Row(Modifier.fillMaxWidth().clickable { SearchMemory.query = q }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, tint = TextSecondary)
                            Text(q, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
                item {
                    Text(
                        "Tip: paste a song link to play it, or a playlist link to import it.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                item { SectionTitle("Browse all") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Genres.forEach { (name, color) ->
                            Box(
                                Modifier.size(200.dp, 112.dp).clip(RoundedCornerShape(8.dp)).background(Color(color))
                                    .clickable { SearchMemory.query = "$name songs" }.padding(16.dp),
                            ) { Text(name, style = MaterialTheme.typography.headlineSmall) }
                        }
                    }
                }
            }
            SearchResult.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 24.dp)) }
            is SearchResult.Error -> item { EmptyMessage(r.message) }
            is SearchResult.PlaylistLink -> item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 24.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        Text("${PlaylistImport.linkSourceName(r.link)} link", style = MaterialTheme.typography.titleLarge)
                        Text("Import it as a playlist. Songify finds every song and saves the playlist on this computer.", color = TextSecondary, modifier = Modifier.padding(vertical = 8.dp))
                        Button(onClick = { Imports.link(r.link) }) { Text("Import playlist") }
                    }
                }
            }
            is SearchResult.Songs -> {
                val filter = SearchMemory.filter
                val shown = filter?.let { s -> r.tracks.filter { it.source == s } } ?: r.tracks
                item {
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "All", Source.YOUTUBE to "Songify", Source.AUDIUS to "Audius").forEach { (source, label) ->
                            FilterChip(
                                selected = filter == source,
                                onClick = { SearchMemory.filter = source },
                                label = { Text(label) },
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
                if (shown.isEmpty()) item { EmptyMessage("No full-length songs found for “${query.trim()}”") }
                item { SectionTitle("Songs") }
                itemsIndexed(shown, key = { i, t -> "s/$i/${t.key}" }) { i, track ->
                    TrackRow(track, i + 1, onPlay = {
                        DesktopSettings.addRecentSearch(query)
                        DesktopPlayer.playTracks(shown, i)
                    })
                }
            }
        }
    }
}

private enum class TrackSort(val label: String) { CUSTOM("Custom order"), TITLE("Title"), ARTIST("Artist") }

@Composable
fun PlaylistScreen(id: String) {
    val ui = LocalUi.current
    val library by Library.data.collectAsState()
    val downloaded by DesktopDownloads.downloaded.collectAsState()
    val downloads by DesktopDownloads.states.collectAsState()
    val player by DesktopPlayer.state.collectAsState()
    val playlist = if (id == DOWNLOADS_ID) Playlist(DOWNLOADS_ID, "Downloaded", downloaded) else library.playlist(id)
    if (playlist == null) {
        Box(Modifier.padding(PagePadding)) { EmptyMessage("This playlist was deleted.") }
        return
    }
    val liked = id == LIKED_ID
    val builtIn = liked || id == DOWNLOADS_ID
    var filter by remember(id) { mutableStateOf("") }
    var sort by remember(id) { mutableStateOf(TrackSort.CUSTOM) }
    var menu by remember { mutableStateOf(false) }
    val shown = playlist.tracks
        .let { all -> when (sort) { TrackSort.CUSTOM -> all; TrackSort.TITLE -> all.sortedBy { it.title.lowercase() }; TrackSort.ARTIST -> all.sortedBy { it.artist.lowercase() } } }
        .let { all -> filter.trim().lowercase().let { q -> if (q.isEmpty()) all else all.filter { q in it.title.lowercase() || q in it.artist.lowercase() } } }
    val playingHere = player.current?.let { c -> playlist.tracks.any { it.key == c.key } } == true
    val done = playlist.tracks.count { downloads[it.key] == Offline.Done }
    val accent = MaterialTheme.colorScheme.primary

    LazyColumn {
        item {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(lerp(if (liked) Color(0xFF4A2FBD) else accent, Color.Black, 0.55f), MaterialTheme.colorScheme.surface)))
                    .padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 24.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                PlaylistCover(playlist.tracks, Modifier.size(200.dp), liked = liked, corner = 6.dp)
                Column(Modifier.padding(start = 24.dp)) {
                    Text("Playlist", style = MaterialTheme.typography.labelLarge)
                    Text(playlist.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatTotal(playlist.tracks) + if (done in 1 until playlist.tracks.size) " · $done downloaded" else "",
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 32.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayCircle(playing = playingHere && player.isPlaying) {
                    if (playingHere) DesktopPlayer.togglePlay() else DesktopPlayer.playTracks(shown)
                }
                IconButton(onClick = { DesktopPlayer.playTracks(playlist.tracks, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, "Shuffle play", Modifier.size(28.dp)) }
                IconButton(onClick = {
                    if (playlist.tracks.isNotEmpty() && done == playlist.tracks.size) {
                        DesktopDownloads.remove(playlist.tracks); ui.toast("Downloads removed")
                    } else {
                        val n = DesktopDownloads.download(playlist.tracks)
                        ui.toast(if (n == 0) "Already downloading" else "Downloading $n songs for offline play")
                    }
                }) {
                    if (playlist.tracks.isNotEmpty() && done == playlist.tracks.size) Icon(Icons.Rounded.DownloadDone, "Remove downloads", tint = accent, modifier = Modifier.size(28.dp))
                    else Icon(Icons.Rounded.DownloadForOffline, "Download for offline", Modifier.size(28.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "More", Modifier.size(28.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Export playlist file…") }, onClick = { menu = false; exportPlaylist(playlist, ui) })
                        DropdownMenuItem(text = { Text("Copy as text list") }, onClick = { menu = false; copyPlaylistText(playlist, ui) })
                        TrackSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text("Sort: ${s.label}", color = if (sort == s) accent else Color.White) }, onClick = { menu = false; sort = s })
                        }
                        if (!builtIn) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; ui.renaming = playlist })
                            DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; ui.deleting = playlist })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Find in playlist") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    modifier = Modifier.width(260.dp),
                )
            }
        }
        if (playlist.tracks.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 32.dp)) {
                    EmptyMessage(if (liked) "Songs you like will appear here." else "Let's find something for your playlist.")
                    Button(onClick = { ui.go(Screen.Search) }) { Text("Find songs") }
                }
            }
        }
        itemsIndexed(shown, key = { i, t -> "$i/${t.key}" }) { i, track ->
            Box(Modifier.padding(horizontal = 20.dp)) {
                TrackRow(track, i + 1, onPlay = { DesktopPlayer.playTracks(shown, i) }, playlistId = playlist.id.takeIf { !builtIn || liked })
            }
        }
        if (playlist.missing.isNotEmpty()) {
            item {
                Row(Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, null, tint = TextSecondary)
                    Text("Couldn't find ${playlist.missing.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    TextButton(onClick = { Imports.retryMissing(playlist.id) }) { Text("Try again") }
                }
                Text("Click Find to pick the right version yourself, or paste a link to the song.", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 32.dp))
            }
            items(playlist.missing, key = { "missing/${it.title}/${it.artist}" }) { wanted ->
                Row(Modifier.padding(horizontal = 32.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(wanted.title, color = TextSecondary)
                        if (wanted.artist.isNotBlank()) Text(wanted.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    OutlinedButton(onClick = { ui.replace = Replace(playlist.id, wanted) }) { Text("Find") }
                    IconButton(onClick = { Library.resolveMissing(playlist.id, wanted, null) }) { Icon(Icons.Rounded.Close, "Dismiss", tint = MaterialTheme.colorScheme.outline) }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
fun ArtistScreen(name: String) {
    val player by DesktopPlayer.state.collectAsState()
    val songs by produceState<List<Track>?>(null, name) {
        value = withContext(Dispatchers.IO) {
            runCatching { MusicSearch.youtube(name) }.getOrDefault(emptyList()).sortedByDescending { name.lowercase() in it.artist.lowercase() }
        }
    }
    val playingHere = player.current?.let { c -> songs?.any { it.key == c.key } } == true
    LazyColumn {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.6f), MaterialTheme.colorScheme.surface)))
                    .padding(start = 32.dp, end = 32.dp, top = 96.dp, bottom = 24.dp),
            ) {
                Text("Artist", style = MaterialTheme.typography.labelLarge)
                Text(name, style = MaterialTheme.typography.displaySmall)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 32.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayCircle(playing = playingHere && player.isPlaying) {
                    if (playingHere) DesktopPlayer.togglePlay() else songs?.let { DesktopPlayer.playTracks(it) }
                }
                IconButton(onClick = { songs?.let { DesktopPlayer.playTracks(it, shuffle = true) } }) { Icon(Icons.Rounded.Shuffle, "Shuffle play", Modifier.size(28.dp)) }
            }
        }
        val list = songs
        if (list == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(32.dp)) }
        else {
            item { Text("Popular", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
            itemsIndexed(list, key = { i, t -> "$i/${t.key}" }) { i, track ->
                Box(Modifier.padding(horizontal = 20.dp)) { TrackRow(track, i + 1, onPlay = { DesktopPlayer.playTracks(list, i) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ui = LocalUi.current
    val prefs by DesktopSettings.prefs.collectAsState()
    val downloaded by DesktopDownloads.downloaded.collectAsState()
    LazyColumn(contentPadding = PagePadding) {
        item { Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 40.dp)) }
        item { SectionTitle("Accent color") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AccentPresets.forEach { preset ->
                    val selected = preset.argb == prefs.accent
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(preset.argb))
                            .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                            .clickable { DesktopSettings.update { it.copy(accent = preset.argb) } },
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Icons.Rounded.Check, preset.name, tint = Color.Black) }
                }
            }
        }
        item {
            Text("Or pick any color", color = TextSecondary, modifier = Modifier.padding(top = 16.dp))
            val hsv = FloatArray(3).also { java.awt.Color.RGBtoHSB((prefs.accent shr 16) and 0xFF, (prefs.accent shr 8) and 0xFF, prefs.accent and 0xFF, it) }
            Box(Modifier.width(420.dp).padding(top = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.75f, 0.95f) })))
                Slider(
                    value = hsv[0] * 359f,
                    onValueChange = { h -> DesktopSettings.update { it.copy(accent = Color.hsv(h, 0.75f, 0.95f).toArgb()) } },
                    valueRange = 0f..359f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                )
            }
        }
        item {
            SettingRow("Pure black background", "Darker look for OLED screens") {
                Switch(checked = prefs.amoled, onCheckedChange = { v -> DesktopSettings.update { it.copy(amoled = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
            }
        }
        item { SectionTitle("Playback") }
        item {
            SettingRow("Autoplay", "Keep playing similar songs when your music ends") {
                Switch(checked = prefs.autoplay, onCheckedChange = { v -> DesktopSettings.update { it.copy(autoplay = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
            }
        }
        item { Text("Audio quality", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        items(AudioQuality.entries) { option ->
            Row(Modifier.clickable { DesktopSettings.update { it.copy(quality = option) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = prefs.quality == option, onClick = { DesktopSettings.update { it.copy(quality = option) } })
                Column {
                    Text(option.label)
                    Text(if (option == AudioQuality.AUTO) "Best available on this computer" else option.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
        item { SectionTitle("Downloads & updates") }
        item {
            SettingRow("Downloaded songs", "${downloaded.size} songs · ${DesktopDownloads.bytesUsed() / (1024 * 1024)} MB on this computer") {
                TextButton(onClick = { runCatching { Desktop.getDesktop().open(AppDirs.downloads) } }) { Text("Open folder") }
                TextButton(enabled = downloaded.isNotEmpty(), onClick = { DesktopDownloads.remove(downloaded); ui.toast("Downloads removed") }) { Text("Remove all") }
            }
        }
        item {
            SettingRow("Songify $APP_VERSION", "Updates are checked automatically") {
                TextButton(onClick = { Updates.check(manual = true) }) { Text("Check now") }
            }
        }
        item { SectionTitle("Help") }
        item {
            SettingRow("Beginner's guide", "Import from Spotify, fix missing songs, share playlists") {
                TextButton(onClick = { ui.guidePage = 0 }) { Text("Open") }
            }
        }
        item {
            SettingRow("History", "Clear recently played and recent searches") {
                TextButton(onClick = {
                    Library.clearRecent()
                    DesktopSettings.update { it.copy(recentSearches = emptyList()) }
                    ui.toast("History cleared")
                }) { Text("Clear") }
            }
        }
        item {
            Text(
                "Keyboard: Space play/pause · Ctrl+→ next · Ctrl+← previous",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(Modifier.width(720.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        action()
    }
}
