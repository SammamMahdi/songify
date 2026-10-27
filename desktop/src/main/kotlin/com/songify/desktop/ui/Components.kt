package com.songify.desktop.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import java.awt.Cursor
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.songify.app.data.Library
import com.songify.app.data.Track
import com.songify.desktop.DesktopDownloads
import com.songify.desktop.DesktopPlayer
import com.songify.desktop.Offline

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: Dp = 4.dp) {
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Liked Songs gets a gradient heart; other playlists show a 2×2 mosaic of their artwork. */
@Composable
fun PlaylistCover(tracks: List<Track>, modifier: Modifier = Modifier, liked: Boolean = false, corner: Dp = 4.dp) {
    if (liked) {
        Box(
            modifier.clip(RoundedCornerShape(corner))
                .background(Brush.linearGradient(listOf(Color(0xFF4A2FBD), lerp(MaterialTheme.colorScheme.primary, Color.White, 0.25f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize(0.4f))
        }
        return
    }
    val art = tracks.mapNotNull { it.artworkUrl }.distinct()
    if (art.size < 4) {
        Artwork(art.firstOrNull(), modifier, corner)
        return
    }
    Column(modifier.clip(RoundedCornerShape(corner))) {
        for (row in 0..1) {
            Row(Modifier.weight(1f)) {
                for (col in 0..1) Artwork(art[row * 2 + col], Modifier.weight(1f).fillMaxSize(), 0.dp)
            }
        }
    }
}

/** The big round accent-colored play button. */
@Composable
fun PlayCircle(playing: Boolean, size: Dp = 52.dp, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

@Composable
fun DownloadBadge(state: Offline?) {
    val modifier = Modifier.size(16.dp)
    when (state) {
        Offline.Done -> Icon(Icons.Rounded.DownloadDone, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = modifier)
        Offline.Queued, Offline.Downloading -> CircularProgressIndicator(modifier.padding(2.dp), strokeWidth = 1.5.dp)
        Offline.Failed -> Icon(Icons.Rounded.ErrorOutline, contentDescription = "Couldn't download", tint = MaterialTheme.colorScheme.error, modifier = modifier)
        null -> Unit
    }
}

/** The actions for one song, shared by the ⋮ menu and the right-click menu. */
private data class SongAction(val label: String, val run: () -> Unit)

// Plain function (not composable) so menus are only built when they're opened.
private fun songActions(
    track: Track,
    playlistId: String?,
    liked: Boolean,
    download: Offline?,
    ui: DesktopUi,
    copy: (String) -> Unit,
    close: () -> Unit,
): List<SongAction> =
    buildList {
        add(SongAction(if (liked) "Remove from Liked Songs" else "Add to Liked Songs") { Library.toggleLike(track) })
        add(SongAction("Add to playlist…") { ui.addToPlaylist = listOf(track) })
        add(
            when (download) {
                Offline.Done -> SongAction("Remove download") { DesktopDownloads.remove(listOf(track)); ui.toast("Removed from downloads") }
                Offline.Failed -> SongAction("Couldn't download · Try again") { DesktopDownloads.download(listOf(track)) }
                Offline.Queued, Offline.Downloading -> SongAction("Downloading…") {}
                null -> SongAction("Download") { DesktopDownloads.download(listOf(track)); ui.toast("Downloading ${track.title}") }
            },
        )
        add(SongAction("Play next") { DesktopPlayer.playNext(track); ui.toast("Playing next") })
        add(SongAction("Add to queue") { DesktopPlayer.enqueue(track); ui.toast("Added to queue") })
        add(SongAction("Start radio") { DesktopPlayer.startRadio(track) })
        add(SongAction("Go to artist") { ui.go(Screen.ArtistScreen(track.artist)) })
        if (playlistId != null) add(SongAction("Remove from this playlist") { Library.removeFromPlaylist(playlistId, track) })
        add(SongAction("Copy song info") { copy(track.shareText); ui.toast("Copied") })
    }.map { action -> SongAction(action.label) { close(); action.run() } }

@Composable
fun SongMenuButton(track: Track, playlistId: String? = null, tint: Color = TextSecondary) {
    val ui = LocalUi.current
    val clipboard = LocalClipboardManager.current
    val liked = track.key in LocalLikedKeys.current
    val download = LocalDownloadStates.current[track.key]
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(Icons.Rounded.MoreHoriz, "More options", tint = tint) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            songActions(track, playlistId, liked, download, ui, { clipboard.setText(AnnotatedString(it)) }) { open = false }.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, onClick = action.run)
            }
        }
    }
}

/** A song in a list: click to play, right-click or ⋯ for options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(track: Track, number: Int, onPlay: () -> Unit, playlistId: String? = null) {
    val ui = LocalUi.current
    val clipboard = LocalClipboardManager.current
    val current = LocalCurrentKey.current == track.key
    val liked = track.key in LocalLikedKeys.current
    val download = LocalDownloadStates.current[track.key]
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()

    ContextMenuArea(items = {
        songActions(track, playlistId, liked, download, ui, { clipboard.setText(AnnotatedString(it)) }) {}
            .map { ContextMenuItem(it.label, it.run) }
    }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (hovered) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                .hoverable(hover)
                .clickable(onClick = onPlay)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                when {
                    hovered -> Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", modifier = Modifier.size(20.dp))
                    current -> Icon(Icons.Rounded.GraphicEq, contentDescription = "Now playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    else -> Text("$number", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Artwork(track.artworkUrl, Modifier.padding(start = 8.dp).size(40.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (current) MaterialTheme.colorScheme.primary else Color.White,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadBadge(download)
                    Text(
                        track.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = if (download != null) 4.dp else 0.dp).clickable { ui.go(Screen.ArtistScreen(track.artist)) },
                    )
                }
            }
            if (hovered || liked) {
                IconButton(onClick = { Library.toggleLike(track) }) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                        tint = if (liked) MaterialTheme.colorScheme.primary else TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (hovered && download == null) {
                IconButton(onClick = { DesktopDownloads.download(listOf(track)); ui.toast("Downloading ${track.title}") }) {
                    Icon(Icons.Rounded.Download, contentDescription = "Download", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Text(formatTime(track.durationSec * 1000), color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
            SongMenuButton(track, playlistId)
        }
    }
}

/** A LazyColumn with a scrollbar on the right. */
@Composable
fun ScrollColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = contentPadding, content = content)
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }
}

/** A draggable divider between panels; reports horizontal movement and when the drag ends. */
@Composable
fun Splitter(onDrag: (Dp) -> Unit, onDragEnd: () -> Unit) {
    val density = LocalDensity.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier
            .width(8.dp)
            .fillMaxHeight()
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) },
                Orientation.Horizontal,
                onDragStopped = { onDragEnd() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (hovered) Box(Modifier.width(2.dp).fillMaxHeight().padding(vertical = 8.dp).background(Color.White.copy(alpha = 0.3f)))
    }
}

/** An icon button with a hover tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconAction(icon: ImageVector, label: String, tint: Color = Color.White, size: Dp = 24.dp, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(4.dp), shadowElevation = 4.dp) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        delayMillis = 500,
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size)) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyMessage(text: String) {
    Text(text, color = TextSecondary, modifier = Modifier.padding(vertical = 24.dp))
}
