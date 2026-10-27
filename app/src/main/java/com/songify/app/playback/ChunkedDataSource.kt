package com.songify.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Reads large audio files as a series of ranged requests instead of one long connection.
 * Some hosts throttle long single connections to roughly playback speed, which makes
 * downloads take as long as the song; short ranged requests run at full speed.
 */
class ChunkedDataSource(private val upstream: HttpDataSource) : DataSource {
    private companion object {
        const val CHUNK_BYTES = 1L shl 20 // 1 MB
        val THROTTLED_HOSTS = listOf("googlevideo.com")
    }

    class Factory(private val upstream: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var chunked = false
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong() // exclusive; unknown until the first response

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = THROTTLED_HOSTS.any { dataSpec.uri.host?.endsWith(it) == true }
        if (!chunked) return upstream.open(dataSpec)

        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else C.LENGTH_UNSET.toLong()
        openChunk()
        if (end == C.LENGTH_UNSET.toLong()) {
            // "bytes 0-1048575/5205537" -> total size
            val total = upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()?.substringAfter('/')?.toLongOrNull()
            if (total != null) end = total
        }
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - dataSpec.position
    }

    private fun openChunk() {
        upstream.close()
        val length = if (end == C.LENGTH_UNSET.toLong()) CHUNK_BYTES else minOf(CHUNK_BYTES, end - position)
        upstream.open(spec!!.buildUpon().setPosition(position).setLength(length).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
        var n = upstream.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            // This chunk is done; fetch the next one unless that was the end of the file.
            if (end == C.LENGTH_UNSET.toLong() || position >= end) return C.RESULT_END_OF_INPUT
            openChunk()
            n = upstream.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) return n
        }
        position += n
        return n
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
