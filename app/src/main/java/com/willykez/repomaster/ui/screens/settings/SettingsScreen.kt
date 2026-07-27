package com.willykez.repomaster.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.data.AccentPalettePrefs
import com.willykez.repomaster.data.AppearancePrefs
import com.willykez.repomaster.ui.components.GroupPosition
import com.willykez.repomaster.ui.components.GroupedListItem
import com.willykez.repomaster.ui.components.GroupedListSection
import com.willykez.repomaster.ui.components.SettingsRow
import com.willykez.repomaster.ui.components.groupPositionFor
import com.willykez.repomaster.ui.theme.*

private val INTERVAL_OPTIONS = listOf(1L, 3L, 6L, 12L, 24L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenApkDownloads: () -> Unit = {}, vm: SettingsViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }

    var notificationsDenied by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsDenied = !granted }

    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showIdentityDialog by remember { mutableStateOf(false) }
    var showSyncIntervalDialog by remember { mutableStateOf(false) }
    var showAutomationDialog by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            GroupedListSection("Appearance") {
                GroupedListItem(GroupPosition.ONLY) {
                    SettingsRow(
                        icon = Icons.Filled.Palette,
                        title = "Appearance",
                        subtitle = "Theme, color palette \u0026 text size",
                        onClick = { showAppearanceSheet = true },
                    )
                }
            }

            GroupedListSection("Git identity") {
                GroupedListItem(GroupPosition.ONLY) {
                    SettingsRow(
                        icon = Icons.Filled.Person,
                        title = state.authorName,
                        subtitle = state.authorEmail,
                        onClick = { showIdentityDialog = true },
                    )
                }
            }

            GroupedListSection("Background sync") {
                val rows = if (state.backgroundSyncEnabled) 2 else 1
                var idx = 0
                GroupedListItem(groupPositionFor(idx++, rows)) {
                    SettingsRow(
                        icon = Icons.Filled.Sync,
                        title = "Background sync",
                        subtitle = if (state.backgroundSyncEnabled) "Checking every ${formatInterval(state.intervalHours)}" else "Off",
                        trailing = {
                            Switch(
                                checked = state.backgroundSyncEnabled,
                                onCheckedChange = { enabled ->
                                    vm.setBackgroundSyncEnabled(enabled)
                                    // POST_NOTIFICATIONS is a runtime permission on API 33+ —
                                    // this is the only place in the app with an Activity to
                                    // prompt from, since SyncWorker runs headless. Harmless
                                    // when already granted (the launcher just no-ops).
                                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        val hasPermission = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.POST_NOTIFICATIONS,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!hasPermission) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                            )
                        },
                    )
                }
                if (state.backgroundSyncEnabled) {
                    GroupedListItem(groupPositionFor(idx, rows)) {
                        SettingsRow(
                            icon = Icons.Filled.Timer,
                            title = "Check every",
                            subtitle = formatInterval(state.intervalHours),
                            onClick = { showSyncIntervalDialog = true },
                        )
                    }
                }
            }
            if (notificationsDenied) {
                Text(
                    "Notifications are off, so background sync will still run but won't alert you when it finds new commits.",
                    style = MaterialTheme.typography.labelSmall, color = StatusDeleted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            GroupedListSection("Automation") {
                GroupedListItem(GroupPosition.ONLY) {
                    SettingsRow(
                        icon = Icons.Filled.Bolt,
                        title = "Auto-commit \u0026 push",
                        subtitle = if (state.automatedRepoIds.isEmpty()) "Off" else "${state.automatedRepoIds.size} repo(s) selected",
                        onClick = { showAutomationDialog = true },
                    )
                }
            }

            GroupedListSection("Storage") {
                GroupedListItem(GroupPosition.TOP) {
                    SettingsRow(
                        icon = Icons.Filled.Folder,
                        title = "Repo folder",
                        subtitle = state.storageRootPath,
                    )
                }
                GroupedListItem(GroupPosition.MIDDLE) {
                    SettingsRow(
                        icon = Icons.Filled.Android,
                        title = "Downloaded APKs",
                        subtitle = if (state.isCalculatingCache) "Calculating…" else formatBytes(state.apkCacheBytes),
                        onClick = onOpenApkDownloads,
                    )
                }
                GroupedListItem(GroupPosition.BOTTOM) {
                    SettingsRow(
                        icon = Icons.Filled.DeleteSweep,
                        title = "Clear APK cache",
                        subtitle = "Removes every downloaded build — re-download anytime from Actions",
                        trailing = {
                            TextButton(onClick = vm::clearApkCache, enabled = state.apkCacheBytes > 0) { Text("Clear") }
                        },
                    )
                }
            }

            GroupedListSection("About") {
                GroupedListItem(GroupPosition.ONLY) {
                    val versionName = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
                    }
                    SettingsRow(icon = Icons.Filled.Info, title = "Repo Master", subtitle = "Version $versionName")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAppearanceSheet) {
        ModalBottomSheet(onDismissRequest = { showAppearanceSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AppearanceSheetContent(context)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showIdentityDialog) {
        GitIdentityDialog(state = state, onSave = vm::setGitIdentity, onDismiss = { showIdentityDialog = false })
    }

    if (showSyncIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showSyncIntervalDialog = false },
            title = { Text("Check every") },
            text = {
                Column {
                    INTERVAL_OPTIONS.forEach { hours ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.setIntervalHours(hours); showSyncIntervalDialog = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.intervalHours == hours, onClick = { vm.setIntervalHours(hours); showSyncIntervalDialog = false })
                            Spacer(Modifier.width(8.dp))
                            Text(formatInterval(hours))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSyncIntervalDialog = false }) { Text("Done") } },
        )
    }

    if (showAutomationDialog) {
        AutomationDialog(
            repos = state.allRepos,
            selectedIds = state.automatedRepoIds,
            onSave = { vm.setAutomatedRepoIds(it); showAutomationDialog = false },
            onDismiss = { showAutomationDialog = false },
        )
    }
}

@Composable
private fun AutomationDialog(
    repos: List<com.willykez.repomaster.data.db.entity.RepoEntity>,
    selectedIds: Set<Long>,
    onSave: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember(selectedIds) { mutableStateOf(selectedIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-commit \u0026 push") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.WarningAmber, null, Modifier.size(16.dp).padding(top = 2.dp), tint = Amber)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "During background sync, any selected repo with local changes gets everything staged, committed, and pushed automatically — with nobody reviewing the diff first. Only enable this for repos where that's genuinely fine, like scratch notes or generated output.",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (repos.isEmpty()) {
                    Text("No repos tracked yet.", style = MaterialTheme.typography.bodySmall, color = StatusClean)
                } else {
                    repos.forEach { repo ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                working = if (repo.id in working) working - repo.id else working + repo.id
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = repo.id in working, onCheckedChange = {
                                working = if (it) working + repo.id else working - repo.id
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(repo.name, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(working) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatInterval(hours: Long) = if (hours < 24) "${hours}h" else "1 day"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

@Composable
private fun GitIdentityDialog(state: SettingsUiState, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(state.authorName) }
    var email by remember { mutableStateOf(state.authorEmail) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Git identity") },
        text = {
            Column {
                Text(
                    "The name and email attached to every commit you make in this app.",
                    style = MaterialTheme.typography.bodySmall, color = StatusClean,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, email); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Everything appearance-related lives in one sheet — theme mode, wallpaper-based color,
 * editor text size, and the accent color palette picker — rather than four separate rows on
 * the main Settings list. Matches the reference app's own "Appearance" sheet pattern: one
 * entry point on the list, a focused sheet for the actual choices.
 */
@Composable
private fun AppearanceSheetContent(context: android.content.Context) {
    val themeMode by AppearancePrefs.themeMode.collectAsState()
    val dynamicColor by AppearancePrefs.dynamicColor.collectAsState()
    val editorTextSize by AppearancePrefs.editorTextSize.collectAsState()
    var selectedPaletteId by remember { mutableStateOf(AccentPalettePrefs.currentPresetId(context)) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customHexInput by remember { mutableStateOf(AccentPalettePrefs.currentCustomHex(context) ?: "") }
    var customHexError by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

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
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wallpaper-based color")
                    Text(
                        "Derive colors from your wallpaper instead of the palette below",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                }
                Switch(checked = dynamicColor, onCheckedChange = { AppearancePrefs.setDynamicColor(context, it) })
            }
        }

        Spacer(Modifier.height(20.dp))
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

        Spacer(Modifier.height(20.dp))
        Text("Color palette", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Repaints the whole app — cards, buttons, status colors, the commit graph.",
            style = MaterialTheme.typography.bodySmall, color = StatusClean,
        )
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, null, Modifier.size(13.dp), tint = Amber)
                Spacer(Modifier.width(4.dp))
                Text("Wallpaper-based color above overrides this while it's on", style = MaterialTheme.typography.labelSmall, color = Amber)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            com.willykez.repomaster.ui.theme.AccentPalette.presets.forEach { preset ->
                PaletteSwatch(
                    color = preset.swatch, label = preset.label, selected = selectedPaletteId == preset.id,
                    onClick = { AccentPalettePrefs.selectPreset(context, preset.id); selectedPaletteId = preset.id },
                )
            }
            CustomSwatch(
                selected = selectedPaletteId == "custom",
                currentHex = AccentPalettePrefs.currentCustomHex(context),
                onClick = { showCustomDialog = true },
            )
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom color") },
            text = {
                Column {
                    Text(
                        "Enter a hex color — a matching secondary and tertiary accent are generated from it automatically.",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customHexInput,
                        onValueChange = { customHexInput = it; customHexError = false },
                        label = { Text("Hex color") },
                        placeholder = { Text("#7C3AED") },
                        singleLine = true,
                        isError = customHexError,
                        supportingText = if (customHexError) { { Text("Enter a valid 6-digit hex color, e.g. #7C3AED") } } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val applied = AccentPalettePrefs.selectCustomHex(context, customHexInput)
                    if (applied) { selectedPaletteId = "custom"; showCustomDialog = false } else customHexError = true
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showCustomDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PaletteSwatch(color: androidx.compose.ui.graphics.Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
                .then(
                    if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape)
                    else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = contrastingIconColor(color), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = StatusClean, maxLines = 1)
    }
}

@Composable
private fun CustomSwatch(selected: Boolean, currentHex: String?, onClick: () -> Unit) {
    val swatchColor = currentHex?.let { com.willykez.repomaster.ui.theme.parseHexColorOrNull(it) } ?: MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(swatchColor)
                .border(
                    if (selected) 2.5.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else StatusClean.copy(alpha = 0.4f),
                    androidx.compose.foundation.shape.CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (selected) Icons.Filled.Check else Icons.Filled.Edit,
                null,
                tint = if (currentHex != null) contrastingIconColor(swatchColor) else StatusClean,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("Custom", style = MaterialTheme.typography.labelSmall, color = StatusClean, maxLines = 1)
    }
}

/** Picks black or white for icon contrast against an arbitrary swatch color — simple
 *  perceptual-luminance threshold. */
@Composable
private fun contrastingIconColor(background: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.6f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
}
