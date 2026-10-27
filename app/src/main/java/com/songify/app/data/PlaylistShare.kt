package com.songify.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Exports playlists as files or text that other Songify users can import. */
object PlaylistShare {
    fun fileIntent(context: Context, playlist: Playlist): Intent {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeName = playlist.name.replace(Regex("""[^\p{L}\p{N} _-]"""), "").trim().ifEmpty { "playlist" }
        val file = File(dir, "$safeName.songify.json")
        file.writeText(AppJson.encodeToString(SharedPlaylist.serializer(), SharedPlaylist(name = playlist.name, tracks = playlist.tracks)))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "${playlist.name} · Songify playlist")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share playlist")
    }

    /** Plain text list; anyone can paste it into Songify's "Paste a song list" import. */
    fun textIntent(playlist: Playlist): Intent {
        val text = buildString {
            appendLine(playlist.name)
            appendLine()
            playlist.tracks.forEach { appendLine("${it.artist} - ${it.title}") }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, "Share song list")
    }

    fun trackIntent(track: Track): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, track.shareText), "Share song")
}
