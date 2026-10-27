package com.songify.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.songify.app.data.ImportItem
import com.songify.app.data.ImportedList
import com.songify.app.data.Library
import com.songify.app.data.PlaylistImport
import com.songify.app.data.Release
import com.songify.app.data.Updater
import com.songify.app.data.Wanted
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ImportedPlaylist(val id: String, val name: String, val added: Int, val missing: List<Wanted>)

sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val message: String, val done: Int = 0, val total: Int = 0) : ImportState
    /** [truncated]: the source only shared part of a playlist. */
    data class Done(val title: String, val playlists: List<ImportedPlaylist>, val truncated: Boolean = false) : ImportState {
        val added get() = playlists.sumOf { it.added }
        val missing get() = playlists.flatMap { it.missing }
    }
    data class Failed(val message: String) : ImportState
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

val LocalAppViewModel = staticCompositionLocalOf<AppViewModel> { error("AppViewModel not provided") }

/** Runs playlist imports and handles links/files shared into the app. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState = _importState.asStateFlow()

    private var job: Job? = null

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update = _update.asStateFlow()

    val versionName: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"

    /** [manual] checks report "up to date"; automatic ones stay quiet unless there's an update. */
    fun checkForUpdate(manual: Boolean) {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        if (manual) _update.value = UpdateState.Checking
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.newerThan(versionName) } }
            _update.value = when {
                result.isFailure -> if (manual) UpdateState.Failed("Couldn't check for updates. Check your connection.") else UpdateState.Idle
                result.getOrNull() != null -> UpdateState.Available(result.getOrNull()!!)
                manual -> UpdateState.Failed("You're on the latest version ($versionName).")
                else -> UpdateState.Idle
            }
        }
    }

    fun downloadUpdate(release: Release) {
        _update.value = UpdateState.Downloading(release, 0f)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Updater.download(getApplication(), release) { p -> _update.value = UpdateState.Downloading(release, p) }
                }
            }
            _update.value = result.fold({ UpdateState.Ready(it) }, { UpdateState.Failed("Couldn't download the update. Try again later.") })
        }
    }

    fun dismissUpdate() {
        _update.value = UpdateState.Idle
    }

    fun importLink(link: String) =
        runImport("Reading your ${PlaylistImport.linkSourceName(link)} playlist…") { listOf(PlaylistImport.fromLink(link)) }

    /** Looks again for a playlist's missing songs (matching improves, and services add songs). */
    fun retryMissing(playlistId: String) {
        val playlist = Library.data.value.playlist(playlistId) ?: return
        if (playlist.missing.isEmpty()) return
        job?.cancel()
        val message = "Looking again for ${playlist.missing.size} songs"
        _importState.value = ImportState.Working(message, 0, playlist.missing.size)
        job = viewModelScope.launch {
            try {
                val list = ImportedList(playlist.name, playlist.missing.map { ImportItem.Lookup(it) })
                val result = PlaylistImport.resolve(list) { done, total -> _importState.value = ImportState.Working(message, done, total) }
                Library.updateMissing(playlistId, result.tracks, result.missing)
                val title = if (result.tracks.isEmpty()) "No new matches" else "Found ${result.tracks.size} more"
                _importState.value = ImportState.Done(title, listOf(ImportedPlaylist(playlistId, playlist.name, result.tracks.size, result.missing)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importState.value = ImportState.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    fun importText(name: String, text: String) =
        runImport("Reading your list…") { PlaylistImport.fromText(name, text) }

    fun importFile(uri: Uri) = runImport("Opening the file…") {
        readFiles(uri).flatMap { (name, text) -> PlaylistImport.fromText(name, text) }
    }

    fun cancelImport() {
        job?.cancel()
        _importState.value = ImportState.Idle
    }

    fun dismissImport() {
        _importState.value = ImportState.Idle
    }

    fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> importFile(stream)
                    text != null -> PlaylistImport.findLink(text)?.let(::importLink) ?: importText("Shared songs", text)
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let(::importFile)
        }
    }

    private fun runImport(message: String, load: () -> List<ImportedList>) {
        job?.cancel()
        _importState.value = ImportState.Working(message)
        job = viewModelScope.launch {
            try {
                val lists = withContext(Dispatchers.IO) { load() }
                val imported = lists.mapIndexed { i, list ->
                    val prefix = if (lists.size > 1) "Playlist ${i + 1} of ${lists.size}: " else ""
                    val finding = "$prefix“${list.name}”"
                    _importState.value = ImportState.Working(finding, 0, list.items.size)
                    val result = PlaylistImport.resolve(list) { done, total ->
                        _importState.value = ImportState.Working(finding, done, total)
                    }
                    // Missing songs are saved with the playlist so they can be found later.
                    val playlist = Library.createPlaylist(list.name, result.tracks, result.missing)
                    ImportedPlaylist(playlist.id, playlist.name, result.tracks.size, result.missing)
                }
                if (imported.all { it.added == 0 }) throw IOException("Couldn't find any of these songs")
                val title = if (imported.size == 1) "Playlist imported" else "${imported.size} playlists imported"
                _importState.value = ImportState.Done(title, imported, truncated = lists.any { it.truncated })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importState.value = ImportState.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    /** The file's playlist name and text; a ZIP (e.g. Exportify's "Export All") yields one entry per file inside. */
    private fun readFiles(uri: Uri): List<Pair<String, String>> {
        val resolver = getApplication<Application>().contentResolver
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Imported playlist"
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it) } ?: throw IOException("Couldn't open that file")
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return listOf(playlistName(fileName) to bytes.decodeToString())

        val entries = mutableListOf<Pair<String, String>>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && importable.containsMatchIn(name)) {
                    entries += playlistName(name) to readLimited(zip).decodeToString()
                }
            }
        }
        if (entries.isEmpty()) throw IOException("No playlists found in that ZIP file")
        return entries
    }

    private val importable = Regex("""\.(json|csv|m3u8?|txt)$""", RegexOption.IGNORE_CASE)

    // "my_road_trip.csv" -> "my road trip"
    private fun playlistName(fileName: String) =
        fileName.replace(Regex("""(\.songify)?\.(json|csv|m3u8?|txt)$""", RegexOption.IGNORE_CASE), "").replace('_', ' ').trim()

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_FILE_BYTES) throw IOException("That file is too large to be a playlist")
        }
        return out.toByteArray()
    }
}
