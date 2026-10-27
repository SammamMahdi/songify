package com.songify.desktop

import com.songify.app.data.ImportItem
import com.songify.app.data.ImportedList
import com.songify.app.data.Library
import com.songify.app.data.PlaylistImport
import com.songify.app.data.Release
import com.songify.app.data.Updater
import com.songify.app.data.Wanted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

data class ImportedPlaylist(val id: String, val name: String, val added: Int, val missing: List<Wanted>)

sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val message: String, val done: Int = 0, val total: Int = 0) : ImportState
    data class Done(val title: String, val playlists: List<ImportedPlaylist>, val truncated: Boolean = false) : ImportState {
        val added get() = playlists.sumOf { it.added }
        val missing get() = playlists.flatMap { it.missing }
    }
    data class Failed(val message: String) : ImportState
}

/** Playlist imports from links, files and pasted text, reusing the shared import code. */
object Imports {
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state = _state.asStateFlow()

    fun link(link: String) =
        run("Reading your ${PlaylistImport.linkSourceName(link)} playlist…") { listOf(PlaylistImport.fromLink(link)) }

    fun text(name: String, text: String) = run("Reading your list…") { PlaylistImport.fromText(name, text) }

    fun file(file: File) = run("Opening ${file.name}…") {
        if (file.length() > MAX_FILE_BYTES) throw IOException("That file is too large to be a playlist")
        PlaylistImport.fromFile(file.name, file.readBytes())
    }

    fun retryMissing(playlistId: String) {
        val playlist = Library.data.value.playlist(playlistId) ?: return
        if (playlist.missing.isEmpty()) return
        job?.cancel()
        val message = "Looking again for ${playlist.missing.size} songs"
        _state.value = ImportState.Working(message, 0, playlist.missing.size)
        job = scope.launch {
            try {
                val list = ImportedList(playlist.name, playlist.missing.map { ImportItem.Lookup(it) })
                val result = PlaylistImport.resolve(list) { d, t -> _state.value = ImportState.Working(message, d, t) }
                Library.updateMissing(playlistId, result.tracks, result.missing)
                val title = if (result.tracks.isEmpty()) "No new matches" else "Found ${result.tracks.size} more"
                _state.value = ImportState.Done(title, listOf(ImportedPlaylist(playlistId, playlist.name, result.tracks.size, result.missing)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ImportState.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = ImportState.Idle
    }

    fun dismiss() {
        _state.value = ImportState.Idle
    }

    private fun run(message: String, load: () -> List<ImportedList>) {
        job?.cancel()
        _state.value = ImportState.Working(message)
        job = scope.launch {
            try {
                val lists = withContext(Dispatchers.IO) { load() }
                val imported = lists.mapIndexed { i, list ->
                    val prefix = if (lists.size > 1) "Playlist ${i + 1} of ${lists.size}: " else ""
                    val finding = "$prefix“${list.name}”"
                    _state.value = ImportState.Working(finding, 0, list.items.size)
                    val result = PlaylistImport.resolve(list) { d, t -> _state.value = ImportState.Working(finding, d, t) }
                    val playlist = Library.createPlaylist(list.name, result.tracks, result.missing)
                    ImportedPlaylist(playlist.id, playlist.name, result.tracks.size, result.missing)
                }
                if (imported.all { it.added == 0 }) throw IOException("Couldn't find any of these songs")
                val title = if (imported.size == 1) "Playlist imported" else "${imported.size} playlists imported"
                _state.value = ImportState.Done(title, imported, truncated = lists.any { it.truncated })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ImportState.Failed(e.message ?: "Something went wrong")
            }
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Checks GitHub releases for a newer Windows installer and runs it. */
object Updates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    fun check(manual: Boolean) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        if (manual) _state.value = UpdateState.Checking
        scope.launch {
            val result = runCatching { Updater.newerThan(APP_VERSION, ".msi") }
            _state.value = when {
                result.isFailure -> if (manual) UpdateState.Failed("Couldn't check for updates. Check your connection.") else UpdateState.Idle
                result.getOrNull() != null -> UpdateState.Available(result.getOrNull()!!)
                manual -> UpdateState.Failed("You're on the latest version ($APP_VERSION).")
                else -> UpdateState.Idle
            }
        }
    }

    /** Downloads the installer, starts it, and closes Songify so it can be replaced. */
    fun install(release: Release) {
        _state.value = UpdateState.Downloading(release, 0f)
        scope.launch {
            runCatching {
                val dir = File(System.getProperty("java.io.tmpdir"), "songify-update")
                Updater.download(dir, release) { _state.value = UpdateState.Downloading(release, it) }
            }.onSuccess { installer ->
                ProcessBuilder("msiexec", "/i", installer.absolutePath).start()
                DesktopPlayer.shutdown()
                exitProcess(0)
            }.onFailure {
                _state.value = UpdateState.Failed("Couldn't download the update. Try again later.")
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }
}
