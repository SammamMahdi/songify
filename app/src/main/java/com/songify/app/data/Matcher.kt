package com.songify.app.data

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** A song known only by its name, e.g. from a Spotify playlist or a pasted list. */
@Serializable
data class Wanted(
    val title: String,
    val artist: String = "",
    val durationSec: Long = 0,
    /** International Standard Recording Code: identifies the exact recording, when the source provides it. */
    val isrc: String = "",
) {
    override fun toString() = if (artist.isBlank()) title else "$title · $artist"
}

/**
 * Finds the best YouTube Music recording for a song known by title/artist/length.
 * Scores candidates on title and artist similarity and duration, and penalizes
 * covers, live versions, remixes and the like unless they were asked for.
 */
object Matcher {
    private const val MIN_SCORE = 0.55
    // Fallback searches drop the artist from the query, so they must match more convincingly.
    private const val FALLBACK_MIN_SCORE = 0.75
    // An ISRC search returns that exact recording; the score only guards against unrelated hits.
    private const val ISRC_MIN_SCORE = 0.4
    private val isrcPattern = Regex("^[A-Z]{2}[A-Z0-9]{3}\\d{7}$")
    private const val CANDIDATES = 8

    private val bracketed = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val dashSuffix = Regex("""\s+-\s+.*(remaster|version|edit|mix|mono|stereo|from).*$""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")
    private val stopWords = setOf("the", "a", "feat", "ft", "featuring", "and", "with", "official", "audio", "video", "lyrics", "lyric")
    private val variantWords = listOf(
        "live", "cover", "remix", "karaoke", "instrumental", "sped", "slowed", "reverb",
        "8d", "acoustic", "lofi", "nightcore", "mashup", "unplugged",
    )

    private val cache = ConcurrentHashMap<String, Track>()

    fun find(wanted: Wanted): Track? {
        val cacheKey = "${normalize(wanted.title)}|${normalize(wanted.artist)}|${wanted.isrc}"
        cache[cacheKey]?.let { return it }
        val leadArtist = wanted.artist.split(',', '&', ';').first().trim()
        val query = listOf(wanted.title, leadArtist).filter { it.isNotBlank() }.joinToString(" ")
        val cleanTitle = wanted.title.replace(bracketed, " ").replace(dashSuffix, " ").trim()
        val isrc = wanted.isrc.uppercase().replace("-", "").trim().takeIf { isrcPattern.matches(it) }
        // Exact recording by ISRC first, then title + artist, then looser queries, then Audius.
        val best = isrc?.let { best(wanted, search { MusicSearch.youtube(it) }, ISRC_MIN_SCORE) }
            ?: best(wanted, search { MusicSearch.youtube(query) }, MIN_SCORE)
            ?: cleanTitle.takeIf { it != query }?.let { best(wanted, search { MusicSearch.youtube(it) }, FALLBACK_MIN_SCORE) }
            ?: best(wanted, search { MusicSearch.audius(query) }, MIN_SCORE)
        best?.let { cache[cacheKey] = it }
        return best
    }

    private fun search(fetch: () -> List<Track>) = runCatching(fetch).getOrDefault(emptyList())

    private fun best(wanted: Wanted, candidates: List<Track>, minScore: Double): Track? =
        candidates.take(CANDIDATES)
            .map { it to score(wanted, it) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= minScore }
            ?.first

    fun normalize(s: String): String = tokens(s).joinToString(" ")

    private fun score(wanted: Wanted, c: Track): Double {
        val durationScore = if (wanted.durationSec > 0 && c.durationSec > 0) {
            (1 - abs(wanted.durationSec - c.durationSec) / 20.0).coerceAtLeast(0.0)
        } else 0.5
        val wantedRaw = "${wanted.title} ${wanted.artist}".lowercase()
        val penalty = if (variantWords.any { it in c.title.lowercase() && it !in wantedRaw }) 0.3 else 0.0

        if (wanted.artist.isBlank()) {
            // Free text such as "Artist - Title" in either order.
            return 0.85 * dice(tokens(wanted.title), tokens("${c.title} ${c.artist}")) + 0.15 * durationScore - penalty
        }
        val titleScore = dice(tokens(wanted.title), tokens(c.title))
        val candidateArtist = tokens("${c.artist} ${c.title}")
        val artistScore = wanted.artist.split(',', '&', ';')
            .map { tokens(it) }
            .filter { it.isNotEmpty() }
            .maxOfOrNull { name -> name.count { it in candidateArtist }.toDouble() / name.size } ?: 0.0
        return 0.5 * titleScore + 0.3 * artistScore + 0.2 * durationScore - penalty
    }

    private fun tokens(s: String): Set<String> =
        s.replace(bracketed, " ").replace(dashSuffix, " ").lowercase()
            .split(nonWord).filter { it.isNotEmpty() && it !in stopWords }.toSet()

    private fun dice(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.count { it in b } / (a.size + b.size)
}
