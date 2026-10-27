package com.songify.app.data

import com.songify.app.net.Http
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class NotFullSongException(message: String) : IOException(message)

enum class AudioQuality(val label: String, val detail: String) {
    AUTO("Automatic", "Adjusts to your connection"),
    LOW("Low", "About 50 kbps · uses the least data"),
    NORMAL("Normal", "About 128 kbps"),
    HIGH("High", "Up to 160 kbps · best on Wi-Fi"),
}

/**
 * Turns songify:// URIs into stream URLs, rejecting anything that isn't the complete song.
 * Nothing is downloaded: the check asks for a single byte and reads the total size header.
 */
object StreamResolver {
    // Stream URLs expire after ~6h.
    private const val TTL_MS = 4 * 60 * 60 * 1000L

    // Size / duration below this means the server holds a preview, not the listed song.
    private const val MIN_KBPS = 32

    /** The quality to stream at right now; each app plugs in its own (e.g. network-aware on phones). */
    @Volatile var quality: () -> AudioQuality = { AudioQuality.NORMAL }

    /** Restrict to AAC/MP3 for players that can't decode Opus. */
    @Volatile var aacOnly = false

    private class Entry(val url: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Blocking; call off the main thread. Returns the playable stream URL for a [Track.uri]. */
    fun resolve(trackUri: String): String {
        val match = Regex("""^songify://(\w+)/([^?]+)(?:\?d=(\d+))?""").find(trackUri)
            ?: throw IOException("Bad track URI: $trackUri")
        val (host, id) = match.destructured
        val listedSec = match.groupValues[3].toLongOrNull() ?: 0
        val quality = quality()
        val key = "$host/$id/$quality/$aacOnly"
        cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.url }

        val (url, durationSec) = when (host) {
            "youtube" -> YouTubeMusic.audioStream(id, quality, aacOnly)
            "audius" -> Audius.streamUrl(id) to listedSec // Audius serves a single MP3 quality
            else -> throw IOException("Unknown source: $host")
        }
        verifyFullLength(url, durationSec)
        cache[key] = Entry(url, System.currentTimeMillis() + TTL_MS)
        return url
    }

    /**
     * Forgets the stream URL for [trackUri] so the next [resolve] fetches a fresh one. Stream links are
     * occasionally refused (HTTP 403) by the host; a new link for the same song usually works.
     */
    fun invalidate(trackUri: String) {
        val match = Regex("""^songify://(\w+)/([^?]+)""").find(trackUri) ?: return
        val (host, id) = match.destructured
        cache.keys.removeIf { it.startsWith("$host/$id/") }
        if (host == "youtube") YouTubeMusic.forget(id)
    }

    /** Warms the cache so the track starts instantly when played. */
    fun prefetch(trackUri: String) {
        runCatching { resolve(trackUri) }
    }

    private fun verifyFullLength(url: String, durationSec: Long) {
        val probe = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        Http.client.newCall(probe).execute().use { r ->
            if (r.code != 200 && r.code != 206) throw IOException("Stream unavailable (HTTP ${r.code})")
            val type = r.header("Content-Type").orEmpty()
            if (!type.startsWith("audio/")) throw NotFullSongException("Not an audio stream")
            val size = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: r.body?.contentLength()?.takeIf { it > 0 }
                ?: return // Unknown size; the player's duration check still applies.
            if (durationSec > 0 && size * 8 / 1000 / durationSec < MIN_KBPS) {
                throw NotFullSongException("Only a preview is available")
            }
        }
    }
}
