package com.willykez.repomaster.ui.screens.changes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.git.GitFileEntry
import com.willykez.repomaster.git.GitFileStatus
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.components.WeaveRefreshIndicator
import com.willykez.repomaster.ui.screens.actions.StatusIcon
import com.willykez.repomaster.ui.screens.actions.statusColor
import com.willykez.repomaster.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun ChangesScreen(
    repoId: Long,
    onBack: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenActions: () -> Unit,
    onOpenDiff: (path: String, staged: Boolean) -> Unit,
    vm: ChangesViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showAmendDialog by remember { mutableStateOf(false) }
    var showSquashDialog by remember { mutableStateOf(false) }
    var showCherryPickDialog by remember { mutableStateOf(false) }
    var showPullForceDialog by remember { mutableStateOf(false) }
    var showPushForceDialog by remember { mutableStateOf(false) }
    var showDiscardAllDialog by remember { mutableStateOf(false) }
    var fileToDiscard by remember { mutableStateOf<GitFileEntry?>(null) }

    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); vm.dismissMessage() }
    }
    // Auto-prompt after push: only meaningful for repos we actually track CI for (a
    // GitHub remote with a token attached) — skips the tick's initial value of 0 so this
    // doesn't fire on first composition/screen open, only on an actual push completing.
    var lastPushTick by remember { mutableStateOf(0) }
    LaunchedEffect(state.pushSuccessTick) {
        val tick = state.pushSuccessTick
        if (tick > 0 && tick != lastPushTick) {
            lastPushTick = tick
            if (state.ciApplicable) {
                val result = snack.showSnackbar("Pushed — checking CI…", actionLabel = "View")
                if (result == SnackbarResult.ActionPerformed) onOpenActions()
            } else {
                snack.showSnackbar("Pushed")
            }
        }
    }

    // Pull-to-refresh via the Material2 "pullrefresh" primitives (androidx.compose.material,
    // @ExperimentalMaterialApi) rather than material3's own pull-to-refresh — the material3
    // version that ships in this project's pinned compose-bom doesn't expose it under the
    // plain androidx.compose.material3 package the way a newer BOM would, so this is the
    // long-stable, unambiguous API instead of a guess about material3's internal package layout.
    val pullRefreshState = rememberPullRefreshState(refreshing = state.isLoading, onRefresh = vm::refresh)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.repo?.name ?: "Changes", fontWeight = FontWeight.SemiBold)
                        val branch = state.currentBranch
                        if (branch.isNotBlank()) {
                            Text(
                                "$branch:origin/$branch",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        clipboard.setText(AnnotatedString(branch))
                                        vm.showTransientMessage("Branch name copied")
                                    },
                                ),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    // Latest Actions run for this repo — the whole point is not having to
                    // leave the app to see whether the build passed after a push. Tapping
                    // it opens the full Actions tab for run details / logs / re-run.
                    if (state.ciApplicable) {
                        val run = state.ciRun
                        IconButton(onClick = onOpenActions) {
                            if (run == null) {
                                Icon(Icons.Filled.PlayCircleOutline, "Actions", tint = StatusClean)
                            } else {
                                StatusIcon(run.status, run.conclusion, statusColor(run.status, run.conclusion), size = 22.dp)
                            }
                        }
                    }
                    // Push is the #1 reason someone opens this menu — surface it directly
                    // instead of burying it in an overflow list.
                    IconButton(onClick = vm::push, enabled = !state.isWorking) {
                        Icon(Icons.Filled.Upload, "Push", tint = Amber)
                    }
                    IconButton(onClick = vm::refresh, enabled = !state.isWorking) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                    // Everything else lives in a grouped bottom sheet, not a flat
                    // 24-item dropdown — see RepoToolsSheet below.
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        snackbarHost = { SnackbarHost(snack) { d -> Snackbar(d) } },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .pullRefresh(pullRefreshState),
        ) {
            WeaveRefreshIndicator(refreshing = state.isLoading, progress = pullRefreshState.progress)

            AnimatedVisibility(
                visible = state.hasConflicts,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(color = StatusDeleted.copy(alpha = 0.15f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = StatusDeleted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Merge conflicts need resolving",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = onOpenConflicts) { Text("Resolve") }
                        }
                    }
                }

                // Ahead/behind status bar
                AnimatedVisibility(
                    visible = state.ahead > 0 || state.behind > 0 || state.statusLine.isNotBlank(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.statusLine.isNotBlank())
                                Text(state.statusLine, style = MaterialTheme.typography.bodySmall, color = StatusClean)
                            if (state.ahead > 0 || state.behind > 0) {
                                Icon(Icons.Filled.Upload, null, Modifier.size(14.dp), tint = StatusAdded)
                                Text("${state.ahead}", style = MaterialTheme.typography.labelSmall, color = StatusAdded)
                                Icon(Icons.Filled.Download, null, Modifier.size(14.dp), tint = Amber)
                                Text("${state.behind}", style = MaterialTheme.typography.labelSmall, color = Amber)
                            }
                        }
                    }
                }

                if (state.isLoading) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Staged
                        item {
                            SectionRow("Staged (${state.staged.size})",
                                action = if (state.staged.isNotEmpty()) "Unstage all" else null,
                                onAction = { vm.unstageAll() })
                        }
                        if (state.staged.isEmpty()) {
                            item { PlaceholderRow("Nothing staged") }
                        } else {
                            items(state.staged, key = { "s:" + it.path }) { e ->
                                FileRow(e, staged = true,
                                    onToggle = { vm.unstageFile(e) },
                                    onDiscard = { fileToDiscard = e },
                                    onDiff = { onOpenDiff(e.path, true) })
                            }
                        }
                        item { Spacer(Modifier.height(4.dp)) }
                        // Unstaged
                        item {
                            SectionRow("Changes (${state.unstaged.size})",
                                action = if (state.unstaged.isNotEmpty()) "Stage all" else null,
                                onAction = { vm.stageAll() })
                        }
                        if (state.unstaged.isEmpty()) {
                            item { PlaceholderRow("Working tree clean") }
                        } else {
                            items(state.unstaged, key = { "u:" + it.path }) { e ->
                                FileRow(e, staged = false,
                                    onToggle = { vm.stageFile(e) },
                                    onDiscard = { fileToDiscard = e },
                                    onDiff = { onOpenDiff(e.path, false) })
                            }
                        }
                    }
                }

                // Commit bar
                CommitBar(
                    message = state.commitMessage,
                    onMessageChanged = vm::onCommitMessageChanged,
                    isWorking = state.isWorking,
                    hasChanges = state.staged.isNotEmpty() || state.unstaged.isNotEmpty(),
                    onCommit = vm::commit,
                    onPush = vm::push,
                    onGenerate = vm::generateCommitMessage,
                    onStageCommitPush = vm::stageCommitAndPush,
                    recentMessages = state.recentMessages,
                    onPickRecent = vm::applyRecentMessage,
                    template = state.commitTemplate,
                    onSaveTemplate = vm::setCommitTemplate,
                    showUpstreamNudge = state.currentBranch in PROTECTED_BRANCH_NAMES && state.hasUpstream == false,
                    branchName = state.currentBranch,
                )
        }
    }

    // ── dialogs ───────────────────────────────────────────────────────────────
    if (showResetDialog) {
        ConfirmDialog("Reset Hard?",
            "This will discard ALL uncommitted changes and reset to HEAD. Cannot be undone.",
            confirmLabel = "Reset Hard", danger = true,
            onConfirm = { vm.resetHard(); showResetDialog = false },
            onDismiss = { showResetDialog = false })
    }
    if (showPullForceDialog) {
        ConfirmDialog("Pull (Force)?",
            "This hard-resets your branch to match the remote. Any local commits not on the remote will be lost. Cannot be undone.",
            confirmLabel = "Force Pull", danger = true,
            onConfirm = { vm.pullForce(); showPullForceDialog = false },
            onDismiss = { showPullForceDialog = false })
    }
    if (showPushForceDialog) {
        ConfirmDialog("Push (Force)?",
            "This overwrites the remote branch with your local history. If someone else pushed in the meantime, their commits will be lost.",
            confirmLabel = "Force Push", danger = true,
            onConfirm = { vm.pushForce(); showPushForceDialog = false },
            onDismiss = { showPushForceDialog = false })
    }
    if (showDiscardAllDialog) {
        ConfirmDialog("Discard All Changes?",
            "This discards every uncommitted change in the working directory. Cannot be undone.",
            confirmLabel = "Discard All", danger = true,
            onConfirm = { vm.discardAll(); showDiscardAllDialog = false },
            onDismiss = { showDiscardAllDialog = false })
    }
    fileToDiscard?.let { e ->
        ConfirmDialog("Discard changes to ${e.path}?",
            "This discards uncommitted changes to this file. Cannot be undone.",
            confirmLabel = "Discard", danger = true,
            onConfirm = { vm.discardFile(e); fileToDiscard = null },
            onDismiss = { fileToDiscard = null })
    }
    if (showAmendDialog) {
        SingleInputDialog("Amend Last Commit", "New commit message", state.commitMessage,
            onConfirm = { msg -> vm.amendCommit(msg); showAmendDialog = false },
            onDismiss = { showAmendDialog = false })
    }
    var squashCount by remember { mutableStateOf("2") }
    if (showSquashDialog) {
        SingleInputDialog("Squash Commits", "How many last commits to squash?", squashCount,
            onConfirm = { n -> vm.squash(n.toIntOrNull() ?: 2, state.commitMessage); showSquashDialog = false },
            onDismiss = { showSquashDialog = false })
    }
    var cherryPickSha by remember { mutableStateOf("") }
    if (showCherryPickDialog) {
        SingleInputDialog("Cherry-Pick", "Commit SHA to cherry-pick", cherryPickSha,
            onConfirm = { sha -> vm.cherryPick(sha); showCherryPickDialog = false },
            onDismiss = { showCherryPickDialog = false })
    }

    if (showMenu) {
        RepoToolsSheet(
            onDismiss = { showMenu = false },
            onStageAll = vm::stageAll,
            onCommitAll = vm::stageAllAndCommit,
            onDiscardAll = { showDiscardAllDialog = true },
            onFetch = vm::fetch,
            onPullMerge = vm::pullMerge,
            onPullRebase = vm::pullRebase,
            onPullForce = { showPullForceDialog = true },
            onPushForce = { showPushForceDialog = true },
            onSyncMerge = vm::syncMerge,
            onSyncRebase = vm::syncRebase,
            onCherryPick = { showCherryPickDialog = true },
            onAmend = { showAmendDialog = true },
            onSquash = { showSquashDialog = true },
            onResetSoft = vm::resetSoft,
            onResetMixed = vm::resetMixed,
            onResetHard = { showResetDialog = true },
        )
    }
}

/**
 * Every less-frequent repo action, organized as labeled groups of glass
 * cards instead of one long flat dropdown. Grouping (not just alphabetizing)
 * is what makes 20+ actions scannable: a person looking for "push" reasons
 * for anxiety about the remote in one visual cluster, "reset" danger in
 * another, and navigation to other screens in a third — never all mixed
 * together in a single scrolling list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoToolsSheet(
    onDismiss: () -> Unit,
    onStageAll: () -> Unit,
    onCommitAll: () -> Unit,
    onDiscardAll: () -> Unit,
    onFetch: () -> Unit,
    onPullMerge: () -> Unit,
    onPullRebase: () -> Unit,
    onPullForce: () -> Unit,
    onPushForce: () -> Unit,
    onSyncMerge: () -> Unit,
    onSyncRebase: () -> Unit,
    onCherryPick: () -> Unit,
    onAmend: () -> Unit,
    onSquash: () -> Unit,
    onResetSoft: () -> Unit,
    onResetMixed: () -> Unit,
    onResetHard: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var openGroup by remember { mutableStateOf<String?>(null) }

    fun close(action: () -> Unit) {
        action()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Repo tools", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))

            ToolGroup(
                title = "Sync with remote", icon = Icons.Filled.Sync, isOpen = openGroup == "sync",
                onToggle = { openGroup = if (openGroup == "sync") null else "sync" },
            ) {
                ToolRow("Fetch", Icons.Filled.Download) { close(onFetch) }
                ToolRow("Pull (merge)", Icons.Filled.MergeType) { close(onPullMerge) }
                ToolRow("Pull (rebase)", Icons.Filled.LinearScale) { close(onPullRebase) }
                ToolRow("Sync (merge)", Icons.Filled.SyncAlt) { close(onSyncMerge) }
                ToolRow("Sync (rebase)", Icons.Filled.Sync) { close(onSyncRebase) }
                ToolRow("Pull (force) — overwrites local commits", Icons.Filled.Warning, danger = true) { close(onPullForce) }
                ToolRow("Push (force) — overwrites the remote", Icons.Filled.Warning, danger = true) { close(onPushForce) }
            }

            ToolGroup(
                title = "Staging", icon = Icons.Filled.Add, isOpen = openGroup == "staging",
                onToggle = { openGroup = if (openGroup == "staging") null else "staging" },
            ) {
                ToolRow("Stage all", Icons.Filled.Add) { close(onStageAll) }
                ToolRow("Stage all & commit", Icons.Filled.Check) { close(onCommitAll) }
                ToolRow("Discard all changes", Icons.Filled.DeleteSweep, danger = true) { close(onDiscardAll) }
            }

            ToolGroup(
                title = "History", icon = Icons.Filled.History, isOpen = openGroup == "history",
                onToggle = { openGroup = if (openGroup == "history") null else "history" },
            ) {
                ToolRow("Cherry-pick", Icons.Filled.ContentCopy) { close(onCherryPick) }
                ToolRow("Amend last commit", Icons.Filled.Edit) { close(onAmend) }
                ToolRow("Squash commits…", Icons.Filled.Compress) { close(onSquash) }
            }

            ToolGroup(
                title = "Reset", icon = Icons.Filled.RestartAlt, isOpen = openGroup == "reset",
                onToggle = { openGroup = if (openGroup == "reset") null else "reset" },
            ) {
                ToolRow("Reset (soft)", Icons.Filled.Undo) { close(onResetSoft) }
                ToolRow("Reset (mixed)", Icons.Filled.RestartAlt) { close(onResetMixed) }
                ToolRow("Reset (hard)", Icons.Filled.DeleteForever, danger = true) { close(onResetHard) }
            }

            Text(
                "Branches, tags, stash, remotes, and files live in the Tools tab.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusClean,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }
}

/** A collapsible group header + its rows, rendered as one glass card. Collapsed by default
 *  so the sheet opens short — a person expands only the group they came here for. */
@Composable
private fun ToolGroup(
    title: String,
    icon: ImageVector,
    isOpen: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = CommandBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(
                    if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                    tint = StatusClean, modifier = Modifier.size(20.dp),
                )
            }
            if (isOpen) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(vertical = 4.dp), content = content)
            }
        }
    }
}

@Composable
private fun ToolRow(label: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) StatusDeleted else StatusClean, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (danger) StatusDeleted else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SectionRow(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
    }
}

@Composable
private fun PlaceholderRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = StatusClean,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))
}

/** Icon + tint for a file's status — used by the row's leading icon so the same visual
 * language (green=added, amber=modified, red=deleted/conflict) is consistent everywhere
 * a file status shows up, replacing the old plain colored-letter treatment. */
private fun statusIcon(status: GitFileStatus): Pair<ImageVector, Color> = when (status) {
    GitFileStatus.ADDED -> Icons.Filled.AddCircle to StatusAdded
    GitFileStatus.MODIFIED -> Icons.Filled.Edit to StatusModified
    GitFileStatus.DELETED -> Icons.Filled.RemoveCircle to StatusDeleted
    GitFileStatus.RENAMED -> Icons.Filled.DriveFileRenameOutline to StatusModified
    GitFileStatus.TYPE_CHANGED -> Icons.Filled.SwapHoriz to StatusModified
    GitFileStatus.CONFLICTED -> Icons.Filled.ErrorOutline to StatusDeleted
}

private data class SwipeBackgroundSpec(val color: Color, val icon: ImageVector, val label: String, val alignEnd: Boolean)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    e: GitFileEntry, staged: Boolean,
    onToggle: () -> Unit, onDiscard: () -> Unit, onDiff: () -> Unit,
) {
    val (icon, color) = statusIcon(e.status)
    var showOptions by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Swipe right = primary action (stage/unstage). Swipe left = discard, but only for
    // unstaged files — staged files have nothing destructive to do in that direction, so
    // that gesture is disabled for them rather than silently doing nothing.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onToggle()
                }
                SwipeToDismissBoxValue.EndToStart -> if (!staged) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onDiscard()
                }
                SwipeToDismissBoxValue.Settled -> {}
            }
            false // always snap back — the row leaves the list via data change, not the swipe animation
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromEndToStart = !staged,
        enableDismissFromStartToEnd = true,
        backgroundContent = {
            val spec = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd ->
                    SwipeBackgroundSpec(StatusAdded, Icons.Filled.Check, if (staged) "Unstage" else "Stage", alignEnd = false)
                SwipeToDismissBoxValue.EndToStart ->
                    SwipeBackgroundSpec(StatusDeleted, Icons.Filled.DeleteSweep, "Discard", alignEnd = true)
                else -> SwipeBackgroundSpec(StatusClean, Icons.Filled.Check, "", alignEnd = false)
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(spec.color)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (spec.alignEnd) Arrangement.End else Arrangement.Start,
            ) {
                Icon(spec.icon, null, tint = Cream)
                Spacer(Modifier.width(6.dp))
                Text(spec.label, color = Cream, style = MaterialTheme.typography.labelMedium)
            }
        },
    ) {
        GlassCard(
            Modifier.fillMaxWidth(),
            accent = color,
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = onToggle, onLongClick = { showOptions = true })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text(e.path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                IconButton(onClick = onDiff, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Code, "Diff", Modifier.size(16.dp), tint = StatusClean)
                }
                Box {
                    IconButton(onClick = { showOptions = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, "Options", Modifier.size(16.dp), tint = StatusClean)
                    }
                    DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                        DropdownMenuItem(text = { Text(if (staged) "Unstage" else "Stage") },
                            onClick = { showOptions = false; onToggle() })
                        DropdownMenuItem(text = { Text("View Diff") },
                            onClick = { showOptions = false; onDiff() })
                        if (!staged) DropdownMenuItem(
                            text = { Text("Discard Changes", color = StatusDeleted) },
                            onClick = { showOptions = false; onDiscard() })
                    }
                }
            }
        }
    }
}

private val COMMIT_TYPES = listOf("feat", "fix", "chore", "docs", "refactor", "test", "perf", "style", "ci", "build")
private val COMMIT_PREFIX_REGEX = Regex("""^(\w+)(\(([^)]*)\))?:\s*""")

/**
 * Picking a type chip rewrites just the `type(scope): ` prefix of the message, in place —
 * there's no separate "preview" text to keep in sync with what you actually type, the field
 * itself always shows exactly what will be committed. Reselecting the same chip clears the
 * prefix back out; switching chips swaps it. Scope is optional and only appears once a type's
 * picked, since a bare `feat: ` is a completely valid conventional commit on its own.
 */
private val PROTECTED_BRANCH_NAMES = setOf("main", "master")

@Composable
private fun CommitBar(
    message: String, onMessageChanged: (String) -> Unit,
    isWorking: Boolean, hasChanges: Boolean,
    onCommit: () -> Unit, onPush: () -> Unit,
    onGenerate: () -> Unit, onStageCommitPush: () -> Unit,
    recentMessages: List<String>, onPickRecent: (String) -> Unit,
    template: com.willykez.repomaster.data.CommitPrefs.Template,
    onSaveTemplate: (prefix: String, footer: String) -> Unit,
    showUpstreamNudge: Boolean, branchName: String,
) {
    val currentMatch = remember(message) { COMMIT_PREFIX_REGEX.find(message) }
    val currentType = currentMatch?.groupValues?.get(1)?.takeIf { it in COMMIT_TYPES }
    var scope by remember { mutableStateOf(currentMatch?.groupValues?.get(3) ?: "") }
    var showHistory by remember { mutableStateOf(false) }
    var showTemplateEditor by remember { mutableStateOf(false) }

    fun applyType(type: String?) {
        val body = if (currentMatch != null) message.substring(currentMatch.range.last + 1) else message
        val newPrefix = when {
            type == null -> ""
            scope.isNotBlank() -> "$type($scope): "
            else -> "$type: "
        }
        onMessageChanged(newPrefix + body)
    }

    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                COMMIT_TYPES.forEach { type ->
                    FilterChip(
                        selected = currentType == type,
                        onClick = { applyType(if (currentType == type) null else type) },
                        label = { Text(type, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            if (currentType != null) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = scope,
                    onValueChange = { scope = it; applyType(currentType) },
                    label = { Text("Scope (optional)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = message, onValueChange = onMessageChanged,
                label = { Text("Commit message") },
                trailingIcon = {
                    Row {
                        // Only worth offering once there's actually something to summarize —
                        // an empty working tree has nothing for the heuristic to describe.
                        if (hasChanges) {
                            IconButton(onClick = onGenerate) {
                                Icon(Icons.Filled.AutoAwesome, "Generate message from changes", tint = CommandBlue)
                            }
                        }
                        if (recentMessages.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { showHistory = true }) {
                                    Icon(Icons.Filled.History, "Recent messages", tint = StatusClean)
                                }
                                DropdownMenu(expanded = showHistory, onDismissRequest = { showHistory = false }) {
                                    recentMessages.forEach { msg ->
                                        DropdownMenuItem(
                                            text = { Text(msg, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                                            onClick = { onPickRecent(msg); showHistory = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 4,
            )
            val firstLineLength = message.substringBefore('\n').length
            if (firstLineLength > 72) {
                Text(
                    "First line is $firstLineLength characters — most tools wrap or truncate a commit title past ~72",
                    style = MaterialTheme.typography.labelSmall, color = Amber,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().clickable { showTemplateEditor = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Description, null, Modifier.size(13.dp), tint = StatusClean)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (template.prefix.isBlank() && template.footer.isBlank()) "No commit template set"
                    else "Template: ${template.prefix.ifBlank { "—" }} … ${template.footer.ifBlank { "—" }}",
                    style = MaterialTheme.typography.labelSmall, color = StatusClean, maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (showUpstreamNudge) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WarningAmber, null, Modifier.size(14.dp), tint = Amber)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "\"$branchName\" has no upstream yet — pushing will set one up on the remote",
                        style = MaterialTheme.typography.labelSmall, color = Amber,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPush, modifier = Modifier.weight(1f), enabled = !isWorking) {
                    Icon(Icons.Filled.Upload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Push")
                }
                Button(onClick = onCommit, modifier = Modifier.weight(1f), enabled = !isWorking) {
                    if (isWorking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Commit") }
                }
            }
            Spacer(Modifier.height(8.dp))
            // The "just ship it" shortcut — stages everything, commits with whatever's in
            // the box above, pushes. Kept visually distinct (full-width, tonal emerald)
            // from Push/Commit above so it doesn't get tapped by reflex when only a partial
            // stage+commit was actually intended.
            Button(
                onClick = onStageCommitPush,
                enabled = !isWorking && hasChanges,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black),
            ) {
                if (isWorking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stage All + Commit + Push")
                }
            }
        }
    }

    if (showTemplateEditor) {
        var prefix by remember { mutableStateOf(template.prefix) }
        var footer by remember { mutableStateOf(template.footer) }
        AlertDialog(
            onDismissRequest = { showTemplateEditor = false },
            title = { Text("Commit template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Applied automatically whenever you tap Generate. Leave either blank to skip it.",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                    OutlinedTextField(
                        value = prefix, onValueChange = { prefix = it },
                        label = { Text("Prefix") },
                        placeholder = { Text("e.g. \"[PROJ-123] \"") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = footer, onValueChange = { footer = it },
                        label = { Text("Footer") },
                        placeholder = { Text("e.g. \"Signed-off-by: you@example.com\"") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onSaveTemplate(prefix, footer); showTemplateEditor = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showTemplateEditor = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                // A confirm-dialog tap is the one moment in the app where the person is
                // deliberately committing to something they can't casually undo (reset
                // hard, force push, drop a stash) — a firm haptic here is confirmation
                // the tap actually registered, on top of the dialog closing.
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onConfirm()
            }) { Text(confirmLabel, color = if (danger) StatusDeleted else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun SingleInputDialog(title: String, label: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
