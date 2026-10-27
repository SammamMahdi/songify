package com.songify.desktop

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max

/** Decodes any audio file (AAC, HE-AAC, Opus, MP3) to 16-bit PCM with FFmpeg, the decoder VLC uses. */
private class FfmpegSource(file: File) : AutoCloseable {
    private val grabber = FFmpegFrameGrabber(file).apply {
        sampleMode = FrameGrabber.SampleMode.SHORT
        sampleFormat = avutil.AV_SAMPLE_FMT_S16 // interleaved 16-bit
        start()
    }

    val format = AudioFormat(grabber.sampleRate.toFloat(), 16, grabber.audioChannels, true, false)
    val durationMs: Long = grabber.lengthInTime / 1000

    /** The next block of PCM bytes, or null at the end. */
    fun read(): ByteArray? {
        while (true) {
            val frame = grabber.grabSamples() ?: return null
            val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
            val bytes = ByteArray(samples.remaining() * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            return bytes
        }
    }

    fun seek(ms: Long) = grabber.setAudioTimestamp(ms * 1000)

    override fun close() {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }

    companion object {
        init {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        }
    }
}

/**
 * Plays one file at a time through the system's audio output on its own thread.
 * All public methods are safe to call from any thread; only the audio thread touches the decoder.
 */
class AudioEngine(private val onEnded: () -> Unit, private val onError: (Throwable) -> Unit) {
    private companion object {
        // A long output buffer rides out GC pauses and busy moments without an audible gap.
        val BUFFER_SECONDS = System.getProperty("songify.audioBufferSeconds")?.toFloatOrNull() ?: 1.5f
    }

    @Volatile private var line: SourceDataLine? = null
    @Volatile private var paused = true
    @Volatile private var seekTo: Long? = null
    @Volatile private var volume = 0.8f
    @Volatile private var generation = 0
    @Volatile private var positionBaseMs = 0L
    @Volatile var durationMs: Long = 0
        private set
    private val lock = Object()

    val isPaused: Boolean get() = paused

    /** Times the sound card ran dry mid-song (each is an audible gap). For diagnostics. */
    @Volatile var underruns = 0
        private set

    /** Current position, from the frames the sound card has actually played. */
    val positionMs: Long
        get() = positionBaseMs + (line?.microsecondPosition ?: 0) / 1000

    /** Opens [file] and starts playing (or stays paused if [play] is false). */
    fun load(file: File, play: Boolean = true, startMs: Long = 0) {
        stop()
        val src = FfmpegSource(file)
        val bufferBytes = (src.format.frameRate * BUFFER_SECONDS).toInt() * src.format.frameSize
        val out = try {
            AudioSystem.getSourceDataLine(src.format).apply { open(src.format, bufferBytes) }
        } catch (e: Exception) {
            src.close()
            throw e
        }
        durationMs = src.durationMs
        line = out
        applyVolume()
        positionBaseMs = 0
        seekTo = startMs.takeIf { it > 0 }
        paused = !play
        if (play) out.start()
        val myGeneration = ++generation
        Thread({ pump(myGeneration, src, out) }, "songify-audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }.start()
    }

    // Decodes and feeds the sound card; owns the decoder, so it's the only place that closes it.
    private fun pump(myGeneration: Int, src: FfmpegSource, out: SourceDataLine) {
        try {
            while (myGeneration == generation) {
                synchronized(lock) {
                    while (paused && seekTo == null && myGeneration == generation) lock.wait(200)
                }
                if (myGeneration != generation) break
                var flushed = false
                seekTo?.let { ms ->
                    seekTo = null
                    out.flush()
                    src.seek(ms)
                    positionBaseMs = ms - out.microsecondPosition / 1000
                    flushed = true
                }
                if (paused) continue
                val pcm = src.read()
                if (pcm == null) {
                    out.drain()
                    if (myGeneration == generation) onEnded()
                    break
                }
                // An empty line while playing (and not just flushed by a seek) means the card ran dry.
                if (!flushed && out.isRunning && out.available() >= out.bufferSize && out.microsecondPosition > 500_000) underruns++
                out.write(pcm, 0, pcm.size)
            }
        } catch (e: Throwable) {
            if (myGeneration == generation) onError(e)
        } finally {
            runCatching { out.close() }
            src.close()
        }
    }

    fun play() {
        if (line == null) return
        paused = false
        line?.start()
        synchronized(lock) { lock.notifyAll() }
    }

    fun pause() {
        paused = true
        line?.stop()
    }

    fun seek(ms: Long) {
        seekTo = ms.coerceIn(0, max(0, durationMs - 500))
        synchronized(lock) { lock.notifyAll() }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        val control = line?.takeIf { it.isControlSupported(FloatControl.Type.MASTER_GAIN) }
            ?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl ?: return
        val db = if (volume <= 0.001f) control.minimum else (20 * log10(volume.toDouble())).toFloat()
        control.value = db.coerceIn(control.minimum, control.maximum)
    }

    /** Silences immediately; the audio thread then closes the decoder itself. */
    fun stop() {
        generation++
        synchronized(lock) { lock.notifyAll() }
        line?.run {
            stop()
            flush()
            close() // unblocks a pending write so the audio thread can exit
        }
        line = null
        paused = true
    }
}
