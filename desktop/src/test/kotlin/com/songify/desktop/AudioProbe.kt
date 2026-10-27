package com.songify.desktop

import com.songify.app.data.Audius
import com.songify.app.data.StreamResolver
import com.songify.app.data.AudioQuality
import com.songify.app.data.YouTubeMusic
import com.songify.app.net.NewPipeDownloader
import org.schabi.newpipe.extractor.NewPipe
import java.io.File

/** Developer check (`gradlew :desktop:probeAudio`): fetches songs and plays a few seconds of each, silently. */
fun main() {
    NewPipe.init(NewPipeDownloader)
    StreamResolver.quality = { AudioQuality.NORMAL } // AAC
    val aac = AudioFiles.fetch(YouTubeMusic.track("-feVteC_gGM"), AppDirs.cache)
    StreamResolver.quality = { AudioQuality.HIGH } // Opus
    val opus = AudioFiles.fetch(YouTubeMusic.track("9DjeWYS52kY"), AppDirs.cache)
    val mp3 = AudioFiles.fetch(Audius.trending().first(), AppDirs.cache)
    listOf(aac, opus, mp3).forEach { println("${it.name}: ${probe(it)}") }
    System.exit(0)
}

private fun probe(file: File): String {
    var error: Throwable? = null
    val engine = AudioEngine(onEnded = {}, onError = { error = it })
    engine.setVolume(0f)
    engine.load(file)
    Thread.sleep(2500)
    val playedMs = engine.positionMs
    engine.seek(60_000)
    Thread.sleep(1500)
    val afterSeek = engine.positionMs
    engine.stop()
    return error?.let { "error: $it" }
        ?: "duration ${engine.durationMs / 1000}s, played ${playedMs}ms, after seek to 60s: ${afterSeek}ms"
}
