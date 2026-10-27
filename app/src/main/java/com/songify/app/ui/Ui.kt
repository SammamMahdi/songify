package com.songify.app.ui

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.songify.app.data.Track
import com.songify.app.data.Wanted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val SEARCH = "search?q={q}"
    const val SEARCH_ROOT = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val PLAYLIST = "playlist/{id}"
    const val ARTIST = "artist/{name}"
    fun playlist(id: String) = "playlist/$id"
    fun artist(name: String) = "artist/${Uri.encode(name)}"
    fun search(query: String) = "search?q=${Uri.encode(query)}"
}

data class TrackMenu(val track: Track, val playlistId: String? = null)

data class Replace(val playlistId: String, val wanted: Wanted)

/** App-wide UI state for sheets and dialogs, so any screen can open them. */
class UiState(val nav: NavController, private val snackbar: SnackbarHostState, private val scope: CoroutineScope) {
    var trackMenu by mutableStateOf<TrackMenu?>(null)
    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    /** Non-null while the "new playlist" dialog is open; holds the tracks to put in it. */
    var createPlaylist by mutableStateOf<List<Track>?>(null)
    var replace by mutableStateOf<Replace?>(null)
    var importMenu by mutableStateOf(false)
    var linkDialog by mutableStateOf(false)
    var pasteDialog by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var sleepOpen by mutableStateOf(false)
    var playerOpen by mutableStateOf(false)
    /** Page of the beginner's guide being shown, or null when it's closed. */
    var guidePage by mutableStateOf<Int?>(null)

    /** Opens a screen on top of the current tab, closing the full player if it's open. */
    fun open(route: String) {
        playerOpen = false
        nav.navigate(route) { launchSingleTop = true }
    }

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
}

val LocalUi = staticCompositionLocalOf<UiState> { error("UiState not provided") }
val LocalPlayer = compositionLocalOf<Player?> { null }

/** Key of the track that's playing; changes only when the song changes. */
val LocalCurrentKey = compositionLocalOf<String?> { null }
val LocalIsPlaying = compositionLocalOf { false }

fun formatTime(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatTotal(tracks: List<Track>): String {
    val minutes = tracks.sumOf { it.durationSec } / 60
    val songs = if (tracks.size == 1) "1 song" else "${tracks.size} songs"
    return if (minutes >= 60) "$songs · ${minutes / 60} h ${minutes % 60} min" else "$songs · $minutes min"
}
