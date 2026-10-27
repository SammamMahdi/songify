package com.songify.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.songify.app.data.MusicSearch
import com.songify.app.data.PlaylistImport
import com.songify.app.data.Settings
import com.songify.app.data.Source
import com.songify.app.data.StreamResolver
import com.songify.app.data.Track
import com.songify.app.data.YouTubeMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchFilter(val label: String, val source: Source?) {
    ALL("All", null), YOUTUBE("Songify", Source.YOUTUBE), AUDIUS("Audius", Source.AUDIUS)
}

data class SearchUi(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Track> = emptyList(),
    val error: String? = null,
    val link: String? = null,
    val filter: SearchFilter = SearchFilter.ALL,
) {
    val shown: List<Track> get() = filter.source?.let { s -> results.filter { it.source == s } } ?: results
}

@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi())
    val ui = _ui.asStateFlow()

    init {
        // Search as you type; a new keystroke cancels the previous search.
        viewModelScope.launch {
            _ui.map { it.query.trim() }.distinctUntilChanged().debounce(300).collectLatest(::search)
        }
    }

    fun setQuery(query: String) = _ui.update { it.copy(query = query) }

    fun setFilter(filter: SearchFilter) = _ui.update { it.copy(filter = filter) }

    private suspend fun search(q: String) {
        if (q.isEmpty()) {
            _ui.update { it.copy(loading = false, results = emptyList(), error = null, link = null) }
            return
        }
        // A pasted song link plays that exact song (checked before playlists: song links often carry a list id).
        YouTubeMusic.videoId(q)?.let { id ->
            _ui.update { it.copy(loading = true, results = emptyList(), error = null, link = null) }
            val track = withContext(Dispatchers.IO) { runCatching { YouTubeMusic.track(id) }.getOrNull() }
            _ui.update {
                it.copy(loading = false, results = listOfNotNull(track), error = if (track == null) "Couldn't open that song link" else null)
            }
            return
        }
        PlaylistImport.findLink(q)?.let { link ->
            _ui.update { it.copy(loading = false, results = emptyList(), error = null, link = link) }
            return
        }
        _ui.update { it.copy(loading = true, error = null, link = null) }
        coroutineScope {
            val youtube = async(Dispatchers.IO) { runCatching { MusicSearch.youtube(q) } }
            val audius = async(Dispatchers.IO) { runCatching { MusicSearch.audius(q) } }
            // Show the main catalog as soon as it lands; Audius fills in after.
            val yt = youtube.await().getOrNull()
            _ui.update { it.copy(results = yt.orEmpty()) }
            val au = audius.await().getOrNull()
            val seen = yt.orEmpty().map(MusicSearch::dedupeKey).toSet()
            val merged = yt.orEmpty() + au.orEmpty().filter { MusicSearch.dedupeKey(it) !in seen }
            val error = if (yt == null && au == null) "Couldn't reach the music sources. Check your connection." else null
            _ui.update { it.copy(loading = false, results = merged, error = error) }
            merged.take(2).forEach { launch(Dispatchers.IO) { StreamResolver.prefetch(it.uri) } }
        }
    }
}

private val Genres = listOf(
    "Pop" to 0xFF8D67AB, "Hip-Hop" to 0xFFBA5D07, "Rock" to 0xFFE91429, "Lo-fi" to 0xFF477D95,
    "Bollywood" to 0xFFE8115B, "Bangla" to 0xFF148A08, "K-Pop" to 0xFFDC148C, "Chill" to 0xFF503750,
    "Workout" to 0xFF777777, "Party" to 0xFFAF2896, "Romantic" to 0xFFE1118C, "Indie" to 0xFF608108,
)

@Composable
fun SearchScreen(initialQuery: String? = null, vm: SearchViewModel = viewModel()) {
    val ui = LocalUi.current
    val player = LocalPlayer.current
    val state by vm.ui.collectAsState()
    LaunchedEffect(initialQuery) { initialQuery?.let(vm::setQuery) }
    val recent by Settings.recentSearches.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    fun play(tracks: List<Track>, index: Int) {
        Settings.addRecentSearch(state.query)
        keyboard?.hide()
        player?.playTracks(tracks, index)
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Text("Search", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                SearchBox(state.query, vm::setQuery, onSubmit = {
                    Settings.addRecentSearch(state.query)
                    keyboard?.hide()
                })
            }
        }

        when {
            state.query.isBlank() -> {
                if (recent.isNotEmpty()) {
                    item {
                        SectionTitle("Recent searches") {
                            TextButton(onClick = Settings::clearRecentSearches) { Text("Clear") }
                        }
                    }
                    items(recent, key = { "recent/$it" }) { q ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setQuery(q) }.padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(q, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
                            IconButton(onClick = { Settings.removeRecentSearch(q) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Tip: paste a song link to play it, or a playlist link to import it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                }
                item { SectionTitle("Browse all") }
                items(Genres.chunked(2)) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (name, color) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(96.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(color))
                                    .clickable { vm.setQuery("$name songs") }
                                    .padding(12.dp),
                            ) {
                                Text(name, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }

            state.link != null -> item { LinkImportCard(state.link!!) }

            else -> {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchFilter.entries.forEach { f ->
                            FilterChip(
                                selected = state.filter == f,
                                onClick = { vm.setFilter(f) },
                                label = { Text(f.label) },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                border = null,
                            )
                        }
                    }
                }
                if (state.loading && state.results.isEmpty()) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                }
                state.error?.let { error -> item { EmptyMessage(error) } }
                val shown = state.shown
                if (!state.loading && state.error == null && shown.isEmpty()) {
                    item { EmptyMessage("No full-length songs found for “${state.query.trim()}”") }
                }
                shown.firstOrNull()?.let { top ->
                    item(key = "top/${top.key}") { TopResult(top) { play(shown, 0) } }
                }
                if (shown.size > 1) item { SectionTitle("Songs") }
                itemsIndexed(shown.drop(1), key = { i, t -> "song/$i/${t.key}" }) { i, track ->
                    TrackRow(track, onClick = { play(shown, i + 1) }, onMore = { ui.trackMenu = TrackMenu(track) })
                }
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF121212))
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (query.isEmpty()) {
                Text("What do you want to listen to?", color = Color(0xFF6A6A6A), fontSize = 16.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF121212), fontSize = 16.sp),
                cursorBrush = SolidColor(Color(0xFF121212)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = Color(0xFF121212))
            }
        }
    }
}

@Composable
private fun TopResult(track: Track, onPlay: () -> Unit) {
    val ui = LocalUi.current
    Column {
        SectionTitle("Top result")
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = onPlay,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.artworkUrl, Modifier.size(92.dp), corner = 6.dp)
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Song · ${track.artist}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { ui.trackMenu = TrackMenu(track) }, contentPadding = PaddingValues(0.dp)) {
                        Text("More options", style = MaterialTheme.typography.labelLarge)
                    }
                }
                PlayCircle(playing = false, onClick = onPlay, size = 48.dp)
            }
        }
    }
}

@Composable
private fun LinkImportCard(link: String) {
    val app = LocalAppViewModel.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("${PlaylistImport.linkSourceName(link)} link", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Import it as a playlist. Songify finds every song and saves the playlist on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { app.importLink(link) }) { Text("Import playlist") }
        }
    }
}

@Composable
fun EmptyMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
    )
}
