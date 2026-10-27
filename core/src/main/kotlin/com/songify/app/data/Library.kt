package com.songify.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

const val LIKED_ID = "liked"

@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tracks: List<Track> = emptyList(),
    /** Imported songs Songify couldn't find yet; kept so the user can fix them later. */
    val missing: List<Wanted> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class LibraryData(
    val playlists: List<Playlist> = emptyList(),
    val liked: List<Track> = emptyList(),
    val recent: List<Track> = emptyList(),
) {
    fun playlist(id: String): Playlist? =
        if (id == LIKED_ID) Playlist(LIKED_ID, "Liked Songs", liked) else playlists.firstOrNull { it.id == id }

    fun isLiked(track: Track) = liked.any { it.key == track.key }
}

/** The user's library, kept in a single JSON file on the device. No server, no database. */
object Library {
    private const val MAX_RECENT = 50

    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private val _data = MutableStateFlow(LibraryData())
    val data = _data.asStateFlow()

    /** Loads the library from [dir] (the app's private files folder). */
    fun init(dir: File) {
        file = File(dir, "library.json")
        _data.value = runCatching {
            AppJson.decodeFromString<LibraryData>(file.readText())
        }.getOrDefault(LibraryData())
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList(), missing: List<Wanted> = emptyList()): Playlist {
        val playlist = Playlist(
            name = name.trim().ifEmpty { "My playlist" },
            tracks = tracks.distinctBy { it.key },
            missing = missing.distinct(),
        )
        update { it.copy(playlists = listOf(playlist) + it.playlists) }
        return playlist
    }

    /** Settles missing songs: [found] tracks are added, and only [stillMissing] stay listed. */
    fun updateMissing(id: String, found: List<Track>, stillMissing: List<Wanted>) = editPlaylist(id) { p ->
        val have = p.tracks.map { it.key }.toSet()
        p.copy(tracks = p.tracks + found.filter { it.key !in have }.distinctBy { it.key }, missing = stillMissing)
    }

    /** The user picked [track] for a song that wasn't found automatically (or dismissed it when null). */
    fun resolveMissing(id: String, wanted: Wanted, track: Track?) = editPlaylist(id) { p ->
        val tracks = if (track != null && p.tracks.none { it.key == track.key }) p.tracks + track else p.tracks
        p.copy(tracks = tracks, missing = p.missing - wanted)
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deletePlaylist(id: String) = update { lib -> lib.copy(playlists = lib.playlists.filterNot { it.id == id }) }

    /** Returns how many tracks were new to the playlist. */
    fun addToPlaylist(id: String, tracks: List<Track>): Int {
        val existing = _data.value.playlist(id)?.tracks?.map { it.key }?.toSet() ?: return 0
        val fresh = tracks.filter { it.key !in existing }.distinctBy { it.key }
        if (id == LIKED_ID) update { it.copy(liked = fresh.reversed() + it.liked) }
        else editPlaylist(id) { it.copy(tracks = it.tracks + fresh) }
        return fresh.size
    }

    fun removeFromPlaylist(id: String, track: Track) {
        if (id == LIKED_ID) update { lib -> lib.copy(liked = lib.liked.filterNot { it.key == track.key }) }
        else editPlaylist(id) { p -> p.copy(tracks = p.tracks.filterNot { it.key == track.key }) }
    }

    /** Returns true if the track is now liked. */
    fun toggleLike(track: Track): Boolean {
        val liked = _data.value.isLiked(track)
        update { lib ->
            lib.copy(liked = if (liked) lib.liked.filterNot { it.key == track.key } else listOf(track) + lib.liked)
        }
        return !liked
    }

    fun addRecent(track: Track) = update { lib ->
        lib.copy(recent = (listOf(track) + lib.recent.filterNot { it.key == track.key }).take(MAX_RECENT))
    }

    fun clearRecent() = update { it.copy(recent = emptyList()) }

    private fun editPlaylist(id: String, change: (Playlist) -> Playlist) = update { lib ->
        lib.copy(playlists = lib.playlists.map {
            if (it.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it
        })
    }

    private fun update(change: (LibraryData) -> LibraryData) {
        _data.update(change)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300) // coalesce bursts of edits into one write
            writeAtomically(file, AppJson.encodeToString(LibraryData.serializer(), _data.value))
        }
    }
}

/** Writes to a temp file and swaps it in, so a crash mid-write never corrupts [file]. */
fun writeAtomically(file: File, text: String) {
    val temp = File(file.parentFile, "${file.name}.tmp")
    runCatching {
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
