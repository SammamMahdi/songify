package com.songify.app.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The service's player, shared in-process for features MediaController doesn't cover. */
object PlayerHub {
    @Volatile var player: ExoPlayer? = null
        internal set

    /** For the system equalizer. */
    @Volatile var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
        internal set
}

sealed interface Sleep {
    data object Off : Sleep
    data class At(val atMs: Long) : Sleep
    data object EndOfSong : Sleep
}

/** Pauses playback after a delay or at the end of the current song. Keeps running with the app closed. */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable {
        PlayerHub.player?.pause()
        _state.value = Sleep.Off
    }

    private val _state = MutableStateFlow<Sleep>(Sleep.Off)
    val state = _state.asStateFlow()

    fun start(minutes: Int) {
        handler.removeCallbacks(fire)
        _state.value = Sleep.At(System.currentTimeMillis() + minutes * 60_000L)
        handler.postDelayed(fire, minutes * 60_000L)
    }

    fun endOfSong() {
        handler.removeCallbacks(fire)
        _state.value = Sleep.EndOfSong
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        _state.value = Sleep.Off
    }

    /** Called by the service when a song finishes on its own; true if playback should stop. */
    internal fun consumeEndOfSong(): Boolean {
        if (_state.value != Sleep.EndOfSong) return false
        _state.value = Sleep.Off
        return true
    }
}
