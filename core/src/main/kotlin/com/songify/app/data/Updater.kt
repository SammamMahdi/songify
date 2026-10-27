package com.songify.app.data

import com.songify.app.net.Http
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A newer release; [assetUrl] is the installer for the asking platform. */
data class Release(val version: String, val notes: String, val assetUrl: String, val assetName: String)

/** Checks the project's GitHub releases for a newer version. */
object Updater {
    private const val REPO = "SammamMahdi/songify"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /**
     * The newest release if it's newer than [currentVersion] and has an asset ending in
     * [assetSuffix] (".apk" on Android, ".msi" on Windows); blocking.
     */
    fun newerThan(currentVersion: String, assetSuffix: String = ".apk"): Release? {
        val request = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        val json = Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Update check failed (HTTP ${r.code})")
            JSONObject(r.body!!.string())
        }
        val version = json.optString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets") ?: return null
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(assetSuffix, ignoreCase = true) } ?: return null
        if (compare(version, currentVersion) <= 0) return null
        return Release(version, json.optString("body"), asset.getString("browser_download_url"), asset.getString("name"))
    }

    /** Downloads the installer into [dir], reporting progress 0..1; blocking. */
    fun download(dir: File, release: Release, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, release.assetName)
        Http.client.newCall(Request.Builder().url(release.assetUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        total?.let { onProgress(read.toFloat() / it) }
                    }
                }
            }
        }
        return file
    }

    // "0.10.0" > "0.9.2"
    private fun compare(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
