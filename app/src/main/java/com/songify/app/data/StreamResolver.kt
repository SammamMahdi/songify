package com.songify.app.data

import android.net.Uri
import com.songify.app.net.Http
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class NotFullSongException(message: String) : IOException(message)

/**
 * Turns songify:// URIs into stream URLs, rejecting anything that isn't the complete song.
 * Nothing is downloaded: the check asks for a single byte and reads the total size header.
 */
object StreamResolver {
    // YouTube stream URLs expire after ~6h.
    private const val TTL_MS = 4 * 60 * 60 * 1000L

    // Size / duration below this means the server holds a preview, not the listed song.
    private const val MIN_KBPS = 32

    private class Entry(val url: String, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Blocking; call off the main thread. */
    fun resolve(uri: Uri): Uri {
        val host = uri.host
        val id = uri.lastPathSegment ?: throw IOException("Bad track URI: $uri")
        val quality = Settings.effectiveQuality()
        val key = "$host/$id/$quality"
        cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return Uri.parse(it.url) }

        val listedSec = uri.getQueryParameter("d")?.toLongOrNull() ?: 0
        val (url, durationSec) = when (host) {
            "youtube" -> YouTubeMusic.audioStream(id, quality)
            "audius" -> Audius.streamUrl(id) to listedSec // Audius serves a single MP3 quality
            else -> throw IOException("Unknown source: $host")
        }
        verifyFullLength(url, durationSec)
        cache[key] = Entry(url, System.currentTimeMillis() + TTL_MS)
        return Uri.parse(url)
    }

    /** Warms the cache so the track starts instantly when played. */
    fun prefetch(uri: String) {
        runCatching { resolve(Uri.parse(uri)) }
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
