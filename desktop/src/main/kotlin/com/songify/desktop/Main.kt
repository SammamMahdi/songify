package com.songify.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import javax.swing.UIManager
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.songify.app.data.Library
import com.songify.app.data.MusicSearch
import com.songify.app.data.StreamResolver
import com.songify.app.net.Http
import com.songify.app.net.NewPipeDownloader
import com.songify.desktop.ui.SongifyDesktop
import com.songify.desktop.ui.SongifyTheme
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * `Songify.exe --self-test`: search, fetch and silently play two seconds without opening a window,
 * then write the outcome to %APPDATA%\Songify\self-test.txt. For checking an installed copy.
 */
private fun selfTest() {
    val report = File(AppDirs.data, "self-test.txt")
    val result = runCatching {
        NewPipe.init(NewPipeDownloader)
        val track = MusicSearch.youtube("Anupam Roy").first()
        val file = AudioFiles.fetch(track, AppDirs.cache)
        var error: Throwable? = null
        // Reads nothing from and writes nothing to the library; only the song cache and this report.
        val engine = AudioEngine(onEnded = {}, onError = { error = it })
        engine.setVolume(0f)
        engine.load(file)
        Thread.sleep(2000)
        val played = engine.positionMs
        engine.stop()
        error?.let { throw it }
        "OK: ${track.title} · ${file.length() / 1024} KB · played ${played} ms"
    }.getOrElse { "FAILED: ${it.stackTraceToString()}" }
    report.writeText("Songify $APP_VERSION self-test\n$result\n")
    exitProcess(0)
}

private val appIcon by lazy {
    BitmapPainter(Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.use(::loadImageBitmap))
}

fun main(args: Array<String>) {
    if ("--self-test" in args) return selfTest()
    val crashFile = File(AppDirs.data, "last_crash.txt")
    val previousCrash = crashFile.takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        runCatching { crashFile.writeText("Songify $APP_VERSION · ${System.getProperty("os.name")}\n\n${e.stackTraceToString()}") }
    }

    // Country biases search results toward local music.
    NewPipe.init(NewPipeDownloader, Localization.DEFAULT, ContentCountry(Locale.getDefault().country.ifEmpty { "US" }))
    DesktopSettings.init()
    Library.init(AppDirs.data)
    StreamResolver.quality = DesktopSettings::effectiveQuality
    AudioFiles.migrateCache()
    DesktopDownloads.init()
    DesktopPlayer.init()
    // Native look for the folder picker.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    application {
        // Reopen where the window was left.
        val saved = DesktopSettings.prefs.value.window
        val windowState = rememberWindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = saved?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 820.dp),
        )
        Window(
            onCloseRequest = {
                val pos = windowState.position
                DesktopSettings.update {
                    it.copy(
                        window = WindowPrefs(
                            x = if (pos.isSpecified) pos.x.value else 100f,
                            y = if (pos.isSpecified) pos.y.value else 100f,
                            width = windowState.size.width.value,
                            height = windowState.size.height.value,
                            maximized = windowState.placement == WindowPlacement.Maximized,
                        ),
                    )
                }
                DesktopPlayer.shutdown()
                exitApplication()
            },
            title = "Songify",
            icon = appIcon,
            state = windowState,
            onKeyEvent = { event ->
                // Only keys no text field consumed reach the window.
                if (event.type != KeyEventType.KeyDown) return@Window false
                when {
                    event.key == Key.Spacebar || event.key == Key.MediaPlayPause -> DesktopPlayer.togglePlay()
                    event.key == Key.MediaNext || (event.isCtrlPressed && event.key == Key.DirectionRight) -> DesktopPlayer.next()
                    event.key == Key.MediaPrevious || (event.isCtrlPressed && event.key == Key.DirectionLeft) -> DesktopPlayer.previous()
                    else -> return@Window false
                }
                true
            },
        ) {
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
                    .crossfade(true)
                    .build()
            }
            val prefs by DesktopSettings.prefs.collectAsState()
            SongifyTheme(Color(prefs.accent), prefs.amoled) {
                SongifyDesktop(previousCrash)
            }
        }
    }
}
