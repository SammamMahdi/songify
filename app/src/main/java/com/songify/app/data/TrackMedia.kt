package com.songify.app.data

import android.net.Uri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

// mediaId carries the URI because controllers can't hand stream URIs to the session.
fun Track.toMediaItem(radio: Boolean = false): MediaItem = MediaItem.Builder()
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

fun Track.Companion.fromMediaItem(item: MediaItem): Track? {
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
