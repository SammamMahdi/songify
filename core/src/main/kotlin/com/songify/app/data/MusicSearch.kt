package com.songify.app.data

/** Cached song search across sources. Repeat queries (typing, back navigation, imports) return instantly. */
object MusicSearch {
    private const val MAX_ENTRIES = 60
    private const val TTL_MS = 15 * 60 * 1000L

    private class Entry(val tracks: List<Track>, val at: Long)

    private val cache = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > MAX_ENTRIES
    }

    fun youtube(query: String): List<Track> = cached("yt", query) { YouTubeMusic.search(it) }

    fun audius(query: String): List<Track> = cached("au", query) { Audius.search(it) }

    private fun cached(source: String, query: String, fetch: (String) -> List<Track>): List<Track> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        val key = "$source:${q.lowercase()}"
        synchronized(cache) {
            cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.tracks }
        }
        val tracks = fetch(q)
        synchronized(cache) { cache[key] = Entry(tracks, System.currentTimeMillis()) }
        return tracks
    }

    /** Normalized "title|artist" used to drop the same song arriving from several sources. */
    fun dedupeKey(track: Track) = "${Matcher.normalize(track.title)}|${Matcher.normalize(track.artist)}"
}
