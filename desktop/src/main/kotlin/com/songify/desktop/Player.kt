package com.songify.desktop

import com.songify.app.data.AppJson
import com.songify.app.data.Library
import com.songify.app.data.Track
import com.songify.app.data.YouTubeMusic
import com.songify.app.data.writeAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

enum class Repeat { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.OFF,
    val volume: Float = 0.8f,
) {
    val current: Track? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index < queue.lastIndex
}

@Serializable
private data class SavedQueue(val tracks: List<Track>, val index: Int, val positionMs: Long)

/** The desktop player: queue, shuffle, repeat, radio, prefetching and resume, on top of [AudioEngine]. */
object DesktopPlayer {
    private const val RADIO_BATCH = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queueFile get() = File(AppDirs.data, "queue.json")

    private val _state = MutableStateFlow(PlayerState())
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Things to tell the user, e.g. a song that couldn't be played. */
    val messages = _messages.asSharedFlow()

    private val engine = AudioEngine(
        onEnded = { scope.launch { onEnded() } },
        onError = { scope.launch { onFailed(it) } },
    )

    private var original: List<Track> = emptyList()
    private var loadJob: Job? = null
    private var loadedKey: String? = null
    private var resumeAtMs = 0L
    private var radio = false
    private var extending = false

    val positionMs: Long get() = if (loadedKey == null) resumeAtMs else engine.positionMs

    /** Audible gaps so far (sound card ran dry). For diagnostics. */
    val underruns: Int get() = engine.underruns

    fun init() {
        val volume = DesktopSettings.prefs.value.volume
        engine.setVolume(volume)
        val saved = runCatching { AppJson.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull()
        if (saved != null && saved.tracks.isNotEmpty()) {
            original = saved.tracks
            resumeAtMs = saved.positionMs
            _state.value = PlayerState(queue = saved.tracks, index = saved.index.coerceIn(0, saved.tracks.lastIndex), volume = volume)
        } else {
            _state.update { it.copy(volume = volume) }
        }
    }

    fun playTracks(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        radio = false
        original = tracks
        val first = if (shuffle) tracks.indices.random() else start.coerceIn(0, tracks.lastIndex)
        val queue = if (shuffle) listOf(tracks[first]) + (tracks.take(first) + tracks.drop(first + 1)).shuffled() else tracks
        _state.update { it.copy(queue = queue, index = if (shuffle) 0 else first, shuffle = shuffle) }
        load(autoplay = true)
    }

    /** Plays [track], then keeps going with similar songs. */
    fun startRadio(track: Track) {
        playTracks(listOf(track))
        radio = true
    }

    fun playAt(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(index = index) }
        load(autoplay = true)
    }

    fun togglePlay() {
        val s = _state.value
        val current = s.current ?: return
        when {
            loadedKey != current.key -> load(autoplay = true, startMs = resumeAtMs)
            engine.isPaused -> {
                engine.play()
                _state.update { it.copy(isPlaying = true) }
            }
            else -> pause()
        }
    }

    fun pause() {
        engine.pause()
        _state.update { it.copy(isPlaying = false) }
        saveQueue()
    }

    /** Next always works: with nothing queued, it finds a similar song and plays it. */
    fun next() {
        if (_state.value.hasNext) playAt(_state.value.index + 1) else addSimilar(skipToThem = true)
    }

    fun previous() {
        val s = _state.value
        if (positionMs > 3000 || s.index <= 0) seek(0) else playAt(s.index - 1)
    }

    fun seek(ms: Long) {
        if (loadedKey == null) resumeAtMs = ms else engine.seek(ms)
    }

    fun setVolume(value: Float) {
        engine.setVolume(value)
        _state.update { it.copy(volume = value) }
        DesktopSettings.update { it.copy(volume = value) }
    }

    fun toggleShuffle() {
        _state.update { s ->
            val current = s.current ?: return@update s.copy(shuffle = !s.shuffle)
            if (!s.shuffle) {
                // Shuffle what's still to come; the current song keeps playing.
                s.copy(queue = s.queue.take(s.index + 1) + s.queue.drop(s.index + 1).shuffled(), shuffle = true)
            } else {
                val restored = original.ifEmpty { s.queue }
                s.copy(queue = restored, index = restored.indexOfFirst { it.key == current.key }.coerceAtLeast(0), shuffle = false)
            }
        }
    }

    fun cycleRepeat() = _state.update { it.copy(repeat = Repeat.entries[(it.repeat.ordinal + 1) % Repeat.entries.size]) }

    fun playNext(track: Track) {
        if (_state.value.current == null) return playTracks(listOf(track))
        _state.update { s -> s.copy(queue = s.queue.take(s.index + 1) + track + s.queue.drop(s.index + 1)) }
    }

    fun enqueue(track: Track) {
        if (_state.value.current == null) return playTracks(listOf(track))
        _state.update { it.copy(queue = it.queue + track) }
    }

    /** Removes an upcoming song from the queue. */
    fun removeAt(index: Int) {
        if (index <= _state.value.index) return
        _state.update { s -> s.copy(queue = s.queue.filterIndexed { i, _ -> i != index }) }
    }

    fun shutdown() {
        saveQueue()
        engine.stop()
    }

    private fun load(autoplay: Boolean, startMs: Long = 0) {
        val track = _state.value.current ?: return
        loadJob?.cancel()
        engine.stop()
        loadedKey = null
        resumeAtMs = startMs
        _state.update { it.copy(isLoading = true, isPlaying = autoplay, durationMs = track.durationSec * 1000) }
        loadJob = scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { AudioFiles.fileFor(track) } }
                .getOrElse { onFailed(it); return@launch }
            ensureActive()
            val loaded = runCatching { engine.load(file, autoplay, startMs) }.recoverCatching { error ->
                // A cached copy that won't decode (e.g. saved by an older version): fetch it again once.
                if (file.parentFile != AppDirs.cache) throw error
                file.delete()
                val fresh = withContext(Dispatchers.IO) { AudioFiles.fetch(track, AppDirs.cache) }
                engine.load(fresh, autoplay, startMs)
            }
            loaded.onFailure { onFailed(it); return@launch }
            loadedKey = track.key
            _state.update { s -> s.copy(isLoading = false, durationMs = engine.durationMs.takeIf { it > 0 } ?: s.durationMs) }
            Library.addRecent(track)
            saveQueue()
            prepareAhead()
        }
    }

    // Fetch the next song now so it starts without a gap, and top up the queue near its end.
    private fun prepareAhead() {
        val s = _state.value
        s.queue.getOrNull(s.index + 1)?.let { next -> scope.launch(Dispatchers.IO) { runCatching { AudioFiles.fileFor(next) } } }
        if (!s.hasNext && s.repeat == Repeat.OFF && (DesktopSettings.prefs.value.autoplay || radio)) addSimilar(skipToThem = false)
    }

    private fun addSimilar(skipToThem: Boolean) {
        val current = _state.value.current ?: return
        if (extending) return
        extending = true
        scope.launch {
            val queued = _state.value.queue.map { it.key }.toSet()
            val more = withContext(Dispatchers.IO) { runCatching { YouTubeMusic.similar(current) }.getOrDefault(emptyList()) }
                .filter { it.key !in queued }
                .take(RADIO_BATCH)
            _state.update { it.copy(queue = it.queue + more) }
            extending = false
            if (skipToThem && _state.value.hasNext) playAt(_state.value.index + 1)
            else if (skipToThem) _messages.tryEmit("Couldn't find a next song. Check your connection.")
        }
    }

    private fun onEnded() {
        val s = _state.value
        when {
            s.repeat == Repeat.ONE -> {
                engine.load(AudioFiles.fileFor(s.current ?: return), play = true)
            }
            DesktopSleep.consumeEndOfSong() -> pause()
            s.hasNext -> playAt(s.index + 1)
            s.repeat == Repeat.ALL && s.queue.isNotEmpty() -> playAt(0)
            DesktopSettings.prefs.value.autoplay || radio -> addSimilar(skipToThem = true)
            else -> _state.update { it.copy(isPlaying = false) }
        }
    }

    private suspend fun onFailed(error: Throwable) {
        val title = _state.value.current?.title ?: "that song"
        _messages.tryEmit("Couldn't play “$title”: ${error.message ?: "unknown error"}")
        _state.update { it.copy(isLoading = false) }
        if (_state.value.hasNext) {
            delay(500)
            playAt(_state.value.index + 1)
        } else {
            _state.update { it.copy(isPlaying = false) }
        }
    }

    private fun saveQueue() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val saved = SavedQueue(s.queue.take(500), s.index, positionMs)
        scope.launch(Dispatchers.IO) { writeAtomically(queueFile, AppJson.encodeToString(SavedQueue.serializer(), saved)) }
    }
}

sealed interface Sleep {
    data object Off : Sleep
    data class At(val atMs: Long) : Sleep
    data object EndOfSong : Sleep
}

/** Pauses after a delay or at the end of the current song. */
object DesktopSleep {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<Sleep>(Sleep.Off)
    val state = _state.asStateFlow()

    fun start(minutes: Int) {
        job?.cancel()
        _state.value = Sleep.At(System.currentTimeMillis() + minutes * 60_000L)
        job = scope.launch {
            delay(minutes * 60_000L)
            DesktopPlayer.pause()
            _state.value = Sleep.Off
        }
    }

    fun endOfSong() {
        job?.cancel()
        _state.value = Sleep.EndOfSong
    }

    fun cancel() {
        job?.cancel()
        _state.value = Sleep.Off
    }

    fun consumeEndOfSong(): Boolean {
        if (_state.value != Sleep.EndOfSong) return false
        _state.value = Sleep.Off
        return true
    }
}
