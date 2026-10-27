package com.songify.desktop

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import net.sourceforge.jaad.aac.Decoder as AacDecoder
import net.sourceforge.jaad.aac.SampleBuffer as AacSampleBuffer
import net.sourceforge.jaad.mp4.MP4Container
import net.sourceforge.jaad.mp4.api.AudioTrack
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max

/** Decoded 16-bit PCM from an audio file. */
private interface PcmSource : Closeable {
    val format: AudioFormat
    val durationMs: Long
    /** The next block of PCM bytes, or null at the end. */
    fun read(): ByteArray?
    fun seek(ms: Long)
}

private class AacSource(file: File) : PcmSource {
    private val raf = RandomAccessFile(file, "r")
    private val movie = MP4Container(raf).movie
    private val track = movie.getTracks(AudioTrack.AudioCodec.AAC).first() as AudioTrack
    private val decoder = AacDecoder(track.decoderSpecificInfo)
    private val buffer = AacSampleBuffer()
    private var pending: ByteArray? = decodeNext()

    override val format: AudioFormat = AudioFormat(
        buffer.sampleRate.toFloat(), buffer.bitsPerSample, buffer.channels, true, buffer.isBigEndian,
    )
    override val durationMs = (movie.duration * 1000).toLong()

    private fun decodeNext(): ByteArray? {
        while (track.hasMoreFrames()) {
            val frame = track.readNextFrame() ?: return null
            // A damaged frame shouldn't stop the song.
            if (runCatching { decoder.decodeFrame(frame.data, buffer) }.isSuccess) return buffer.data.copyOf()
        }
        return null
    }

    override fun read(): ByteArray? = pending?.also { pending = null } ?: decodeNext()

    override fun seek(ms: Long) {
        track.seek(ms / 1000.0)
        pending = null
    }

    override fun close() = raf.close()
}

private class Mp3Source(private val file: File) : PcmSource {
    private var bitstream = open()
    private val decoder = Decoder()
    private var first: ByteArray? = decodeNext()

    override val format = AudioFormat(decoder.outputFrequency.toFloat(), 16, decoder.outputChannels, true, false)
    override val durationMs: Long = scanDuration()

    private fun open() = Bitstream(BufferedInputStream(file.inputStream(), 64 * 1024))

    private fun decodeNext(): ByteArray? {
        while (true) {
            val header = bitstream.readFrame() ?: return null
            val out = runCatching { decoder.decodeFrame(header, bitstream) as SampleBuffer }.getOrNull()
            bitstream.closeFrame()
            if (out != null) return toBytes(out.buffer, out.bufferLength)
        }
    }

    // Headers only, no decoding: quick even for long files.
    private fun scanDuration(): Long {
        val scan = open()
        var ms = 0.0
        try {
            while (true) {
                val header = scan.readFrame() ?: break
                ms += header.ms_per_frame()
                scan.closeFrame()
            }
        } finally {
            scan.close()
        }
        return ms.toLong()
    }

    override fun read(): ByteArray? = first?.also { first = null } ?: decodeNext()

    override fun seek(ms: Long) {
        bitstream.close()
        bitstream = open()
        first = null
        var at = 0.0
        while (at < ms) {
            val header = bitstream.readFrame() ?: break
            at += header.ms_per_frame()
            bitstream.closeFrame()
        }
    }

    override fun close() = bitstream.close()

    private fun toBytes(samples: ShortArray, length: Int): ByteArray {
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            bytes[i * 2] = samples[i].toInt().toByte()
            bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
        }
        return bytes
    }
}

/**
 * Plays one file at a time through the system's audio output on its own thread.
 * All public methods are safe to call from any thread.
 */
class AudioEngine(private val onEnded: () -> Unit, private val onError: (Throwable) -> Unit) {
    @Volatile private var source: PcmSource? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var paused = true
    @Volatile private var seekTo: Long? = null
    @Volatile private var volume = 0.8f
    @Volatile private var generation = 0
    @Volatile private var positionBaseMs = 0L
    private val lock = Object()

    val durationMs: Long get() = source?.durationMs ?: 0
    val isPaused: Boolean get() = paused

    /** Current position, from the frames the sound card has actually played. */
    val positionMs: Long
        get() = positionBaseMs + (line?.microsecondPosition ?: 0) / 1000

    /** Opens [file] and starts playing (or stays paused if [play] is false). */
    fun load(file: File, play: Boolean = true, startMs: Long = 0) {
        stop()
        val src = if (file.extension.equals("mp3", ignoreCase = true)) Mp3Source(file) else AacSource(file)
        val out = AudioSystem.getSourceDataLine(src.format).apply {
            open(src.format, (src.format.frameRate * src.format.frameSize / 4).toInt()) // ~250 ms buffer
        }
        source = src
        line = out
        applyVolume()
        positionBaseMs = 0
        if (startMs > 0) seek(startMs)
        paused = !play
        if (play) out.start()
        val myGeneration = ++generation
        Thread({ pump(myGeneration, src, out) }, "songify-audio").apply { isDaemon = true }.start()
    }

    private fun pump(myGeneration: Int, src: PcmSource, out: SourceDataLine) {
        try {
            while (myGeneration == generation) {
                synchronized(lock) {
                    while (paused && seekTo == null && myGeneration == generation) lock.wait(200)
                }
                if (myGeneration != generation) break
                seekTo?.let { ms ->
                    seekTo = null
                    out.flush()
                    src.seek(ms)
                    positionBaseMs = ms - out.microsecondPosition / 1000
                }
                if (paused) continue
                val pcm = src.read()
                if (pcm == null) {
                    out.drain()
                    if (myGeneration == generation) onEnded()
                    break
                }
                out.write(pcm, 0, pcm.size)
            }
        } catch (e: Throwable) {
            if (myGeneration == generation) onError(e)
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

    fun stop() {
        generation++
        synchronized(lock) { lock.notifyAll() }
        line?.run {
            stop()
            flush()
            close()
        }
        line = null
        runCatching { source?.close() }
        source = null
        paused = true
    }
}
