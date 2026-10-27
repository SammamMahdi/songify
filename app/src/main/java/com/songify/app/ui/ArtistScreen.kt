package com.songify.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songify.app.data.MusicSearch
import com.songify.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An artist's popular songs, with play and shuffle. */
@Composable
fun ArtistScreen(name: String) {
    val ui = LocalUi.current
    val player = LocalPlayer.current
    val songs by produceState<List<Track>?>(null, name) {
        value = withContext(Dispatchers.IO) {
            runCatching { MusicSearch.youtube(name) }.getOrDefault(emptyList())
                // The artist's own songs first, then features and covers.
                .sortedByDescending { name.lowercase() in it.artist.lowercase() }
        }
    }
    val base = rememberArtworkColor(songs?.firstOrNull()?.artworkUrl)
    val playingHere = LocalCurrentKey.current?.let { key -> songs?.any { it.key == key } } == true

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(lerp(base, Color.Black, 0.15f), MaterialTheme.colorScheme.background)))
                    .statusBarsPadding(),
            ) {
                IconButton(onClick = { ui.nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.height(48.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.headlineLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        songs?.let { "${it.size} popular songs" } ?: "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { songs?.let { player?.playTracks(it, shuffle = true) } }) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle play", modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    PlayCircle(playing = playingHere && LocalIsPlaying.current, onClick = {
                        if (playingHere) player?.togglePlay() else songs?.let { player?.playTracks(it) }
                    })
                }
            }
        }
        val list = songs
        if (list == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        } else {
            if (list.isEmpty()) item { EmptyMessage("No songs found for $name") }
            item { SectionTitle("Popular") }
            itemsIndexed(list, key = { i, t -> "$i/${t.key}" }) { i, track ->
                TrackRow(track, onClick = { player?.playTracks(list, i) }, onMore = { ui.trackMenu = TrackMenu(track) })
            }
        }
    }
}
