package com.songify.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.songify.app.data.AudioQuality
import com.songify.app.data.Library
import com.songify.app.data.Lyrics
import com.songify.app.data.LyricsRepo
import com.songify.app.data.PlaylistShare
import com.songify.app.data.Settings
import com.songify.app.data.Source
import com.songify.app.data.Track
import com.songify.app.playback.Sleep
import com.songify.app.playback.SleepTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val SWIPE_THRESHOLD = 140f

@Composable
fun MiniPlayer(np: NowPlaying, player: Player, onOpen: () -> Unit) {
    val track = np.track ?: return
    val liked = Library.data.collectAsState().value.isLiked(track)
    val base = rememberArtworkColor(track.artworkUrl)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    Surface(
        color = lerp(base, Color.Black, 0.35f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Swipe left/right to skip, like Spotify.
                    .pointerInput(player) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val dx = offset.value
                                when {
                                    dx < -SWIPE_THRESHOLD && player.hasNextMediaItem() -> player.seekToNextMediaItem()
                                    dx > SWIPE_THRESHOLD -> player.seekToPreviousMediaItem()
                                }
                                if (abs(dx) > SWIPE_THRESHOLD) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch { offset.animateTo(0f) }
                            },
                            onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        ) { change, dragAmount ->
                            change.consume()
                            scope.launch { offset.snapTo(offset.value + dragAmount) }
                        }
                    }
                    .clickable(onClick = onOpen)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).graphicsLayer {
                        translationX = offset.value
                        alpha = 1f - (abs(offset.value) / 600f).coerceAtMost(0.6f)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(track.artworkUrl, Modifier.size(40.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(
                            track.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                LikeButton(track, liked)
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (np.isBuffering) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        IconButton(onClick = player::togglePlay) {
                            Icon(
                                if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (np.isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }
            MiniProgress(player, np.durationMs)
        }
    }
}

// Isolated so the ticking position only redraws this thin bar.
@Composable
private fun MiniProgress(player: Player, durationMs: Long) {
    val position by player.positionState()
    val fraction = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Color.White))
    }
}

@Composable
private fun LikeButton(track: Track, liked: Boolean, size: Int = 24) {
    val haptics = LocalHapticFeedback.current
    IconButton(onClick = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        Library.toggleLike(track)
    }) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
            tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(size.dp),
        )
    }
}

@Composable
fun FullPlayer(np: NowPlaying, player: Player, onClose: () -> Unit) {
    val track = np.track ?: return
    val ui = LocalUi.current
    val context = LocalContext.current
    val liked = Library.data.collectAsState().value.isLiked(track)
    val sleep by SleepTimer.state.collectAsState()
    val base = rememberArtworkColor(track.artworkUrl)
    val accent = MaterialTheme.colorScheme.primary
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragY.value }
            .background(Brush.verticalGradient(listOf(lerp(base, Color.Black, 0.1f), lerp(base, Color.Black, 0.75f), Color.Black)))
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Drag the top half down to close.
            Column(
                Modifier.weight(1f).pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragY.value > 220f) onClose()
                            scope.launch { dragY.animateTo(0f) }
                        },
                        onDragCancel = { scope.launch { dragY.animateTo(0f) } },
                    ) { change, dy ->
                        change.consume()
                        scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) }
                    }
                },
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", modifier = Modifier.size(32.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        Text(track.source.label, style = MaterialTheme.typography.titleSmall)
                    }
                    IconButton(onClick = { ui.trackMenu = TrackMenu(track) }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    if (showLyrics) {
                        LyricsPanel(track, player, base)
                    } else {
                        Artwork(track.artworkUrl, Modifier.fillMaxSize(), corner = 8.dp)
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { ui.open(Routes.artist(track.artist)) },
                    )
                }
                LikeButton(track, liked, size = 28)
            }
            Spacer(Modifier.height(8.dp))
            SeekBar(player, np.durationMs)
            Spacer(Modifier.height(4.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.shuffleModeEnabled = !np.shuffle }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = if (np.shuffle) accent else Color.White)
                }
                IconButton(onClick = player::seekToPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
                }
                Surface(onClick = player::togglePlay, shape = CircleShape, color = Color.White, modifier = Modifier.size(68.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (np.isBuffering) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.Black, strokeWidth = 3.dp)
                        } else {
                            Icon(
                                if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (np.isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(38.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = player::seekToNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = {
                    player.repeatMode = when (np.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        if (np.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = if (np.repeatMode == Player.REPEAT_MODE_OFF) Color.White else accent,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Rounded.Lyrics, contentDescription = "Lyrics", tint = if (showLyrics) accent else Color.White)
                }
                IconButton(onClick = { ui.sleepOpen = true }) {
                    Icon(Icons.Rounded.Bedtime, contentDescription = "Sleep timer", tint = if (sleep != Sleep.Off) accent else Color.White)
                }
                QualityLabel(track) {
                    onClose()
                    ui.open(Routes.SETTINGS)
                }
                IconButton(onClick = { context.startActivity(PlaylistShare.trackIntent(track)) }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share")
                }
                IconButton(onClick = { ui.queueOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Queue")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SeekBar(player: Player, durationMs: Long) {
    val position by player.positionState(250)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Slider(
        value = dragging ?: fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { player.seekTo((it * durationMs).toLong()) }
            dragging = null
        },
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        ),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val shownMs = dragging?.let { (it * durationMs).toLong() } ?: position
        Text(formatTime(shownMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun QualityLabel(track: Track, onClick: () -> Unit) {
    val chosen by Settings.quality.collectAsState()
    val label = when {
        track.source == Source.AUDIUS -> "MP3 320"
        chosen == AudioQuality.AUTO -> "Auto · ${Settings.effectiveQuality().label}"
        else -> chosen.label
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.8f),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object None : LyricsUi
    data object Failed : LyricsUi
    data class Loaded(val lyrics: Lyrics) : LyricsUi
}

/** Synced lyrics that follow the song; tap a line to jump there. */
@Composable
private fun LyricsPanel(track: Track, player: Player, base: Color) {
    val state by produceState<LyricsUi>(LyricsUi.Loading, track.key) {
        value = withContext(Dispatchers.IO) {
            runCatching { LyricsRepo.get(track) }.fold({ it?.let(LyricsUi::Loaded) ?: LyricsUi.None }, { LyricsUi.Failed })
        }
    }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(lerp(base, Color.Black, 0.45f)).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            LyricsUi.Loading -> CircularProgressIndicator(color = Color.White)
            LyricsUi.None -> Text("No lyrics for this song yet", color = Color.White.copy(alpha = 0.8f))
            LyricsUi.Failed -> Text("Couldn't load lyrics. Check your connection.", color = Color.White.copy(alpha = 0.8f))
            is LyricsUi.Loaded -> if (s.lyrics.synced.isNotEmpty()) {
                SyncedLyrics(s.lyrics, player)
            } else {
                Text(
                    s.lyrics.plain.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, player: Player) {
    val position by player.positionState(200)
    val lines = lyrics.synced
    val current by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= position + 300 }.coerceAtLeast(0) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current) { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(lines) { i, line ->
            Text(
                line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = when {
                    i == current -> Color.White
                    i < current -> Color.White.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.35f)
                },
                modifier = Modifier.fillMaxWidth().clickable { player.seekTo(line.timeMs) }.padding(vertical = 6.dp),
            )
        }
    }
}
