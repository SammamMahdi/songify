package com.songify.app.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.songify.app.data.AppJson
import com.songify.app.data.StreamResolver
import com.songify.app.data.Track
import com.songify.app.net.Http
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

sealed interface Offline {
    data object Queued : Offline
    data class Downloading(val percent: Int) : Offline
    data object Done : Offline
    data object Failed : Offline
}

/**
 * Offline songs, Spotify-style: stored privately in the app (playable only inside Songify),
 * managed by Media3's DownloadManager. Every download passes the same full-song check as streaming.
 */
object Downloads {
    private const val PROGRESS_POLL_MS = 1000L

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())

    val cache: SimpleCache by lazy {
        SimpleCache(File(appContext.filesDir, "offline"), NoOpCacheEvictor(), StandaloneDatabaseProvider(appContext))
    }

    val manager: DownloadManager by lazy {
        DownloadManager(appContext, StandaloneDatabaseProvider(appContext), cache, upstream(), Executors.newFixedThreadPool(3)).apply {
            maxParallelDownloads = 3
            requirements = Requirements(Requirements.NETWORK)
            addListener(Listener)
        }
    }

    private val _states = MutableStateFlow<Map<String, Offline>>(emptyMap())
    /** Download state per [Track.key]. */
    val states = _states.asStateFlow()

    private val _downloaded = MutableStateFlow<List<Track>>(emptyList())
    /** Finished downloads, newest first. */
    val downloaded = _downloaded.asStateFlow()

    private val _failures = MutableSharedFlow<Track>(extraBufferCapacity = 16)
    /** Songs that couldn't be downloaded, so the UI can say so. */
    val failures = _failures.asSharedFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        manager.resumeDownloads()
        refresh()
    }

    /** Streams resolve songify:// URIs to verified full-length audio. */
    fun upstream(): DataSource.Factory = ResolvingDataSource.Factory(ChunkedDataSource.Factory(OkHttpDataSource.Factory(Http.client))) { spec ->
        if (spec.uri.scheme == "songify") spec.withUri(StreamResolver.resolve(spec.uri)) else spec
    }

    /** For playback: downloaded songs play from storage with no network; others stream (and aren't cached). */
    fun playbackFactory(): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream())
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Returns how many songs were queued (already downloaded ones are skipped). */
    fun download(tracks: List<Track>): Int {
        val todo = tracks.distinctBy { it.key }.filter { _states.value[it.key] != Offline.Done }
        todo.forEach { track ->
            val request = DownloadRequest.Builder(track.key, Uri.parse(track.uri))
                .setCustomCacheKey(track.key)
                .setData(AppJson.encodeToString(Track.serializer(), track).encodeToByteArray())
                .build()
            DownloadService.sendAddDownload(appContext, SongDownloadService::class.java, request, false)
        }
        _states.value = _states.value + todo.associate { it.key to Offline.Queued }
        return todo.size
    }

    fun remove(tracks: List<Track>) {
        tracks.forEach { DownloadService.sendRemoveDownload(appContext, SongDownloadService::class.java, it.key, false) }
        _states.value = _states.value - tracks.map { it.key }.toSet()
        _downloaded.value = _downloaded.value.filter { t -> tracks.none { it.key == t.key } }
    }

    fun removeAll() = remove(_downloaded.value)

    /** Bytes used by downloaded songs. */
    fun bytesUsed(): Long = runCatching { cache.cacheSpace }.getOrDefault(0)

    private fun refresh() {
        val states = mutableMapOf<String, Offline>()
        val done = mutableListOf<Pair<Track, Long>>()
        var active = false
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                val track = d.track() ?: continue
                states[track.key] = when (d.state) {
                    Download.STATE_COMPLETED -> Offline.Done.also { done += track to d.updateTimeMs }
                    Download.STATE_FAILED -> Offline.Failed
                    Download.STATE_DOWNLOADING -> Offline.Downloading(d.percentDownloaded.toInt().coerceIn(0, 100)).also { active = true }
                    Download.STATE_QUEUED, Download.STATE_RESTARTING -> Offline.Queued.also { active = true }
                    else -> continue
                }
            }
        }
        // Include live progress that the index hasn't persisted yet.
        manager.currentDownloads.forEach { d ->
            val track = d.track() ?: return@forEach
            if (d.state == Download.STATE_DOWNLOADING) {
                states[track.key] = Offline.Downloading(d.percentDownloaded.toInt().coerceIn(0, 100))
                active = true
            }
        }
        _states.value = states
        _downloaded.value = done.sortedByDescending { it.second }.map { it.first }
        handler.removeCallbacks(poll)
        if (active) handler.postDelayed(poll, PROGRESS_POLL_MS)
    }

    private val poll = Runnable { refresh() }

    private fun Download.track(): Track? =
        runCatching { AppJson.decodeFromString(Track.serializer(), request.data.decodeToString()) }.getOrNull()

    private object Listener : DownloadManager.Listener {
        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
            if (download.state == Download.STATE_FAILED) download.track()?.let { _failures.tryEmit(it) }
            refresh()
        }

        override fun onDownloadRemoved(manager: DownloadManager, download: Download) = refresh()
    }
}
