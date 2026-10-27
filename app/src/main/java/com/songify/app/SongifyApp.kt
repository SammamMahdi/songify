package com.songify.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.songify.app.data.Library
import com.songify.app.data.Settings
import com.songify.app.net.Http
import com.songify.app.playback.Downloads
import com.songify.app.net.NewPipeDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class SongifyApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        // Country biases YouTube Music results toward local music.
        val country = Locale.getDefault().country.ifEmpty { "US" }
        NewPipe.init(NewPipeDownloader, Localization.DEFAULT, ContentCountry(country))
        Settings.init(this)
        Library.init(this)
        Downloads.init(this)
    }

    // Artwork shares the app's HTTP connections and fades in instead of popping.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Http.client)
        .crossfade(150)
        .respectCacheHeaders(false)
        .build()
}
