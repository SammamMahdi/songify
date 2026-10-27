package com.songify.desktop

import com.songify.app.net.Http
import com.songify.app.net.NewPipeDownloader
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Developer check: which ranged requests does the host accept for a song that failed with HTTP 403? */
fun main() {
    NewPipe.init(NewPipeDownloader)
    listOf("5KRafXNESxg", "9DjeWYS52kY").forEach { id ->
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        val stream = info.audioStreams.first { it.itag == 140 }
        val url = stream.content
        println("== $id  client=${Regex("[?&]c=([A-Z_]+)").find(url)?.groupValues?.get(1)}  pot=${"pot=" in url}")
        fun probe(label: String, u: String, range: String?) {
            val req = Request.Builder().url(u).apply { range?.let { header("Range", it) } }.build()
            Http.client.newCall(req).execute().use { r -> println("  $label -> ${r.code} ${r.header("Content-Range") ?: r.body?.contentLength()}") }
        }
        probe("header 0-0", url, "bytes=0-0")
        probe("header 0-1MB", url, "bytes=0-1048575")
        probe("header 1MB-2MB", url, "bytes=1048576-2097151")
        probe("param 0-1MB", "$url&range=0-1048575", null)
        probe("param 1MB-2MB", "$url&range=1048576-2097151", null)
    }
    System.exit(0)
}
