package com.songify.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

private fun installIntent(context: Context, apk: File): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", apk), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

/** Update available → downloading → install, driven by [AppViewModel.update]. */
@Composable
fun UpdateDialog() {
    val app = LocalAppViewModel.current
    val context = LocalContext.current
    when (val state = app.update.collectAsState().value) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = app::dismissUpdate,
            title = { Text("Checking for updates") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = app::dismissUpdate,
            title = { Text("Songify ${state.release.version} is available") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("You have ${app.versionName}. Your playlists and downloads are kept.")
                    if (state.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.release.notes.replace("**", "").replace("## ", ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { app.downloadUpdate(state.release) }) { Text("Update") } },
            dismissButton = { TextButton(onClick = app::dismissUpdate) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Downloading update") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        is UpdateState.Ready -> {
            // Android asks once for permission to install apps from Songify.
            val canInstall = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
            AlertDialog(
                onDismissRequest = app::dismissUpdate,
                title = { Text("Update ready") },
                text = {
                    Text(
                        if (canInstall) "Tap Install to finish updating."
                        else "Allow Songify to install updates on the next screen, come back, then tap Install.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (canInstall) {
                            context.startActivity(installIntent(context, state.apk))
                        } else {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }) { Text(if (canInstall) "Install" else "Allow") }
                },
                dismissButton = { TextButton(onClick = app::dismissUpdate) { Text("Later") } },
            )
        }
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = app::dismissUpdate,
            title = { Text("Updates") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = app::dismissUpdate) { Text("OK") } },
        )
    }
}
