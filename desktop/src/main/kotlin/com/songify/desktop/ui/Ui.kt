package com.songify.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp
import com.songify.desktop.Offline
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import com.songify.app.data.Playlist
import com.songify.app.data.Track
import com.songify.app.data.Wanted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data class PlaylistScreen(val id: String) : Screen
    data class ArtistScreen(val name: String) : Screen
    data object SettingsScreen : Screen
}

enum class RightPanel { QUEUE, LYRICS }

enum class ImportTab { LINK, FILE, PASTE }

data class Replace(val playlistId: String, val wanted: Wanted)

const val DOWNLOADS_ID = "downloads"

/** Navigation and app-wide dialogs, so any screen can open them. */
class DesktopUi(private val snackbar: SnackbarHostState, private val scope: CoroutineScope) {
    var stack by mutableStateOf<List<Screen>>(listOf(Screen.Home))
        private set
    val screen: Screen get() = stack.last()

    var rightPanel by mutableStateOf<RightPanel?>(null)
    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    /** Non-null while the "new playlist" dialog is open; holds the tracks to put in it. */
    var createPlaylist by mutableStateOf<List<Track>?>(null)
    var renaming by mutableStateOf<Playlist?>(null)
    var deleting by mutableStateOf<Playlist?>(null)
    var replace by mutableStateOf<Replace?>(null)
    var importTab by mutableStateOf<ImportTab?>(null)
    var guidePage by mutableStateOf<Int?>(null)

    fun go(screen: Screen) {
        if (stack.last() != screen) stack = stack + screen
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun toggle(panel: RightPanel) {
        rightPanel = if (rightPanel == panel) null else panel
    }

    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
}

val LocalUi = staticCompositionLocalOf<DesktopUi> { error("DesktopUi not provided") }

// Provided once at the top so song rows don't each subscribe to the library, downloads and player.
val LocalLikedKeys = compositionLocalOf<Set<String>> { emptySet() }
val LocalDownloadStates = compositionLocalOf<Map<String, Offline>> { emptyMap() }
val LocalCurrentKey = compositionLocalOf<String?> { null }

val TextSecondary = Color(0xFFB3B3B3)

data class AccentPreset(val name: String, val argb: Int)

val AccentPresets = listOf(
    AccentPreset("Green", 0xFF1ED760.toInt()),
    AccentPreset("Blue", 0xFF4C8DFF.toInt()),
    AccentPreset("Purple", 0xFF9D85FF.toInt()),
    AccentPreset("Pink", 0xFFFF5CA8.toInt()),
    AccentPreset("Red", 0xFFFF4D5E.toInt()),
    AccentPreset("Orange", 0xFFFF8A3D.toInt()),
    AccentPreset("Yellow", 0xFFFFD43B.toInt()),
    AccentPreset("Teal", 0xFF2DD4BF.toInt()),
)

@Composable
fun SongifyTheme(accent: Color, amoled: Boolean, content: @Composable () -> Unit) {
    val background = if (amoled) Color.Black else Color(0xFF121212)
    val colors = darkColorScheme(
        primary = accent,
        onPrimary = if (accent.luminance() > 0.4f) Color.Black else Color.White,
        background = Color.Black,
        onBackground = Color.White,
        surface = background,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF282828),
        onSurfaceVariant = TextSecondary,
        surfaceContainerLowest = background,
        surfaceContainerLow = Color(0xFF181818),
        surfaceContainer = Color(0xFF1F1F1F),
        surfaceContainerHigh = Color(0xFF282828),
        surfaceContainerHighest = Color(0xFF333333),
        secondaryContainer = Color(0xFF333333),
        onSecondaryContainer = Color.White,
        outline = Color(0xFF535353),
        outlineVariant = Color(0xFF3E3E3E),
    )
    val base = Typography()
    val typography = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
    // Thin, rounded scrollbars that brighten on hover, like the rest of the dark UI.
    val scrollbars = ScrollbarStyle(
        minimalHeight = 48.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 250,
        unhoverColor = Color.White.copy(alpha = 0.18f),
        hoverColor = Color.White.copy(alpha = 0.45f),
    )
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalScrollbarStyle provides scrollbars, content = content)
    }
}

fun formatTime(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatTotal(tracks: List<Track>): String {
    val minutes = tracks.sumOf { it.durationSec } / 60
    val songs = if (tracks.size == 1) "1 song" else "${tracks.size} songs"
    return if (minutes >= 60) "$songs · ${minutes / 60} h ${minutes % 60} min" else "$songs · $minutes min"
}
