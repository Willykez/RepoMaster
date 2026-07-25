package com.willykez.repomaster.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.data.AppearancePrefs
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.theme.*

private val INTERVAL_OPTIONS = listOf(1L, 3L, 6L, 12L, 24L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }

    var notificationsDenied by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsDenied = !granted }

    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppearanceSection(context)
            GitIdentitySection(state, onSave = vm::setGitIdentity)
            BackgroundSyncSection(
                state = state,
                notificationsDenied = notificationsDenied,
                onToggle = { enabled ->
                    vm.setBackgroundSyncEnabled(enabled)
                    // POST_NOTIFICATIONS is a runtime permission on API 33+ — this is the
                    // only place in the app with an Activity to prompt from, since
                    // SyncWorker itself runs headless in the background. Harmless to call
                    // when already granted (the launcher just no-ops without a dialog).
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasPermission) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onIntervalChange = vm::setIntervalHours,
            )
            StorageSection(state, onClearCache = vm::clearApkCache, onRefresh = vm::refreshCacheSize)
            AboutSection(context)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = CommandBlue)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun AppearanceSection(context: android.content.Context) {
    val themeMode by AppearancePrefs.themeMode.collectAsState()
    val dynamicColor by AppearancePrefs.dynamicColor.collectAsState()
    val editorTextSize by AppearancePrefs.editorTextSize.collectAsState()

    SettingsCard("Appearance", Icons.Filled.Palette) {
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppearancePrefs.ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { AppearancePrefs.setThemeMode(context, mode) },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wallpaper-based color")
                    Text(
                        "Derive the theme's colors from your wallpaper instead of Repo Master's own violet/coral palette",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                }
                Switch(checked = dynamicColor, onCheckedChange = { AppearancePrefs.setDynamicColor(context, it) })
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Editor text size", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppearancePrefs.EditorTextSize.entries.forEach { size ->
                FilterChip(
                    selected = editorTextSize == size,
                    onClick = { AppearancePrefs.setEditorTextSize(context, size) },
                    label = { Text(size.label) },
                )
            }
        }
    }
}

@Composable
private fun GitIdentitySection(state: SettingsUiState, onSave: (String, String) -> Unit) {
    var name by remember(state.authorName) { mutableStateOf(state.authorName) }
    var email by remember(state.authorEmail) { mutableStateOf(state.authorEmail) }
    val dirty = name != state.authorName || email != state.authorEmail

    SettingsCard("Git identity", Icons.Filled.Person) {
        Text(
            "The name and email attached to every commit you make in this app.",
            style = MaterialTheme.typography.bodySmall, color = StatusClean,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (dirty) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onSave(name, email) }, modifier = Modifier.align(Alignment.End)) { Text("Save") }
        }
    }
}

@Composable
private fun BackgroundSyncSection(
    state: SettingsUiState, notificationsDenied: Boolean,
    onToggle: (Boolean) -> Unit, onIntervalChange: (Long) -> Unit,
) {
    SettingsCard("Background sync", Icons.Filled.Sync) {
        Text(
            "Periodically checks every repo for new commits on the remote (fetch only — " +
                "it never merges or changes your working tree on its own). Needs network access. " +
                "When a check finds new commits, you'll get a notification.",
            style = MaterialTheme.typography.bodySmall, color = StatusClean,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Enable background sync")
            Switch(checked = state.backgroundSyncEnabled, onCheckedChange = onToggle)
        }

        if (notificationsDenied) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Notifications are off, so background sync will still run but won't alert you when it finds new commits. " +
                    "You can turn them back on from this app's system notification settings.",
                style = MaterialTheme.typography.bodySmall, color = StatusDeleted,
            )
        }

        if (state.backgroundSyncEnabled) {
            Spacer(Modifier.height(16.dp))
            Text("Check every", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVAL_OPTIONS.forEach { hours ->
                    FilterChip(
                        selected = state.intervalHours == hours,
                        onClick = { onIntervalChange(hours) },
                        label = { Text(if (hours < 24) "${hours}h" else "1d") },
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun StorageSection(state: SettingsUiState, onClearCache: () -> Unit, onRefresh: () -> Unit) {
    SettingsCard("Storage", Icons.Filled.Folder) {
        Text("Repo folder", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            state.storageRootPath, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, color = StatusClean,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloaded APK cache")
                Text(
                    if (state.isCalculatingCache) "Calculating…" else formatBytes(state.apkCacheBytes),
                    style = MaterialTheme.typography.bodySmall, color = StatusClean,
                )
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Recalculate", tint = StatusClean) }
            TextButton(onClick = onClearCache, enabled = state.apkCacheBytes > 0) { Text("Clear") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Build artifacts downloaded via the Actions screen's Install button — always safe to clear, since installing again just re-downloads them.",
            style = MaterialTheme.typography.bodySmall, color = StatusClean,
        )
    }
}

@Composable
private fun AboutSection(context: android.content.Context) {
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    SettingsCard("About", Icons.Filled.Info) {
        Text("Repo Master", fontWeight = FontWeight.SemiBold)
        Text("Version $versionName", style = MaterialTheme.typography.bodySmall, color = StatusClean)
    }
}
