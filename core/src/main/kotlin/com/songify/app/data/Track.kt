package com.songify.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shorter than this is a clip, intro or preview, not a song. */
const val MIN_SONG_SECONDS = 60L

const val EXTRA_LISTED_SECONDS = "listed_seconds"

/** Marks queue items added by radio, so the queue keeps extending even with autoplay off. */
const val EXTRA_RADIO = "radio"

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
enum class Source(val label: String) { YOUTUBE("Songify"), AUDIUS("Audius") }

@Serializable
data class Track(
    val source: Source,
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long = 0,
    val artworkUrl: String? = null,
) {
    /** Stable identity across search results, playlists and the player queue. */
    val key: String get() = "${source.name}:$id"

    /** Resolved to a verified full-length audio stream by [StreamResolver] when played. */
    val uri: String get() = "songify://${source.name.lowercase()}/$id?d=$durationSec"

    val shareText: String get() = "$title · $artist (shared from Songify)"

    companion object {
        /** Parses the source and id back out of a [uri]. */
        fun keyFromUri(uri: String): String? {
            val match = Regex("""^songify://(\w+)/([^?]+)""").find(uri) ?: return null
            val source = Source.entries.firstOrNull { it.name.equals(match.groupValues[1], ignoreCase = true) } ?: return null
            return "${source.name}:${match.groupValues[2]}"
        }
    }
}
