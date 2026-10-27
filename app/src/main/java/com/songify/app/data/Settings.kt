package com.songify.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioQuality(val label: String, val detail: String) {
    AUTO("Automatic", "Adjusts to your connection"),
    LOW("Low", "About 50 kbps · uses the least data"),
    NORMAL("Normal", "About 128 kbps"),
    HIGH("High", "Up to 160 kbps · best on Wi-Fi"),
}

/** Small preferences kept in SharedPreferences on the phone. */
object Settings {
    val DEFAULT_ACCENT = 0xFF1ED760.toInt()

    // A stall within this window keeps Automatic quality lowered.
    private const val STALL_MEMORY_MS = 10 * 60 * 1000L

    private lateinit var prefs: SharedPreferences
    private lateinit var connectivity: ConnectivityManager

    private val _accent = MutableStateFlow(DEFAULT_ACCENT)
    val accent = _accent.asStateFlow()

    private val _amoled = MutableStateFlow(false)
    val amoled = _amoled.asStateFlow()

    private val _quality = MutableStateFlow(AudioQuality.AUTO)
    val quality = _quality.asStateFlow()

    private val _autoplay = MutableStateFlow(true)
    /** Keep playing similar songs when the queue runs out. */
    val autoplay = _autoplay.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches = _recentSearches.asStateFlow()

    /** True once the beginner's guide has been finished or skipped. */
    val guideSeen: Boolean get() = prefs.getBoolean("guide_seen", false)

    fun markGuideSeen() = prefs.edit { putBoolean("guide_seen", true) }

    @Volatile private var downgrades = 0
    @Volatile private var lastStallAt = 0L

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        connectivity = context.getSystemService(ConnectivityManager::class.java)
        _accent.value = prefs.getInt("accent", DEFAULT_ACCENT)
        _amoled.value = prefs.getBoolean("amoled", false)
        _autoplay.value = prefs.getBoolean("autoplay", true)
        _quality.value = prefs.getString("quality", null)
            ?.let { name -> AudioQuality.entries.firstOrNull { it.name == name } } ?: AudioQuality.AUTO
        _recentSearches.value = prefs.getString("recent_searches", "").orEmpty().lines().filter { it.isNotBlank() }
    }

    fun setAccent(argb: Int) {
        _accent.value = argb
        prefs.edit { putInt("accent", argb) }
    }

    fun setAmoled(enabled: Boolean) {
        _amoled.value = enabled
        prefs.edit { putBoolean("amoled", enabled) }
    }

    fun setAutoplay(enabled: Boolean) {
        _autoplay.value = enabled
        prefs.edit { putBoolean("autoplay", enabled) }
    }

    fun setQuality(quality: AudioQuality) {
        _quality.value = quality
        downgrades = 0
        prefs.edit { putString("quality", quality.name) }
    }

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        saveRecentSearches((listOf(q) + _recentSearches.value.filterNot { it.equals(q, ignoreCase = true) }).take(10))
    }

    fun removeRecentSearch(query: String) = saveRecentSearches(_recentSearches.value - query)

    fun clearRecentSearches() = saveRecentSearches(emptyList())

    private fun saveRecentSearches(list: List<String>) {
        _recentSearches.value = list
        prefs.edit { putString("recent_searches", list.joinToString("\n")) }
    }

    /** The quality to stream at right now; resolves Automatic against the current connection. */
    fun effectiveQuality(): AudioQuality {
        val chosen = _quality.value
        if (chosen != AudioQuality.AUTO) return chosen
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val base = when {
            caps == null -> AudioQuality.NORMAL
            caps.linkDownstreamBandwidthKbps in 1 until 1000 -> AudioQuality.LOW
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> AudioQuality.NORMAL
            else -> AudioQuality.HIGH
        }
        if (System.currentTimeMillis() - lastStallAt > STALL_MEMORY_MS) downgrades = 0
        return AudioQuality.entries[maxOf(AudioQuality.LOW.ordinal, base.ordinal - downgrades)]
    }

    /** Playback stalled waiting for data; Automatic picks a lower quality for the next songs. */
    fun reportStall() {
        downgrades = minOf(downgrades + 1, 2)
        lastStallAt = System.currentTimeMillis()
    }
}
