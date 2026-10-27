package com.songify.desktop

import com.songify.app.data.AppJson
import com.songify.app.data.AudioQuality
import com.songify.app.data.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Prefs(
    val accent: Int = 0xFF1ED760.toInt(),
    val amoled: Boolean = false,
    val quality: AudioQuality = AudioQuality.AUTO,
    val autoplay: Boolean = true,
    val volume: Float = 0.8f,
    val recentSearches: List<String> = emptyList(),
    val guideSeen: Boolean = false,
    val updateCheckedAt: Long = 0,
)

/** Small preferences in a JSON file next to the library. */
object DesktopSettings {
    private val file get() = File(AppDirs.data, "settings.json")
    private val _prefs = MutableStateFlow(Prefs())
    val prefs = _prefs.asStateFlow()

    fun init() {
        _prefs.value = runCatching { AppJson.decodeFromString(Prefs.serializer(), file.readText()) }.getOrDefault(Prefs())
    }

    fun update(change: (Prefs) -> Prefs) {
        _prefs.update(change)
        writeAtomically(file, AppJson.encodeToString(Prefs.serializer(), _prefs.value))
    }

    /** Computers are usually on unmetered connections, so Automatic means High. */
    fun effectiveQuality(): AudioQuality = _prefs.value.quality.let { if (it == AudioQuality.AUTO) AudioQuality.HIGH else it }

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        update { p -> p.copy(recentSearches = (listOf(q) + p.recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(10)) }
    }

    /** True if the automatic update check hasn't run in the last 12 hours (and records this one). */
    fun updateCheckDue(): Boolean {
        val now = System.currentTimeMillis()
        if (now - _prefs.value.updateCheckedAt < 12 * 60 * 60 * 1000L) return false
        update { it.copy(updateCheckedAt = now) }
        return true
    }
}
