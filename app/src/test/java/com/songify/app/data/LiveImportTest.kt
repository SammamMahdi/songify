package com.songify.app.data

import com.songify.app.net.NewPipeDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/**
 * Hits the real services, so it only runs when SONGIFY_LIVE_TESTS is set:
 *   SONGIFY_LIVE_TESTS=1 gradlew testDebugUnitTest
 */
class LiveImportTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue(System.getenv("SONGIFY_LIVE_TESTS") != null)
            NewPipe.init(NewPipeDownloader)
        }
    }

    @Test
    fun spotifyPlaylistImportsAndMatches() = runBlocking {
        val list = PlaylistImport.fromLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc")
        println("Spotify: ${list.name} · ${list.items.size} songs")
        assertTrue(list.items.size >= 20)

        val sample = list.copy(items = list.items.take(15))
        val started = System.currentTimeMillis()
        val result = PlaylistImport.resolve(sample) { _, _ -> }
        println("Matched ${result.tracks.size}/15 in ${System.currentTimeMillis() - started} ms")
        sample.items.filterIsInstance<ImportItem.Lookup>().zip(result.tracks.take(15)).take(5).forEach { (w, t) ->
            println("  ${w.wanted}  ->  ${t.title} · ${t.artist} (${t.durationSec}s)")
        }
        result.missing.forEach { println("  missing: $it") }
        assertTrue(result.tracks.size >= 12)
    }

    @Test
    fun appleMusicPlaylistImports() {
        val list = PlaylistImport.fromLink("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb")
        println("Apple Music: ${list.name} · ${list.items.size} songs; first: ${(list.items.first() as ImportItem.Lookup).wanted}")
        assertTrue(list.items.size >= 20)
    }

    @Test
    fun banglaSongMatchesOriginal() {
        val match = Matcher.find(Wanted("Amake Amar Moto Thakte Dao", "Anupam Roy", 322))
        println("Bangla match: ${match?.title} · ${match?.artist} (${match?.durationSec}s)")
        assertTrue(match != null && "anupam" in match.artist.lowercase())
    }

    @Test
    fun pastedTextAndCsvParse() {
        val text = PlaylistImport.fromText("Mix", "Tate McRae - Housewife\nAnupam Roy - Amake Amar Moto Thakte Dao\n").single()
        assertTrue(text.items.size == 2)
        // Exportify: Artist URI(s) column must not be mistaken for the artist name.
        val exportify = PlaylistImport.fromText(
            "Export",
            "\"Track URI\",\"Track Name\",\"Artist URI(s)\",\"Artist Name(s)\",\"Duration (ms)\",\"ISRC\"\n" +
                "\"spotify:track:x\",\"Housewife\",\"spotify:artist:y\",\"Tate McRae\",\"208810\",\"USRC12500001\"\n",
        ).single()
        val wanted = (exportify.items.single() as ImportItem.Lookup).wanted
        assertTrue(wanted == Wanted("Housewife", "Tate McRae", 208, "USRC12500001"))
        // Semicolon-separated, several playlists in one file (TuneMyMusic / Soundiiz style).
        val multi = PlaylistImport.fromText(
            "x",
            "Title;Artist;Album;Playlist name;ISRC\nHousewife;Tate McRae;Housewife;Pop;\nShohage Adore;Anupam Roy;Belashuru;Bangla;\n",
        )
        assertTrue(multi.map { it.name }.toSet() == setOf("Pop", "Bangla"))
    }

    @Test
    fun songLinksArePickedUp() {
        listOf(
            "https://www.youtube.com/watch?v=-feVteC_gGM&list=RD-feVteC_gGM",
            "https://music.youtube.com/watch?v=-feVteC_gGM",
            "https://youtu.be/-feVteC_gGM?si=abc",
            "https://youtube.com/shorts/-feVteC_gGM",
        ).forEach { assertTrue(it, YouTubeMusic.videoId(it) == "-feVteC_gGM") }
        val pasted = PlaylistImport.fromText("Mix", "Tate McRae - Housewife\nhttps://youtu.be/-feVteC_gGM\n").single()
        assertTrue(pasted.items.count { it is ImportItem.Link } == 1 && pasted.items.size == 2)
        val track = YouTubeMusic.track("-feVteC_gGM")
        println("Pasted link: ${track.title} · ${track.artist} (${track.durationSec}s)")
        assertTrue(track.durationSec > 60)
    }

    @Test
    fun updaterSeesLatestRelease() {
        val offered = Updater.newerThan("0.4.9")
        println("Update offered to 0.4.9: ${offered?.version} ${offered?.apkUrl}")
        assertTrue(offered != null && offered.apkUrl.endsWith(".apk"))
        assertTrue(Updater.newerThan("99.0.0") == null)
    }

    @Test
    fun radioIsMusic() {
        val seed = YouTubeMusic.track("-feVteC_gGM")
        val radio = YouTubeMusic.similar(seed)
        println("Radio for ${seed.title}: ${radio.size} songs")
        radio.take(8).forEach { println("  ${it.title} · ${it.artist} (${it.durationSec}s)") }
        assertTrue(radio.size >= 5)
    }

    @Test
    fun spotifyDataExportParses() {
        val json = """{"playlists":[{"name":"Road trip","items":[{"track":{"trackName":"Housewife","artistName":"Tate McRae","albumName":"x","trackUri":"spotify:track:1"}}]}]}"""
        val list = PlaylistImport.fromText("x", json).single()
        assertTrue(list.name == "Road trip" && list.items.size == 1)
    }

    @Test
    fun isrcFindsExactRecording() {
        // "Blinding Lights" by The Weeknd; the title is deliberately vague to prove the ISRC does the work.
        val match = Matcher.find(Wanted("Blinding Lights", "The Weeknd", 0, "USUG11904206"))
        println("ISRC match: ${match?.title} · ${match?.artist}")
        assertTrue(match != null && "blinding" in match.title.lowercase())
    }

    @Test
    fun qualityLevelsPickDifferentStreams() {
        val id = "-feVteC_gGM"
        val picks = listOf(AudioQuality.LOW, AudioQuality.NORMAL, AudioQuality.HIGH).associateWith {
            YouTubeMusic.audioStream(id, it).first
        }
        picks.forEach { (q, url) -> println("$q -> itag ${Regex("itag=(\\d+)").find(url)?.groupValues?.get(1)}") }
        assertTrue(picks.values.toSet().size >= 2)
    }
}
