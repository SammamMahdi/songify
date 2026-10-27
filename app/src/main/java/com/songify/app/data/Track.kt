package com.songify.app.data

import android.net.Uri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    /** Resolved to a verified full-length audio stream by [StreamResolver] when the player loads it. */
    val uri: String get() = "songify://${source.name.lowercase()}/$id?d=$durationSec"

    val shareText: String get() = "$title · $artist (shared from Songify)"

    // mediaId carries the URI because controllers can't hand stream URIs to the session.
    fun toMediaItem(radio: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .setExtras(bundleOf(EXTRA_LISTED_SECONDS to durationSec, EXTRA_RADIO to radio))
                .build()
        )
        .build()

    companion object {
        fun fromMediaItem(item: MediaItem): Track? {
            val uri = Uri.parse(item.mediaId)
            if (uri.scheme != "songify") return null
            val source = Source.entries.firstOrNull { it.name.equals(uri.host, ignoreCase = true) } ?: return null
            val meta = item.mediaMetadata
            return Track(
                source = source,
                id = uri.lastPathSegment ?: return null,
                title = meta.title?.toString().orEmpty(),
                artist = meta.artist?.toString().orEmpty(),
                durationSec = uri.getQueryParameter("d")?.toLongOrNull() ?: 0,
                artworkUrl = meta.artworkUri?.toString(),
            )
        }
    }
}
