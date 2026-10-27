package com.songify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.songify.app.data.Audius
import com.songify.app.data.LIKED_ID
import com.songify.app.data.Library
import com.songify.app.data.LibraryData
import com.songify.app.data.MusicSearch
import com.songify.app.data.StreamResolver
import com.songify.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

data class Shelf(val title: String, val tracks: List<Track>)

class HomeViewModel : ViewModel() {
    private val _shelves = MutableStateFlow<List<Shelf>?>(null)
    val shelves = _shelves.asStateFlow()

    private var loadedFor: List<String>? = null

    /** Builds "More like <artist>" mixes from what the user actually plays. */
    fun load(library: LibraryData) {
        val artists = topArtists(library)
        if (artists == loadedFor) return
        loadedFor = artists
        viewModelScope.launch {
            val requests = buildList {
                if (artists.isEmpty()) add("Popular right now" to { MusicSearch.youtube("top hits") })
                artists.forEach { artist -> add("More like $artist" to { MusicSearch.youtube(artist) }) }
                add("Trending on Audius" to { Audius.trending() })
            }
            val shelves = requests.map { (title, fetch) ->
                async(Dispatchers.IO) { runCatching { Shelf(title, fetch()) }.getOrNull() }
            }.awaitAll().filterNotNull().filter { it.tracks.isNotEmpty() }
            _shelves.value = shelves
            shelves.firstOrNull()?.tracks?.firstOrNull()?.let { launch(Dispatchers.IO) { StreamResolver.prefetch(it.uri) } }
        }
    }

    private fun topArtists(library: LibraryData): List<String> =
        (library.recent + library.liked)
            .map { it.artist.split(',', '&').first().trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(3).map { it.key }
}

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val ui = LocalUi.current
    val player = LocalPlayer.current
    val library by Library.data.collectAsState()
    val shelves by vm.shelves.collectAsState()
    LaunchedEffect(library.recent.take(10).map { it.artist }, library.liked.size) { vm.load(library) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { ui.nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }
        }
        item { QuickGrid(library) }
        if (library.playlists.isEmpty() && library.liked.isEmpty()) item { WelcomeCard() }
        if (library.recent.isNotEmpty()) {
            item { TrackShelf("Recently played", library.recent.take(20)) { player?.playTracks(library.recent.take(20), it) } }
        }
        val loaded = shelves
        if (loaded == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        } else {
            items(loaded, key = { it.title }) { shelf ->
                TrackShelf(shelf.title, shelf.tracks) { player?.playTracks(shelf.tracks, it) }
            }
        }
    }
}

private sealed interface Tile {
    data class PlaylistTile(val id: String, val name: String, val tracks: List<Track>, val liked: Boolean) : Tile
    data class TrackTile(val track: Track) : Tile
}

/** Spotify-style 2-column grid of shortcuts: Liked Songs, playlists, then recent songs. */
@Composable
private fun QuickGrid(library: LibraryData) {
    val ui = LocalUi.current
    val player = LocalPlayer.current
    val tiles = buildList<Tile> {
        if (library.liked.isNotEmpty()) add(Tile.PlaylistTile(LIKED_ID, "Liked Songs", library.liked, true))
        library.playlists.sortedByDescending { it.updatedAt }.forEach { add(Tile.PlaylistTile(it.id, it.name, it.tracks, false)) }
        library.recent.forEach { add(Tile.TrackTile(it)) }
    }.take(6)
    if (tiles.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f).height(56.dp).clickable {
                            when (tile) {
                                is Tile.PlaylistTile -> ui.nav.navigate(Routes.playlist(tile.id))
                                is Tile.TrackTile -> player?.playTracks(listOf(tile.track))
                            }
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (tile) {
                                is Tile.PlaylistTile -> PlaylistCover(tile.tracks, Modifier.size(56.dp), tile.liked, corner = 0.dp)
                                is Tile.TrackTile -> Artwork(tile.track.artworkUrl, Modifier.size(56.dp), corner = 0.dp)
                            }
                            Text(
                                when (tile) {
                                    is Tile.PlaylistTile -> tile.name
                                    is Tile.TrackTile -> tile.track.title
                                },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp),
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun WelcomeCard() {
    val ui = LocalUi.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Bring your music", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Import playlists from Spotify or Apple Music, or start your own. Everything is saved on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { ui.importMenu = true }) { Text("Import or create a playlist") }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}
