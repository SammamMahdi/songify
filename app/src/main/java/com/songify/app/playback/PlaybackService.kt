package com.songify.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.songify.app.MainActivity
import com.songify.app.data.EXTRA_LISTED_SECONDS
import com.songify.app.data.EXTRA_RADIO
import com.songify.app.data.Library
import com.songify.app.data.Settings
import com.songify.app.data.StreamResolver
import com.songify.app.data.Track
import com.songify.app.data.YouTubeMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The player can only load items with a URI; controllers can't send one, so the mediaId carries it.
// The cache key matches the one downloads use, so downloaded songs are found offline.
private fun MediaItem.withStreamUri(): MediaItem =
    buildUpon().setUri(mediaId).setCustomCacheKey(Track.fromMediaItem(this)?.key).build()

/**
 * Streams audio in the background (screen off, app closed) with notification,
 * lock-screen, headset and car controls.
 */
class PlaybackService : MediaSessionService() {
    private companion object {
        const val RADIO_BATCH = 20
    }

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var queueStore: QueueStore

    override fun onCreate() {
        super.onCreate()
        queueStore = QueueStore(this)
        // Downloaded songs play from storage; others resolve songify:// URIs to verified streams on load.
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(Downloads.playbackFactory()))
            // Start after 1 s of audio instead of 2.5 s; keep up to a minute buffered for patchy networks.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_000, 3_000).build())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(QueueWatcher(player))
        restoreQueue(player)
        PlayerHub.player = player
        PlayerHub.audioSessionId = player.audioSessionId
        // Lets the system equalizer and EQ apps attach to Songify's audio.
        sendBroadcast(effectIntent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, player.audioSessionId))

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, AlwaysNextPlayer(player))
            .setCallback(Callback)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.player?.let { player ->
            saveQueue(player)
            sendBroadcast(effectIntent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, PlayerHub.audioSessionId))
        }
        PlayerHub.player = null
        SleepTimer.cancel()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun effectIntent(action: String, sessionId: Int) = Intent(action)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

    // Loaded paused, so the mini player shows the last song without using data until play is pressed.
    private fun restoreQueue(player: ExoPlayer) {
        val saved = queueStore.load() ?: return
        try {
            player.setMediaItems(saved.tracks.map { it.toPlayable() }, saved.index.coerceIn(0, saved.tracks.lastIndex), saved.positionMs)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
        } catch (e: Exception) {
            // A bad saved queue must never stop the app from opening.
            player.clearMediaItems()
            queueStore.clear()
        }
    }

    private fun saveQueue(player: Player) {
        if (player.mediaItemCount == 0) return
        val tracks = (0 until player.mediaItemCount).mapNotNull { Track.fromMediaItem(player.getMediaItemAt(it)) }
        val queue = SavedQueue(tracks, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        scope.launch(Dispatchers.IO) { queueStore.save(queue) }
    }

    private object Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withStreamUri() }.toMutableList())
    }

    /** Items given to ExoPlayer directly (not via a controller) need the songify:// URI set too. */
    private fun Track.toPlayable(radio: Boolean = false) = toMediaItem(radio).withStreamUri()

    private var extending = false

    /** Appends songs similar to the current one; with [skipToThem], also jumps to the first. */
    private fun addSimilarSongs(player: ExoPlayer, skipToThem: Boolean) {
        if (extending || player.hasNextMediaItem()) return
        val track = player.currentMediaItem?.let(Track::fromMediaItem) ?: return
        val queued = (0 until player.mediaItemCount).map { Track.fromMediaItem(player.getMediaItemAt(it))?.key }.toSet()
        extending = true
        scope.launch {
            val more = withContext(Dispatchers.IO) { runCatching { YouTubeMusic.similar(track) }.getOrDefault(emptyList()) }
                .filter { it.key !in queued }
                .take(RADIO_BATCH)
            player.addMediaItems(more.map { it.toPlayable(radio = true) })
            extending = false
            if (skipToThem && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.play()
            }
        }
    }

    /**
     * Next always works, like Spotify: with nothing queued, it finds a similar song and skips to it.
     * Advertising the command also keeps the Next button enabled in the notification and on the lock screen.
     */
    private inner class AlwaysNextPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()

        override fun isCommandAvailable(command: Int) =
            command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || super.isCommandAvailable(command)

        override fun seekToNext() = skipForward()

        override fun seekToNextMediaItem() = skipForward()

        private fun skipForward() {
            if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() else addSimilarSongs(exo, skipToThem = true)
        }
    }

    private inner class QueueWatcher(private val player: ExoPlayer) : Player.Listener {
        private var wasReady = false

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            wasReady = false
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && SleepTimer.consumeEndOfSong()) player.pause()
            mediaItem?.let(Track::fromMediaItem)?.let(Library::addRecent)
            if (player.playWhenReady) prepareAhead()
            saveQueue(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) prepareAhead() else saveQueue(player)
        }

        private fun prepareAhead() {
            // Resolve the following song now so it starts without a gap.
            if (player.hasNextMediaItem()) {
                val next = player.getMediaItemAt(player.nextMediaItemIndex).mediaId
                scope.launch(Dispatchers.IO) { StreamResolver.prefetch(next) }
            }
            extendWithRadio()
        }

        // Moves on when a track can't be streamed in full, instead of stalling the queue.
        override fun onPlayerError(error: PlaybackException) = skip()

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> if (wasReady && player.playWhenReady) Settings.reportStall()
                Player.STATE_READY -> {
                    wasReady = true
                    val listedSec = player.currentMediaItem?.mediaMetadata?.extras?.getLong(EXTRA_LISTED_SECONDS) ?: 0
                    if (listedSec > 0 && player.duration != C.TIME_UNSET && player.duration < listedSec * 900) skip()
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // Buffering right after a seek is expected, not a sign of a slow connection.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) wasReady = false
        }

        /** When the last song starts, queue similar songs so the music doesn't stop (Autoplay / radio). */
        private fun extendWithRadio() {
            val radio = player.currentMediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_RADIO) == true
            if (player.repeatMode != Player.REPEAT_MODE_OFF || (!Settings.autoplay.value && !radio)) return
            addSimilarSongs(player, skipToThem = false)
        }

        private fun skip() {
            if (!player.hasNextMediaItem()) return
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }
}
