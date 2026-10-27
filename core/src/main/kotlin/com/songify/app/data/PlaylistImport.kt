package com.songify.app.data

import com.songify.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** The file format Songify uses to share playlists between phones. */
@Serializable
data class SharedPlaylist(
    val format: String = FORMAT,
    val version: Int = 1,
    val name: String,
    val tracks: List<Track>,
) {
    companion object {
        const val FORMAT = "songify-playlist"
    }
}

sealed interface ImportItem {
    data class Found(val track: Track) : ImportItem
    data class Lookup(val wanted: Wanted) : ImportItem
    /** A pasted song link: added exactly as linked. */
    data class Link(val videoId: String) : ImportItem
}

/** [truncated]: the source only exposed part of the playlist (Spotify's public page stops at 100 songs). */
data class ImportedList(val name: String, val items: List<ImportItem>, val truncated: Boolean = false)

data class ImportResult(val tracks: List<Track>, val missing: List<Wanted>)

/** Reads playlists from other apps' links, shared files and pasted text. */
object PlaylistImport {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private const val PARALLEL_LOOKUPS = 6
    private const val SPOTIFY_PAGE_LIMIT = 100

    private val spotifyLink = Regex("""open\.spotify\.com/(?:intl-[\w-]+/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]+)""")
    private val spotifyShortLink = Regex("""https?://(?:spotify\.link|spotify\.app\.link)/\S+""")
    private val appleLink = Regex("""https?://music\.apple\.com/\S+/(?:playlist|album)/\S+""")
    private val youtubeLink = Regex("""https?://(?:www\.|m\.|music\.)?youtube\.com/\S*[?&]list=[\w-]+\S*""")
    private val anyUrl = Regex("""https?://\S+""")

    /** The importable link inside some shared text, if there is one. */
    fun findLink(text: String): String? =
        listOf(spotifyLink, spotifyShortLink, appleLink, youtubeLink).firstNotNullOfOrNull { regex ->
            regex.find(text)?.value?.let { if (regex == spotifyLink) "https://${it}" else it }
        }

    fun linkSourceName(link: String) = when {
        "spotify" in link -> "Spotify"
        "apple.com" in link -> "Apple Music"
        else -> "Playlist"
    }

    /** Blocking; call off the main thread. */
    fun fromLink(link: String): ImportedList {
        spotifyLink.find(link)?.let { return spotify(it.groupValues[1], it.groupValues[2]) }
        if (spotifyShortLink.matches(link)) {
            val page = fetch(link)
            val full = spotifyLink.find(page.second) ?: spotifyLink.find(page.first)
                ?: throw IOException("Couldn't open that Spotify link")
            return spotify(full.groupValues[1], full.groupValues[2])
        }
        if (appleLink.matches(link)) return apple(link)
        if (youtubeLink.matches(link)) {
            val (name, tracks) = YouTubeMusic.playlist(link)
            return ImportedList(name, tracks.map { ImportItem.Found(it) })
        }
        throw IOException("That link isn't a playlist Songify can read")
    }

    /**
     * Songify JSON, Spotify's "Download your data" files, M3U, CSV (e.g. Exportify / TuneMyMusic)
     * or one song per line. Spotify's export can hold many playlists, hence the list.
     */
    fun fromText(fallbackName: String, text: String): List<ImportedList> {
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.startsWith("{")) {
            val shared = runCatching { AppJson.decodeFromString<SharedPlaylist>(trimmed) }.getOrNull()
            if (shared != null && shared.format == SharedPlaylist.FORMAT) {
                return listOf(ImportedList(shared.name, shared.tracks.map { ImportItem.Found(it) }))
            }
            spotifyExport(trimmed)?.let { return it }
        }
        findLink(trimmed)?.takeIf { trimmed.lines().size == 1 }?.let { return listOf(fromLink(it)) }

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val groups: Map<String?, List<Wanted>> = when {
            lines.any { it.startsWith("#EXTINF") } -> mapOf(null to m3u(lines))
            lines.size > 1 && csvHeader(lines.first()) != null -> csv(lines)
            else -> mapOf(null to lines.filterNot { it.startsWith("#") || anyUrl.matches(it) }.map { Wanted(it) })
        }
        val links = lines.mapNotNull { YouTubeMusic.videoId(it) }.map { ImportItem.Link(it) }
        val lists = groups.filterValues { it.isNotEmpty() || links.isNotEmpty() }.map { (name, songs) ->
            ImportedList(name ?: fallbackName, songs.map { ImportItem.Lookup(it) } + links)
        }
        if (lists.isEmpty()) throw IOException("No songs found in that file")
        return lists
    }

    /** A picked or shared file; a ZIP (e.g. Exportify's "Export All") yields one list per playlist inside. */
    fun fromFile(fileName: String, bytes: ByteArray): List<ImportedList> {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return fromText(playlistName(fileName), bytes.decodeToString())
        val lists = mutableListOf<ImportedList>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && importableFile.containsMatchIn(name)) {
                    lists += runCatching { fromText(playlistName(name), zip.readBytes().decodeToString()) }.getOrDefault(emptyList())
                }
            }
        }
        if (lists.isEmpty()) throw IOException("No playlists found in that ZIP file")
        return lists
    }

    private val importableFile = Regex("""\.(json|csv|m3u8?|txt)$""", RegexOption.IGNORE_CASE)

    // "my_road_trip.songify.json" -> "my road trip"
    private fun playlistName(fileName: String) =
        fileName.replace(Regex("""(\.songify)?\.(json|csv|m3u8?|txt|zip)$""", RegexOption.IGNORE_CASE), "").replace('_', ' ').trim()
            .ifEmpty { "Imported playlist" }

    // Playlist1.json: {"playlists":[{"name", "items":[{"track":{"trackName","artistName"}}]}]}
    // YourLibrary.json: {"tracks":[{"track","artist"}]} — the user's Liked Songs.
    private fun spotifyExport(text: String): List<ImportedList>? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        root.optJSONArray("playlists")?.let { playlists ->
            return (0 until playlists.length()).mapNotNull { i ->
                val p = playlists.optJSONObject(i) ?: return@mapNotNull null
                val items = p.optJSONArray("items") ?: return@mapNotNull null
                val wanted = (0 until items.length()).mapNotNull { j ->
                    val t = items.optJSONObject(j)?.optJSONObject("track") ?: return@mapNotNull null
                    Wanted(t.optString("trackName"), t.optString("artistName")).takeIf { it.title.isNotBlank() }
                }
                ImportedList(p.optString("name", "Spotify playlist"), wanted.map { ImportItem.Lookup(it) })
                    .takeIf { wanted.isNotEmpty() }
            }.ifEmpty { null }
        }
        root.optJSONArray("tracks")?.let { tracks ->
            val wanted = (0 until tracks.length()).mapNotNull { j ->
                val t = tracks.optJSONObject(j) ?: return@mapNotNull null
                Wanted(t.optString("track"), t.optString("artist")).takeIf { it.title.isNotBlank() }
            }
            if (wanted.isNotEmpty()) return listOf(ImportedList("Liked on Spotify", wanted.map { ImportItem.Lookup(it) }))
        }
        return null
    }

    /** Matches every song to a playable track, a few at a time. */
    suspend fun resolve(list: ImportedList, onProgress: (done: Int, total: Int) -> Unit): ImportResult = coroutineScope {
        val gate = Semaphore(PARALLEL_LOOKUPS)
        val done = AtomicInteger()
        val total = list.items.size
        val results = list.items.map { item ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    when (item) {
                        is ImportItem.Found -> item.track
                        is ImportItem.Lookup -> Matcher.find(item.wanted)
                        is ImportItem.Link -> runCatching { YouTubeMusic.track(item.videoId) }.getOrNull()
                    }.also { onProgress(done.incrementAndGet(), total) }
                }
            }
        }.awaitAll()
        ImportResult(
            tracks = results.filterNotNull().distinctBy { it.key },
            missing = list.items.zip(results).mapNotNull { (item, track) ->
                when {
                    track != null -> null
                    item is ImportItem.Lookup -> item.wanted
                    item is ImportItem.Link -> Wanted("Song link ${item.videoId}")
                    else -> null
                }
            },
        )
    }

    // Spotify's public embed page carries the track list; no account or API key needed.
    private fun spotify(type: String, id: String): ImportedList {
        val html = fetch("https://open.spotify.com/embed/$type/$id").second
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IOException("Spotify didn't return that playlist")
        val entity = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        val name = entity.optString("name").ifEmpty { entity.optString("title", "Spotify playlist") }
        val list = entity.optJSONArray("trackList")
        val wanted = if (list != null) {
            (0 until list.length()).map { list.getJSONObject(it) }.map {
                Wanted(it.optString("title"), it.optString("subtitle").replace(' ', ' '), it.optLong("duration") / 1000)
            }
        } else {
            val artists = entity.optJSONArray("artists")
            val artist = entity.optString("subtitle").ifEmpty { artists?.optJSONObject(0)?.optString("name").orEmpty() }
            listOf(Wanted(entity.optString("title", name), artist, entity.optLong("duration") / 1000))
        }
        return ImportedList(
            name,
            wanted.filter { it.title.isNotBlank() }.map { ImportItem.Lookup(it) },
            truncated = type != "track" && wanted.size >= SPOTIFY_PAGE_LIMIT,
        )
    }

    private fun apple(link: String): ImportedList {
        val html = fetch(link).second
        val name = Regex("""<script[^>]*application/ld\+json[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }
            ?.ifEmpty { null } ?: "Apple Music playlist"
        val data = Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IOException("Apple Music didn't return that playlist")
        val found = mutableListOf<Wanted>()
        collectAppleTracks(JSONArray("[$data]"), found)
        if (found.isEmpty()) throw IOException("No songs found on that Apple Music page")
        return ImportedList(name, found.distinct().map { ImportItem.Lookup(it) })
    }

    // Track rows are objects with a title, an artist and a duration in ms, nested deep in page data.
    private fun collectAppleTracks(node: Any?, out: MutableList<Wanted>) {
        when (node) {
            is JSONObject -> {
                val title = node.opt("title") as? String
                val duration = (node.opt("duration") as? Number)?.toLong()
                val artist = (node.opt("artistName") as? String)
                    ?: node.optJSONArray("subtitleLinks")?.optJSONObject(0)?.optString("title")
                if (title != null && duration != null && duration > 0 && !artist.isNullOrBlank()) {
                    out += Wanted(title, artist, duration / 1000)
                    return
                }
                node.keys().forEach { collectAppleTracks(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectAppleTracks(node.opt(i), out)
        }
    }

    private fun m3u(lines: List<String>): List<Wanted> = lines.filter { it.startsWith("#EXTINF") }.map { line ->
        val seconds = line.substringAfter(':').substringBefore(',').trim().toLongOrNull() ?: 0
        val label = line.substringAfter(',').trim()
        val artist = label.substringBefore(" - ", "")
        Wanted(label.substringAfter(" - "), artist, seconds.coerceAtLeast(0))
    }

    private val titleHeaders = setOf("track name", "title", "name", "song", "song name", "track", "track title")

    private class CsvColumns(
        val delimiter: Char,
        val title: Int,
        val artist: Int,
        val duration: Int,
        val isrc: Int,
        val playlist: Int,
    )

    // Soundiiz, TuneMyMusic and Exportify all differ in column names, order and separator.
    private fun csvHeader(line: String): CsvColumns? {
        val delimiter = listOf(',', ';', '\t').maxBy { d -> line.count { it == d } }
        val cols = splitCsv(line, delimiter).map { it.lowercase().trim() }
        val title = cols.indexOfFirst { it in titleHeaders }
        val artist = cols.indexOfFirst { "artist" in it && "uri" !in it && "id" != it.substringAfterLast(' ') }
        if (title < 0 || artist < 0) return null
        return CsvColumns(
            delimiter = delimiter,
            title = title,
            artist = artist,
            duration = cols.indexOfFirst { "duration" in it },
            isrc = cols.indexOfFirst { it == "isrc" },
            playlist = cols.indexOfFirst { it == "playlist name" || it == "playlist" },
        )
    }

    /** Rows grouped by playlist when the file holds several (TuneMyMusic), otherwise one group. */
    private fun csv(lines: List<String>): Map<String?, List<Wanted>> {
        val cols = csvHeader(lines.first()) ?: return emptyMap()
        return lines.drop(1).map { splitCsv(it, cols.delimiter) }.mapNotNull { row ->
            val title = row.getOrNull(cols.title)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val duration = row.getOrNull(cols.duration)?.toLongOrNull() ?: 0
            val wanted = Wanted(
                title = title,
                artist = row.getOrNull(cols.artist).orEmpty(),
                // Durations are ms in Exportify-style exports, seconds elsewhere.
                durationSec = if (duration > 10_000) duration / 1000 else duration,
                isrc = row.getOrNull(cols.isrc).orEmpty(),
            )
            row.getOrNull(cols.playlist)?.ifBlank { null } to wanted
        }.groupBy({ it.first }, { it.second })
    }

    private fun splitCsv(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> { out += cell.toString().trim(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString().trim()
        return out
    }

    /** Returns the final URL and the body. */
    private fun fetch(url: String): Pair<String, String> {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
        Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Couldn't open the link (HTTP ${r.code})")
            return r.request.url.toString() to r.body!!.string()
        }
    }
}
