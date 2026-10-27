package com.songify.app.data

import com.songify.app.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Audius public API: full-length MP3 streams, no API key needed. */
object Audius {
    private const val API = "https://api.audius.co/v1"
    private const val APP_NAME = "songify"

    fun search(query: String): List<Track> = tracks("tracks/search", "query" to query)

    fun trending(): List<Track> = tracks("tracks/trending")

    fun streamUrl(id: String) = "$API/tracks/$id/stream?app_name=$APP_NAME"

    private fun tracks(path: String, vararg params: Pair<String, String>): List<Track> {
        val url = "$API/$path".toHttpUrl().newBuilder()
            .addQueryParameter("app_name", APP_NAME)
            .addQueryParameter("limit", "25")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val data = Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Audius HTTP ${r.code}")
            JSONObject(r.body!!.string()).getJSONArray("data")
        }
        return (0 until data.length())
            .map { data.getJSONObject(it) }
            .filter(::isFreelyStreamable)
            .map {
                Track(
                    source = Source.AUDIUS,
                    id = it.getString("id"),
                    title = it.getString("title"),
                    artist = it.optJSONObject("user")?.optString("name").orEmpty(),
                    durationSec = it.optLong("duration"),
                    artworkUrl = it.optJSONObject("artwork")?.optString("480x480")?.ifEmpty { null },
                )
            }
    }

    // Gated tracks only serve a preview to anonymous listeners.
    private fun isFreelyStreamable(t: JSONObject) =
        t.optBoolean("is_streamable", true) &&
            !t.optBoolean("is_stream_gated") &&
            !t.optBoolean("is_delete") &&
            t.optJSONObject("access")?.optBoolean("stream", true) != false &&
            t.optLong("duration") >= MIN_SONG_SECONDS
}
