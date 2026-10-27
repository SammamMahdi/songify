package com.songify.app.data

import com.songify.app.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

/** [synced] lines carry timestamps; [plain] is the fallback when only unsynced text exists. */
data class Lyrics(val synced: List<LyricLine>, val plain: String?)

/** Lyrics from LRCLIB, a free, open lyrics database (no account or key). */
object LyricsRepo {
    private const val API = "https://lrclib.net/api"
    private const val USER_AGENT = "Songify (personal Android music player)"

    private val noise = Regex("""\s*[(\[][^)\]]*[)\]]|\s+-\s+.*$""")
    private val timestamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

    private class Entry(val lyrics: Lyrics?)

    // Songs without lyrics are cached too, so we don't ask again; network failures are not.
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Blocking; call off the main thread. Throws on network failure. */
    fun get(track: Track): Lyrics? {
        cache[track.key]?.let { return it.lyrics }
        return fetch(track).also { cache[track.key] = Entry(it) }
    }

    private fun fetch(track: Track): Lyrics? {
        val title = track.title.replace(noise, "").trim()
        val artist = track.artist.split(',', '&').first().trim()
        val exact = "$API/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (track.durationSec > 0) addQueryParameter("duration", track.durationSec.toString()) }
            .build()
        request(exact.toString())?.let { return parse(JSONObject(it)) }

        val search = "$API/search".toHttpUrl().newBuilder().addQueryParameter("q", "$title $artist").build()
        val results = request(search.toString())?.let(::JSONArray) ?: return null
        val best = (0 until results.length()).map { results.getJSONObject(it) }
            .filter { track.durationSec <= 0 || abs(it.optDouble("duration", 0.0) - track.durationSec) < 8 }
            .firstOrNull { !it.isNull("syncedLyrics") || !it.isNull("plainLyrics") } ?: return null
        return parse(best)
    }

    private fun parse(json: JSONObject): Lyrics? {
        val synced = json.optString("syncedLyrics").takeIf { !json.isNull("syncedLyrics") }.orEmpty()
            .lines()
            .flatMap { line ->
                val text = line.replace(timestamp, "").trim()
                timestamp.findAll(line).map { m ->
                    val ms = m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()
                    LyricLine(ms, text)
                }.toList()
            }
            .sortedBy { it.timeMs }
        val plain = json.optString("plainLyrics").takeIf { !json.isNull("plainLyrics") && it.isNotBlank() }
        if (synced.isEmpty() && plain == null) return null
        return Lyrics(synced, plain)
    }

    private fun request(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw java.io.IOException("Lyrics HTTP ${r.code}")
            return r.body?.string()
        }
    }
}
