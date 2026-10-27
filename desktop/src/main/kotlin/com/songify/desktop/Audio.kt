package com.songify.desktop

import com.songify.app.data.AppJson
import com.songify.app.data.StreamResolver
import com.songify.app.data.Track
import com.songify.app.data.writeAtomically
import com.songify.app.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Where Songify keeps its files: %APPDATA%\Songify on Windows. */
object AppDirs {
    val data: File by lazy {
        // Developer probes point this at a scratch folder so they never touch a real library.
        System.getProperty("songify.dataDir")?.let { return@lazy File(it).apply { mkdirs() } }
        val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".songify")
        File(base, "Songify").apply { mkdirs() }
    }
    val cache: File get() = File(data, "cache").apply { mkdirs() }
    val defaultDownloads: File get() = File(data, "downloads")

    /** The download folder chosen in Settings, or Songify's own. */
    val downloads: File
        get() = DesktopSettings.prefs.value.downloadDir?.let(::File)?.takeIf { it.isDirectory || it.mkdirs() }
            ?: defaultDownloads.apply { mkdirs() }
}

/**
 * Songs are played from local files: downloaded ones directly, others fetched into a small cache first.
 * Fetching uses 1 MB ranged requests, which run at full speed (long single connections get throttled).
 */
object AudioFiles {
    private const val CHUNK_BYTES = 1L shl 20
    private const val MAX_CACHED = 40
    private const val MAX_ATTEMPTS = 3

    fun downloaded(track: Track): File? = find(AppDirs.downloads, track)

    /** A local file for [track]: downloaded, cached, or fetched now. Blocking. */
    fun fileFor(track: Track): File =
        downloaded(track)
            ?: find(AppDirs.cache, track)?.also { it.setLastModified(System.currentTimeMillis()) }
            ?: fetch(track, AppDirs.cache).also { trimCache() }

    private class RefusedException(code: Int) : IOException("The music host refused the download (HTTP $code)")

    /** Downloads [track]'s verified full-length audio into [dir]. Blocking. */
    fun fetch(track: Track, dir: File): File {
        // Stream links are occasionally refused; a fresh link for the same song usually works.
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return fetchOnce(track, dir)
            } catch (e: RefusedException) {
                StreamResolver.invalidate(track.uri)
            }
        }
        return fetchOnce(track, dir)
    }

    private fun fetchOnce(track: Track, dir: File): File {
        val url = StreamResolver.resolve(track.uri)
        val part = File(dir, "${safeName(track)}.part")
        var position = 0L
        var total: Long? = null
        var extension = "m4a"
        part.outputStream().use { out ->
            while (total == null || position < total!!) {
                val request = Request.Builder().url(url).header("Range", "bytes=$position-${position + CHUNK_BYTES - 1}").build()
                Http.client.newCall(request).execute().use { r ->
                    if (r.code == 403 || r.code == 410) throw RefusedException(r.code)
                    if (r.code != 200 && r.code != 206) throw IOException("Couldn't download (HTTP ${r.code})")
                    if (total == null) {
                        total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body?.contentLength()?.takeIf { it > 0 }
                        if (r.header("Content-Type").orEmpty().contains("mpeg")) extension = "mp3"
                    }
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    out.write(bytes)
                    position += bytes.size
                    // A server that ignores ranges sends everything at once.
                    if (r.code == 200 || bytes.isEmpty()) total = position
                }
            }
        }
        val file = File(dir, "${safeName(track)}.$extension")
        file.delete()
        if (extension == "m4a") {
            flatten(part, file)
            part.delete()
        } else if (!part.renameTo(file)) {
            throw IOException("Couldn't save the song")
        }
        return file
    }

    // AAC streams come as fragmented MP4, which the decoder can't read; rewrite the container only.
    private fun flatten(fragmented: File, out: File) {
        val movie = MovieCreator.build(fragmented.absolutePath)
        FileOutputStream(out).channel.use { DefaultMp4Builder().build(movie).writeContainer(it) }
    }

    fun delete(track: Track, dir: File) {
        listOf("m4a", "mp3").forEach { File(dir, "${safeName(track)}.$it").delete() }
    }

    private fun find(dir: File, track: Track): File? =
        listOf("m4a", "mp3").map { File(dir, "${safeName(track)}.$it") }.firstOrNull { it.exists() && it.length() > 0 }

    private fun safeName(track: Track) = track.key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun trimCache() {
        AppDirs.cache.listFiles { f -> f.extension in setOf("m4a", "mp3") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED)
            ?.forEach { it.delete() }
    }
}

sealed interface Offline {
    data object Queued : Offline
    data object Downloading : Offline
    data object Done : Offline
    data object Failed : Offline
}

/** Offline songs on the computer, kept in Songify's own folder. */
object DesktopDownloads {
    private val indexFile get() = File(AppDirs.data, "downloads.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)

    private val _downloaded = MutableStateFlow<List<Track>>(emptyList())
    val downloaded = _downloaded.asStateFlow()

    private val _states = MutableStateFlow<Map<String, Offline>>(emptyMap())
    val states = _states.asStateFlow()

    private val _failures = MutableSharedFlow<Track>(extraBufferCapacity = 16)
    val failures = _failures.asSharedFlow()

    fun init() {
        val saved = runCatching { AppJson.decodeFromString(ListSerializer(Track.serializer()), indexFile.readText()) }.getOrDefault(emptyList())
        _downloaded.value = saved.filter { AudioFiles.downloaded(it) != null }
        _states.value = _downloaded.value.associate { it.key to Offline.Done }
    }

    /** Returns how many songs were queued. */
    fun download(tracks: List<Track>): Int {
        val busy = setOf(Offline.Done, Offline.Queued, Offline.Downloading)
        val todo = tracks.distinctBy { it.key }.filter { _states.value[it.key] !in busy }
        _states.update { it + todo.associate { t -> t.key to Offline.Queued } }
        todo.forEach { track ->
            scope.launch {
                gate.withPermit {
                    _states.update { it + (track.key to Offline.Downloading) }
                    runCatching { AudioFiles.fetch(track, AppDirs.downloads) }
                        .onSuccess {
                            _downloaded.update { list -> listOf(track) + list.filterNot { it.key == track.key } }
                            _states.update { it + (track.key to Offline.Done) }
                            save()
                        }
                        .onFailure {
                            _states.update { it + (track.key to Offline.Failed) }
                            _failures.tryEmit(track)
                        }
                }
            }
        }
        return todo.size
    }

    fun remove(tracks: List<Track>) {
        val keys = tracks.map { it.key }.toSet()
        tracks.forEach { AudioFiles.delete(it, AppDirs.downloads) }
        _downloaded.update { list -> list.filterNot { it.key in keys } }
        _states.update { it - keys }
        save()
    }

    fun bytesUsed(): Long = AppDirs.downloads.listFiles()?.sumOf { it.length() } ?: 0

    /** Moves downloaded songs into [folder] and keeps downloading there. Returns how many were moved. */
    suspend fun changeFolder(folder: File): Int = withContext(Dispatchers.IO) {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Couldn't create that folder")
        if (!folder.canWrite()) throw IOException("Songify can't save files in that folder")
        val old = AppDirs.downloads
        var moved = 0
        if (old.canonicalPath != folder.canonicalPath) {
            old.listFiles { f -> f.extension in setOf("m4a", "mp3") }?.forEach { f ->
                f.copyTo(File(folder, f.name), overwrite = true)
                f.delete()
                moved++
            }
        }
        DesktopSettings.update { it.copy(downloadDir = folder.absolutePath) }
        moved
    }

    private fun save() = writeAtomically(indexFile, AppJson.encodeToString(ListSerializer(Track.serializer()), _downloaded.value))
}
