package com.songify.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.songify.app.playback.PlayerHub
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.songify.app.data.AudioQuality
import com.songify.app.data.Library
import com.songify.app.data.Settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ui = LocalUi.current
    val accent by Settings.accent.collectAsState()
    val amoled by Settings.amoled.collectAsState()
    val quality by Settings.quality.collectAsState()
    val autoplay by Settings.autoplay.collectAsState()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = { ui.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        SectionTitle("Accent color")
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AccentPresets.forEach { preset ->
                val selected = preset.argb == accent
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(preset.argb))
                        .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        .clickable { Settings.setAccent(preset.argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = preset.name, tint = Color.Black)
                }
            }
        }
        Text(
            "Or pick any color",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        HueSlider(Color(accent)) { Settings.setAccent(it.toArgb()) }

        SettingRow("Pure black background", "Saves battery on OLED screens") {
            Switch(
                checked = amoled,
                onCheckedChange = Settings::setAmoled,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
            )
        }

        SectionTitle("Playback")
        SettingRow("Autoplay", "Keep playing similar songs when your music ends") {
            Switch(
                checked = autoplay,
                onCheckedChange = Settings::setAutoplay,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
            )
        }
        BackgroundPlaybackRow()
        SettingRow("Equalizer", "Adjust bass, treble and more") {
            TextButton(onClick = {
                val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, PlayerHub.audioSessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    ui.toast("This phone doesn't have a built-in equalizer")
                }
            }) { Text("Open") }
        }

        SectionTitle("Audio quality")
        Text(
            "Lower quality starts faster and keeps playing on slow connections.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        AudioQuality.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable { Settings.setQuality(option) }.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = quality == option, onClick = { Settings.setQuality(option) })
                Column {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    val detail = if (option == AudioQuality.AUTO && quality == AudioQuality.AUTO) {
                        "${option.detail} · now ${Settings.effectiveQuality().label}"
                    } else option.detail
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionTitle("Help")
        SettingRow("Beginner's guide", "Import from Spotify, fix missing songs, share playlists") {
            TextButton(onClick = { ui.guidePage = 0 }) { Text("Open") }
        }

        SectionTitle("Storage")
        SettingRow("Recently played", "Clear your listening history on this phone") {
            TextButton(onClick = {
                Library.clearRecent()
                ui.toast("Listening history cleared")
            }) { Text("Clear") }
        }
        SettingRow("Search history", "Clear recent searches") {
            TextButton(onClick = {
                Settings.clearRecentSearches()
                ui.toast("Search history cleared")
            }) { Text("Clear") }
        }

        SectionTitle("About")
        val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        Text(
            "Songify $version\nFull songs, streamed. Your playlists are stored only on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * Some phones (Xiaomi, Samsung, Oppo…) stop background apps to save battery even while music plays.
 * Exempting Songify keeps music going with the screen off.
 */
@Composable
private fun BackgroundPlaybackRow() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Re-check when returning from the system dialog.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SettingRow(
        "Play with screen off",
        if (exempt) "Allowed: music keeps playing in the background" else "Allow so your phone doesn't stop music to save battery",
    ) {
        if (exempt) {
            Icon(Icons.Rounded.Check, contentDescription = "Allowed", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
        } else {
            @SuppressLint("BatteryLife")
            val request = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            TextButton(onClick = { context.startActivity(request) }) { Text("Allow") }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

/** A rainbow slider; picks a vivid color at the chosen hue. */
@Composable
private fun HueSlider(current: Color, onPick: (Color) -> Unit) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(current.toArgb(), it) }
    val rainbow = (0..6).map { Color.hsv(it * 60f % 360f, 0.75f, 0.95f) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(rainbow)))
        Slider(
            value = hsv[0],
            onValueChange = { onPick(Color.hsv(it, 0.75f, 0.95f)) },
            valueRange = 0f..359f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}
