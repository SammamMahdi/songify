package com.songify.app.playback

import android.content.Context
import androidx.core.util.AtomicFile
import com.songify.app.data.AppJson
import com.songify.app.data.Track
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SavedQueue(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

/** Remembers the queue and position so the app reopens where the user left off. */
class QueueStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "queue.json"))

    fun load(): SavedQueue? = runCatching {
        AppJson.decodeFromString(SavedQueue.serializer(), file.readFully().decodeToString())
    }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }

    fun clear() = file.delete()

    fun save(queue: SavedQueue) {
        val out = file.startWrite()
        try {
            out.write(AppJson.encodeToString(SavedQueue.serializer(), queue).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }
}
