package com.willykez.repomaster.ui.screens.apkexplorer

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.willykez.repomaster.data.ApkInstaller
import com.willykez.repomaster.data.PublicStorage
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.theme.CommandBlue
import com.willykez.repomaster.ui.theme.StatusClean
import com.willykez.repomaster.ui.theme.StatusDeleted
import java.io.File
import java.text.DateFormat
import java.util.Date

data class DownloadedApk(val repoName: String, val file: File)

/**
 * Every APK the Actions screen's "Install" button has downloaded, across every repo, browsable
 * in one place — not just a single "Clear cache" button in Settings. Each repo only ever has
 * one APK on disk at a time (installing a new one for a repo replaces the last, see
 * [ApkInstaller]), so this is genuinely "one row per repo with a cached build," not an
 * unbounded history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkExplorerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var apks by remember { mutableStateOf(listApks()) }
    var pendingDelete by remember { mutableStateOf<DownloadedApk?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloaded APKs", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        if (apks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Android, null, Modifier.size(48.dp), tint = StatusClean)
                    Spacer(Modifier.height(12.dp))
                    Text("No downloaded APKs", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "APKs you install from a repo's Actions screen show up here.",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(apks, key = { it.repoName }) { apk ->
                    GlassCard(Modifier.fillMaxWidth(), accent = CommandBlue) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(22.dp), tint = CommandBlue)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(apk.repoName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(apk.file.name, style = MaterialTheme.typography.bodySmall, color = StatusClean, maxLines = 1)
                                Text(
                                    "${formatBytes(apk.file.length())} · ${formatDate(apk.file.lastModified())}",
                                    style = MaterialTheme.typography.labelSmall, color = StatusClean,
                                )
                            }
                            IconButton(onClick = {
                                val intent = ApkInstaller.installIntentForFile(context, apk.file)
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No app found to install APKs", Toast.LENGTH_LONG).show()
                                }
                            }) {
                                Icon(Icons.Filled.InstallMobile, "Install", tint = CommandBlue)
                            }
                            IconButton(onClick = { pendingDelete = apk }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = StatusDeleted)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { apk ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${apk.file.name}?") },
            text = { Text("You can always re-download it from ${apk.repoName}'s Actions screen if you need it again.") },
            confirmButton = {
                TextButton(onClick = {
                    apk.file.parentFile?.deleteRecursively()
                    apks = listApks()
                    pendingDelete = null
                }) { Text("Delete", color = StatusDeleted) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** One subfolder per repo under `.RepoMaster/apk-downloads/` (see [PublicStorage.apkDownloadsDir]);
 *  each holds at most one APK at a time. Doesn't call [PublicStorage.apkDownloadsDir] itself,
 *  since that creates the folder as a side effect — this is read-only browsing and shouldn't
 *  conjure empty folders into existence for repos that have never downloaded anything. */
private fun listApks(): List<DownloadedApk> {
    val root = PublicStorage.apkDownloadsRootDir()
    if (!root.exists()) return emptyList()
    return root.listFiles { it.isDirectory }
        ?.mapNotNull { repoDir ->
            val apk = repoDir.listFiles { f -> f.extension.equals("apk", ignoreCase = true) }?.firstOrNull()
            apk?.let { DownloadedApk(repoDir.name, it) }
        }
        ?.sortedByDescending { it.file.lastModified() }
        ?: emptyList()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
