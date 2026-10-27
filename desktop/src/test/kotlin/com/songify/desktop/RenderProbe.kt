package com.songify.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.songify.app.data.Library
import com.songify.app.data.MusicSearch
import com.songify.app.data.StreamResolver
import com.songify.app.net.NewPipeDownloader
import com.songify.desktop.ui.SongifyDesktop
import com.songify.desktop.ui.SongifyTheme
import org.jetbrains.skia.EncodedImageFormat
import org.schabi.newpipe.extractor.NewPipe
import java.io.File

/**
 * Developer check (`gradlew :desktop:renderProbe`): renders the window offscreen to PNGs while a song
 * plays silently, and reports whether playback advanced. Output goes to the path in args[0].
 */
fun main(args: Array<String>) {
    // Never run against a real library: this probe plays songs and wipes history at the end.
    check(System.getProperty("songify.dataDir") != null) { "Run via gradlew :desktop:renderProbe (uses a scratch data folder)" }
    val out = File(args.firstOrNull() ?: "render").apply { mkdirs() }
    NewPipe.init(NewPipeDownloader)
    DesktopSettings.init()
    DesktopSettings.update { it.copy(guideSeen = true, updateCheckedAt = System.currentTimeMillis()) }
    Library.init(AppDirs.data)
    StreamResolver.quality = DesktopSettings::effectiveQuality
    StreamResolver.aacOnly = true
    DesktopDownloads.init()
    DesktopPlayer.init()
    DesktopPlayer.setVolume(0f)

    val scene = ImageComposeScene(1280, 820, Density(1f)) {
        SongifyTheme(Color(DesktopSettings.prefs.value.accent), false) { SongifyDesktop(null) }
    }
    fun snap(name: String, settleMs: Long) {
        val start = System.nanoTime()
        while ((System.nanoTime() - start) / 1_000_000 < settleMs) {
            scene.render(System.nanoTime())
            Thread.sleep(100)
        }
        val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(out, "$name.png").writeBytes(png)
    }

    snap("1-home", 8000)
    val songs = MusicSearch.youtube("Anupam Roy")
    DesktopPlayer.playTracks(songs, 0)
    snap("2-playing", 6000)
    val p1 = DesktopPlayer.positionMs
    Thread.sleep(3000)
    val p2 = DesktopPlayer.positionMs
    val s = DesktopPlayer.state.value
    println("Playback: ${s.current?.title} playing=${s.isPlaying} loading=${s.isLoading} position ${p1}ms -> ${p2}ms of ${s.durationMs}ms")
    DesktopPlayer.next()
    Thread.sleep(6000)
    println("After Next: ${DesktopPlayer.state.value.current?.title} at ${DesktopPlayer.positionMs}ms")
    snap("3-next", 1000)

    // Stress: render continuously and churn memory for 20 s while music plays, then count gaps.
    val before = DesktopPlayer.underruns
    val start = System.currentTimeMillis()
    var garbage = 0L
    while (System.currentTimeMillis() - start < 20_000) {
        scene.render(System.nanoTime())
        repeat(200) { garbage += ByteArray(64 * 1024).size }
    }
    println("Stress: ${DesktopPlayer.underruns - before} audio gaps in 20 s (buffer ${System.getProperty("songify.audioBufferSeconds") ?: "1.5"} s, churned ${garbage / (1 shl 20)} MB)")
    // Leave a first-run state behind: guide still to show, no history.
    DesktopPlayer.setVolume(0.8f)
    DesktopPlayer.shutdown()
    DesktopSettings.update { it.copy(guideSeen = false, updateCheckedAt = 0) }
    Library.clearRecent()
    File(AppDirs.data, "queue.json").delete()
    Thread.sleep(800) // let the library save
    scene.close()
    System.exit(0)
}
