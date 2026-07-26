package com.willykez.repomaster.ui.screens.repolist

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.data.PublicStorage
import com.willykez.repomaster.data.db.entity.RepoEntity
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.components.WeaveRefreshIndicator
import com.willykez.repomaster.ui.screens.clone.CloneScreen
import com.willykez.repomaster.ui.theme.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun RepoListScreen(
    onOpenRepo: (Long) -> Unit,
    onOpenCredentials: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: RepoListViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }
    var repoPendingDelete by remember { mutableStateOf<RepoEntity?>(null) }
    var repoPendingGitHubDelete by remember { mutableStateOf<RepoEntity?>(null) }
    var repoPendingPullForce by remember { mutableStateOf<RepoEntity?>(null) }
    var repoPendingPushForce by remember { mutableStateOf<RepoEntity?>(null) }
    var repoPendingCredential by remember { mutableStateOf<RepoEntity?>(null) }
    var showCloneSheet by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var hasStorageAccess by remember { mutableStateOf(PublicStorage.hasStorageAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStorageAccess = PublicStorage.hasStorageAccess(context)
                if (hasStorageAccess) vm.scanForLocalRepos(silent = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { snack.showSnackbar(it); vm.dismissSnackbar() }
    }

    // Same Material2 pullrefresh primitives used on the Changes screen — see the note
    // there on why material3's own pull-to-refresh isn't used in this project.
    val pullRefreshState = rememberPullRefreshState(refreshing = state.isRefreshing, onRefresh = vm::refresh)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showSearch) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = vm::setSearchQuery,
                            placeholder = { Text("Search repos…") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                cursorColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Repo Master", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    if (showSearch) {
                        IconButton(onClick = { showSearch = false; vm.setSearchQuery("") }) {
                            Icon(Icons.Filled.ArrowBack, "Close search")
                        }
                    }
                },
                actions = {
                    if (!showSearch) {
                        IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, "Search") }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                DropdownMenuItem(text = { Text("Most recent") },
                                    onClick = { showSortMenu = false; vm.setSortMode(RepoSortMode.RECENT) })
                                DropdownMenuItem(text = { Text("Name (A–Z)") },
                                    onClick = { showSortMenu = false; vm.setSortMode(RepoSortMode.NAME) })
                                DropdownMenuItem(text = { Text("Most changes first") },
                                    onClick = { showSortMenu = false; vm.setSortMode(RepoSortMode.HAS_CHANGES) })
                            }
                        }
                        // Credentials and Settings are destinations, not "add" actions, so they
                        // stay as plain single-tap icons rather than living inside a dropdown
                        // that mixed navigation with repo-creation — the FAB below is now the
                        // one, consistent place for every way to add a repo.
                        IconButton(onClick = onOpenCredentials) { Icon(Icons.Filled.Key, "Credentials") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface, actionIconContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        floatingActionButton = {
            RepoListFab(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                onCloneUrl = { fabExpanded = false; showCloneSheet = true },
                onDiscoverGitHub = { fabExpanded = false; onOpenDiscover() },
                onScanLocal = { fabExpanded = false; vm.scanForLocalRepos() },
            )
        },
        snackbarHost = { SnackbarHost(snack) { d -> Snackbar(d) } },
    ) { pad ->
        // Tapping anywhere on the list while the FAB menu is open collapses it first,
        // instead of leaving it open and letting the tap fall through to whatever's under it.
        Box(Modifier.fillMaxSize().let { if (fabExpanded) it.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { fabExpanded = false } else it }) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .pullRefresh(pullRefreshState),
        ) {
            if (!hasStorageAccess) {
                StorageAccessBanner(onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.startActivity(PublicStorage.allFilesAccessIntent(context))
                    }
                })
            }

            WeaveRefreshIndicator(refreshing = state.isRefreshing, progress = pullRefreshState.progress)

            val visibleRepos = state.visibleRepos

            if (state.repos.isEmpty()) {
                EmptyState(Modifier.weight(1f), onAddRepo = { showCloneSheet = true }, onDiscover = onOpenDiscover)
            } else if (visibleRepos.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No repos match \"${state.searchQuery}\"", color = StatusClean)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(visibleRepos, key = { it.id }) { repo ->
                        RepoCard(
                            repo = repo, isBusy = state.busyRepoId == repo.id,
                            changeCount = state.changeCounts[repo.id],
                            onTap = { onOpenRepo(repo.id) },
                            onLongPress = { repoPendingDelete = repo },
                            onPull = { vm.pull(repo) }, onPush = { vm.push(repo) },
                            onFetch = { vm.fetch(repo) },
                            onRequestPullForce = { repoPendingPullForce = repo },
                            onRequestPushForce = { repoPendingPushForce = repo },
                            onSetCredential = { repoPendingCredential = repo },
                            // Springy placement animation: repos entering, leaving, or
                            // reordering (new sort mode, freshly-scanned local repo,
                            // deletion) settle into place with a little bounce instead
                            // of snapping, and fade in/out rather than popping.
                            itemModifier = Modifier.animateItem(
                                fadeInSpec = tween(220),
                                placementSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                                fadeOutSpec = tween(150),
                            ),
                        )
                    }
                }
            }
        }
        }
    }

    repoPendingDelete?.let { repo ->
        AlertDialog(onDismissRequest = { repoPendingDelete = null },
            title = { Text("Remove ${repo.name}?") },
            text = { Text("Remove it from Repo Master. You can also delete the local files.") },
            confirmButton = { TextButton(onClick = { vm.deleteRepo(repo, true); repoPendingDelete = null }) { Text("Remove + Delete Files", color = StatusDeleted) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.deleteRepo(repo, false); repoPendingDelete = null }) { Text("Just Remove") }
                    if (repo.cloneUrl.contains("github.com")) {
                        TextButton(onClick = { repoPendingDelete = null; repoPendingGitHubDelete = repo }) {
                            Text("Delete on GitHub…", color = StatusDeleted)
                        }
                    }
                    TextButton(onClick = { repoPendingDelete = null }) { Text("Cancel") }
                }
            })
    }

    repoPendingGitHubDelete?.let { repo ->
        com.willykez.repomaster.ui.screens.changes.ConfirmDialog(
            "Delete ${repo.name} on GitHub?",
            "This permanently deletes the repo from GitHub itself — not just from this app. " +
                "There's no undo. The local files stay put; remove those separately if you want them gone too. " +
                "Needs a credential whose token has the \"delete_repo\" scope.",
            confirmLabel = "Delete on GitHub", danger = true,
            onConfirm = { vm.deleteRepoOnGitHub(repo, alsoDeleteFilesLocally = false); repoPendingGitHubDelete = null },
            onDismiss = { repoPendingGitHubDelete = null },
        )
    }

    repoPendingPullForce?.let { repo ->
        com.willykez.repomaster.ui.screens.changes.ConfirmDialog(
            "Pull (Force) ${repo.name}?",
            "This hard-resets the branch to match the remote. Any local commits not on the remote will be lost. Cannot be undone.",
            confirmLabel = "Force Pull", danger = true,
            onConfirm = { vm.pullForce(repo); repoPendingPullForce = null },
            onDismiss = { repoPendingPullForce = null },
        )
    }
    repoPendingPushForce?.let { repo ->
        com.willykez.repomaster.ui.screens.changes.ConfirmDialog(
            "Push (Force) ${repo.name}?",
            "This overwrites the remote branch with local history. If someone else pushed in the meantime, their commits will be lost.",
            confirmLabel = "Force Push", danger = true,
            onConfirm = { vm.pushForce(repo); repoPendingPushForce = null },
            onDismiss = { repoPendingPushForce = null },
        )
    }
    repoPendingCredential?.let { repo ->
        AlertDialog(
            onDismissRequest = { repoPendingCredential = null },
            title = { Text("Credential for ${repo.name}") },
            text = {
                if (state.credentials.isEmpty()) {
                    Text("You don't have any saved credentials yet. Add one on the Credentials screen, then come back here to attach it.")
                } else {
                    Column {
                        Text(
                            "Used for push/pull/fetch on this repo, and for its Actions runs if it's on GitHub.",
                            style = MaterialTheme.typography.bodySmall, color = StatusClean,
                        )
                        Spacer(Modifier.height(8.dp))
                        state.credentials.forEach { cred ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { vm.setCredential(repo, cred.id); repoPendingCredential = null }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = repo.credentialId == cred.id, onClick = { vm.setCredential(repo, cred.id); repoPendingCredential = null })
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(cred.name, style = MaterialTheme.typography.bodyMedium)
                                    if (cred.username.isNotBlank()) {
                                        Text(cred.username, style = MaterialTheme.typography.labelSmall, color = StatusClean)
                                    }
                                }
                            }
                        }
                        if (repo.credentialId != 0L) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { vm.setCredential(repo, 0L); repoPendingCredential = null }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = false, onClick = { vm.setCredential(repo, 0L); repoPendingCredential = null })
                                Spacer(Modifier.width(8.dp))
                                Text("None", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (state.credentials.isEmpty()) {
                    TextButton(onClick = { repoPendingCredential = null; onOpenCredentials() }) { Text("Go to Credentials") }
                } else {
                    TextButton(onClick = { repoPendingCredential = null }) { Text("Done") }
                }
            },
            dismissButton = { TextButton(onClick = { repoPendingCredential = null }) { Text("Cancel") } },
        )
    }

    // Clone lives here as a bottom sheet, not a nav destination — adding a
    // repo never navigates away from the home screen.
    if (showCloneSheet) {
        CloneScreen(
            onDismiss = { showCloneSheet = false },
            onCloned = { showCloneSheet = false },
            onAddCredential = onOpenCredentials,
        )
    }
}

/**
 * Every way to add a repo, in one consistent place — replaces what used to be split across
 * a top-bar "+" icon (Clone) and a separate "..." overflow menu (Scan/Discover) mixed in
 * with unrelated navigation items (Credentials/Settings). A small vertical speed-dial: tap
 * the main FAB to reveal three labeled options, tap one to act (and auto-collapse), tap the
 * main FAB again (now an ✕) or tap anywhere else on the screen to dismiss without picking one.
 */
@Composable
private fun RepoListFab(
    expanded: Boolean, onToggle: () -> Unit,
    onCloneUrl: () -> Unit, onDiscoverGitHub: () -> Unit, onScanLocal: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FabMenuOption("Discover on GitHub", Icons.Filled.Explore, onDiscoverGitHub)
                FabMenuOption("Clone by URL", Icons.Filled.Link, onCloneUrl)
                FabMenuOption("Scan for local repos", Icons.Filled.FolderSpecial, onScanLocal)
                Spacer(Modifier.height(4.dp))
            }
        }
        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) {
            val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "fabRotation")
            Icon(Icons.Filled.Add, if (expanded) "Close" else "Add repo", modifier = Modifier.graphicsLayer { rotationZ = rotation })
        }
    }
}

@Composable
private fun FabMenuOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            tonalElevation = 3.dp, shadowElevation = 2.dp,
            shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable(onClick = onClick),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) { Icon(icon, label, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun StorageAccessBanner(onGrant: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.15f)),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FolderOpen, null, tint = Amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Grant storage access", style = MaterialTheme.typography.labelLarge)
                Text(
                    "So repos save to a public folder you can browse from any file manager, not a hidden app folder.",
                    style = MaterialTheme.typography.bodySmall, color = StatusClean,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAddRepo: () -> Unit, onDiscover: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.FolderOff, null, Modifier.size(56.dp), tint = StatusClean)
        Spacer(Modifier.height(16.dp))
        Text("No repositories", style = MaterialTheme.typography.titleMedium)
        Text("Clone one to get started", style = MaterialTheme.typography.bodyMedium, color = StatusClean)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDiscover) {
                Icon(Icons.Filled.Explore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Browse GitHub")
            }
            Button(onClick = onAddRepo) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Clone URL") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoCard(
    repo: RepoEntity, isBusy: Boolean, changeCount: Int?,
    onTap: () -> Unit, onLongPress: () -> Unit,
    onPull: () -> Unit, onPush: () -> Unit,
    onFetch: () -> Unit, onRequestPullForce: () -> Unit, onRequestPushForce: () -> Unit,
    onSetCredential: () -> Unit,
    itemModifier: Modifier = Modifier,
) {
    var showMore by remember { mutableStateOf(false) }
    val hasError = repo.lastError.isNotBlank()
    val hasCredential = repo.credentialId != 0L
    // The exact wording JGit throws for a private/auth-needed remote with no credential
    // attached — recognizing it means the error banner can offer the actual fix (attach a
    // credential) instead of just showing raw exception text someone has to go interpret.
    val needsCredential = hasError && repo.lastError.contains("no CredentialsProvider", ignoreCase = true)
    val accent = when {
        hasError -> StatusDeleted
        changeCount != null && changeCount > 0 -> Amber
        else -> CommandBlue
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "repoCardScale",
    )

    GlassCard(
        modifier = itemModifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        accent = accent,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgedBox(badge = {
                    if (changeCount != null && changeCount > 0) {
                        Badge(containerColor = Amber) { Text("$changeCount") }
                    }
                }) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(20.dp), tint = CommandBlue)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(repo.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val branch = repo.branch.ifBlank { "—" }
                        Text(branch, style = MaterialTheme.typography.labelSmall, color = Amber)
                        val sync = if (repo.lastSyncTime > 0)
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(repo.lastSyncTime))
                        else "never synced"
                        Text("· $sync", style = MaterialTheme.typography.labelSmall, color = StatusClean)
                        if (!hasCredential) {
                            Text("· no credential", style = MaterialTheme.typography.labelSmall, color = StatusClean)
                        }
                    }
                }
                Box {
                    IconButton(onClick = { showMore = true }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, null, Modifier.size(18.dp), tint = StatusClean)
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(text = { Text("Fetch") }, onClick = { showMore = false; onFetch() },
                            leadingIcon = { Icon(Icons.Filled.Download, null) })
                        DropdownMenuItem(text = { Text("Pull (Force)") }, onClick = { showMore = false; onRequestPullForce() },
                            leadingIcon = { Icon(Icons.Filled.Warning, null, tint = StatusDeleted) })
                        DropdownMenuItem(text = { Text("Push (Force)") }, onClick = { showMore = false; onRequestPushForce() },
                            leadingIcon = { Icon(Icons.Filled.Warning, null, tint = Amber) })
                        DropdownMenuItem(
                            text = { Text(if (hasCredential) "Change credential…" else "Set credential…") },
                            onClick = { showMore = false; onSetCredential() },
                            leadingIcon = { Icon(Icons.Filled.Key, null) },
                        )
                    }
                }
            }

            if (hasError) {
                Spacer(Modifier.height(6.dp))
                Text(repo.lastError, style = MaterialTheme.typography.labelSmall, color = StatusDeleted, maxLines = 2)
                if (needsCredential) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onSetCredential,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(Icons.Filled.Key, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Attach a credential", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onPull, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    if (isBusy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else { Icon(Icons.Filled.ArrowDownward, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Pull") }
                }
                Button(onClick = onPush, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    if (isBusy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else { Icon(Icons.Filled.ArrowUpward, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Push") }
                }
            }
        }
    }
}
