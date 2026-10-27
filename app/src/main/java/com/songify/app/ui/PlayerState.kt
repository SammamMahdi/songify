package com.songify.app.ui

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.songify.app.data.Track
import com.songify.app.playback.PlaybackService
import kotlinx.coroutines.delay

/**
 * Player state that changes on events only (song, play/pause, modes). The ticking position is
 * deliberately separate ([positionState]) so only the progress bar redraws while music plays.
 */
data class NowPlaying(
    val track: Track?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val error: String?,
)

/** Connects to [PlaybackService]; null until the connection is ready. */
@Composable
fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }
    return controller
}

@Composable
fun Player.nowPlayingState(): State<NowPlaying> = produceState(snapshot(), this) {
    val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            value = snapshot()
        }
    }
    addListener(listener)
    awaitDispose { removeListener(listener) }
}

/** Current position, refreshed while composed. Read it only in the composables that draw it. */
@Composable
fun Player.positionState(intervalMs: Long = 500): State<Long> = produceState(currentPosition, this) {
    while (true) {
        value = currentPosition
        delay(intervalMs)
    }
}

private fun Player.snapshot() = NowPlaying(
    track = currentMediaItem?.let(Track::fromMediaItem),
    isPlaying = isPlaying,
    isBuffering = playbackState == Player.STATE_BUFFERING,
    durationMs = duration.takeIf { it != C.TIME_UNSET }
        ?: currentMediaItem?.let(Track::fromMediaItem)?.durationSec?.times(1000) ?: 0,
    shuffle = shuffleModeEnabled,
    repeatMode = repeatMode,
    error = playerError?.let { it.cause?.message ?: it.errorCodeName },
)

fun Player.playTracks(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) {
    if (tracks.isEmpty()) return
    shuffleModeEnabled = shuffle
    val first = if (shuffle) tracks.indices.random() else start
    setMediaItems(tracks.map { it.toMediaItem() }, first, 0)
    prepare()
    play()
}

/** Plays [track] then keeps going with similar songs. */
fun Player.startRadio(track: Track) {
    shuffleModeEnabled = false
    setMediaItem(track.toMediaItem(radio = true))
    prepare()
    play()
}

fun Player.playNext(track: Track) {
    if (mediaItemCount == 0) playTracks(listOf(track)) else addMediaItem(currentMediaItemIndex + 1, track.toMediaItem())
}

fun Player.enqueue(track: Track) {
    if (mediaItemCount == 0) playTracks(listOf(track)) else addMediaItem(track.toMediaItem())
}

fun Player.togglePlay() {
    if (isPlaying) {
        pause()
    } else {
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }
}
