package com.songify.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.songify.app.data.Settings
import kotlinx.coroutines.launch

const val GUIDE_IMPORT_PAGE = 1
private const val EXPORTIFY_URL = "https://exportify.app"

private data class GuidePage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val steps: List<String> = emptyList(),
    val recommended: Boolean = false,
)

private val Pages = listOf(
    GuidePage(
        Icons.Rounded.Headphones,
        "Welcome to Songify",
        "Full songs, streamed for free. No account needed: your playlists, likes and history stay on this phone.",
    ),
    GuidePage(
        Icons.AutoMirrored.Rounded.PlaylistAddCheck,
        "Bring your Spotify playlists",
        "The easiest way to move whole playlists, however long they are:",
        steps = listOf(
            "Tap Open Exportify below and log in with Spotify.",
            "Tap Export next to a playlist (or Export All for everything).",
            "Open the downloaded file with Songify, or go to Your Library → + → Import a file.",
        ),
        recommended = true,
    ),
    GuidePage(
        Icons.Rounded.SearchOff,
        "Fix songs that weren't found",
        "After an import, any song Songify couldn't match waits at the bottom of the playlist.",
        steps = listOf(
            "Tap Find next to the song.",
            "Search for it, or paste a link to the exact song.",
            "Tap the right result and it's added to the playlist.",
        ),
    ),
    GuidePage(
        Icons.Rounded.IosShare,
        "Share playlists with friends",
        "Playlists live on your phone, so sharing is just a file.",
        steps = listOf(
            "Open a playlist and tap the share icon.",
            "Send the file in any chat app.",
            "Your friend opens it with Songify and gets the same playlist.",
        ),
    ),
)

/** Beginner's guide to importing and sharing. Shown once on first launch; can be skipped or reopened from Settings. */
@Composable
fun GuideScreen(startPage: Int, onClose: () -> Unit) {
    val ui = LocalUi.current
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startPage.coerceIn(0, Pages.lastIndex)) { Pages.size }
    val scope = rememberCoroutineScope()
    val finish = {
        Settings.markGuideSeen()
        onClose()
    }
    BackHandler(onBack = finish)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Beginner's guide",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                TextButton(onClick = finish) { Text("Skip") }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                val page = Pages[index]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    if (page.recommended) {
                        Text(
                            "RECOMMENDED",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(page.title, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(page.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    page.steps.forEachIndexed { i, step ->
                        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelLarge)
                            }
                            Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                    if (index == GUIDE_IMPORT_PAGE) {
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EXPORTIFY_URL))) }) {
                            Text("Open Exportify")
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Other ways: paste a Spotify playlist link (first 100 songs only), or request your data from Spotify " +
                                "(Account → Privacy settings → Download your data) and import Playlist1.json.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Page dots
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(Pages.size) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (i == pager.currentPage) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f)),
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage == Pages.lastIndex) {
                    OutlinedButton(onClick = {
                        finish()
                        ui.importMenu = true
                    }) { Text("Import now") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = finish) { Text("Start listening") }
                } else {
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text("Next") }
                }
            }
        }
    }
}
