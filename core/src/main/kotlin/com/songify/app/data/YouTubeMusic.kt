package com.songify.app.data

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import kotlin.math.abs

/** YouTube Music via NewPipeExtractor. Only audio-only streams are ever requested; no video. */
object YouTubeMusic {
    private const val MAX_PLAYLIST_PAGES = 20
    private const val MAX_RADIO_SONG_SECONDS = 600L
    private const val INFO_TTL_MS = 60 * 60 * 1000L

    private val service = ServiceList.YouTube

    private val videoLink = Regex("""(?:youtube\.com/(?:watch\?(?:\S*&)?v=|shorts/|embed/|live/)|youtu\.be/)([\w-]{11})""")

    private val infoCache = object : LinkedHashMap<String, Pair<StreamInfo, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<StreamInfo, Long>>) = size > 30
    }

    fun search(query: String): List<Track> {
        val handler = service.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        return SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull(::toTrack)
    }

    /** Reads a YouTube or YouTube Music playlist, following pages. */
    fun playlist(url: String): Pair<String, List<Track>> {
        val normalized = url.replace("music.youtube.com", "www.youtube.com")
        val info = PlaylistInfo.getInfo(service, normalized)
        val items = info.relatedItems.toMutableList()
        var page: Page? = info.nextPage
        repeat(MAX_PLAYLIST_PAGES) {
            if (!Page.isValid(page)) return@repeat
            val more = PlaylistInfo.getMoreItems(service, normalized, page)
            items += more.items
            page = more.nextPage
        }
        return info.name to items.mapNotNull(::toTrack)
    }

    /** The video id in a pasted song link (watch, short, share or embed links), if there is one. */
    fun videoId(text: String): String? = videoLink.find(text)?.groupValues?.get(1)

    /** A song the user picked by pasting its link. */
    fun track(videoId: String): Track {
        val info = info(videoId)
        return Track(
            source = Source.YOUTUBE,
            id = videoId,
            title = info.name,
            artist = info.uploaderName.orEmpty().removeSuffix(" - Topic"),
            durationSec = info.duration,
            artworkUrl = largest(info.thumbnails),
        )
    }

    /**
     * Songs similar to [track], for radio and autoplay: the song's music radio mix, which (unlike
     * generic related videos) only contains music. Falls back to the artist's songs.
     */
    fun similar(track: Track): List<Track> {
        val mix = if (track.source == Source.YOUTUBE) {
            runCatching {
                PlaylistInfo.getInfo(service, "https://www.youtube.com/watch?v=${track.id}&list=RDAMVM${track.id}").relatedItems
                    .filter { it.duration <= MAX_RADIO_SONG_SECONDS }
                    .mapNotNull(::toTrack)
            }.getOrDefault(emptyList())
        } else emptyList()
        val fill = if (mix.size < 5) MusicSearch.youtube(track.artist) else emptyList()
        return (mix + fill).filter { it.key != track.key }.distinctBy { it.key }
    }

    /** Returns an audio-only stream URL at the requested quality and the song's real duration in seconds. */
    fun audioStream(videoId: String, quality: AudioQuality, aacOnly: Boolean = false): Pair<String, Long> {
        val info = info(videoId)
        val streams = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            // aacOnly: plain AAC-LC ("mp4a.40.2"); HE-AAC variants don't decode on desktop.
            .filter { !aacOnly || (it.format == MediaFormat.M4A && (it.codec?.startsWith("mp4a.40.2") ?: (it.itag == 140))) }
        if (streams.isEmpty()) throw IOException("No audio-only stream for $videoId")
        val stream = when (quality) {
            AudioQuality.LOW -> streams.minBy { it.averageBitrate }
            AudioQuality.HIGH -> streams.maxBy { it.averageBitrate }
            // Prefer AAC near 128 kbps: widely compatible and a good size/quality balance.
            else -> streams.minBy { abs(it.averageBitrate - 128) + if (it.format == MediaFormat.M4A) 0 else 8 }
        }
        return stream.content to info.duration
    }

    /** Drops cached stream info so the next request fetches fresh stream links. */
    fun forget(videoId: String) {
        synchronized(infoCache) { infoCache.remove(videoId) }
    }

    // One page fetch serves both the stream URL and radio suggestions.
    private fun info(videoId: String): StreamInfo {
        synchronized(infoCache) {
            infoCache[videoId]?.takeIf { System.currentTimeMillis() - it.second < INFO_TTL_MS }?.let { return it.first }
        }
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        synchronized(infoCache) { infoCache[videoId] = info to System.currentTimeMillis() }
        return info
    }

    private fun toTrack(item: StreamInfoItem): Track? {
        if (item.duration < MIN_SONG_SECONDS) return null
        return Track(
            source = Source.YOUTUBE,
            id = runCatching { service.streamLHFactory.getId(item.url) }.getOrNull() ?: return null,
            title = item.name,
            artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
            durationSec = item.duration,
            artworkUrl = largest(item.thumbnails),
        )
    }

    // YouTube Music thumbnails come at 60px; ask the image server for a larger size.
    private fun largest(images: List<Image>): String? =
        images.maxByOrNull { it.height }?.url?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
}
