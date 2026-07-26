package com.willykez.repomaster.ui.screens.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.data.github.GitHubRepoSummary
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(onBack: () -> Unit, vm: DiscoverViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    var credentialMenuExpanded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New repo") },
                containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {

            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChanged,
                placeholder = { Text("Search GitHub repos…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    IconButton(onClick = vm::search) { Icon(Icons.Filled.Send, "Search") }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.search() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::loadMyRepos, enabled = state.credentials.isNotEmpty()) {
                    Icon(Icons.Filled.Person, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("My Repos")
                }

                Spacer(Modifier.weight(1f))

                if (state.credentials.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { credentialMenuExpanded = true }) {
                            Icon(Icons.Filled.Key, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                state.credentials.firstOrNull { it.id == state.selectedCredentialId }?.name
                                    ?: "No credential",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        DropdownMenu(expanded = credentialMenuExpanded, onDismissRequest = { credentialMenuExpanded = false }) {
                            state.credentials.forEach { cred ->
                                DropdownMenuItem(
                                    text = { Text(cred.name) },
                                    onClick = { vm.onCredentialSelected(cred.id); credentialMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (state.credentials.isEmpty()) {
                Text(
                    "Tip: add a credential first (Credentials screen) to search with fewer rate limits, list your own repos, and clone private ones.",
                    style = MaterialTheme.typography.bodySmall, color = StatusClean,
                )
                Spacer(Modifier.height(8.dp))
            }

            when {
                state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                !state.hasSearched -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Explore, null, Modifier.size(48.dp), tint = StatusClean)
                        Spacer(Modifier.height(12.dp))
                        Text("Search GitHub or load your own repos", style = MaterialTheme.typography.bodyMedium, color = StatusClean)
                    }
                }
                state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No repos found", color = StatusClean)
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(state.results, key = { it.fullName }) { repo ->
                        DiscoverRepoCard(
                            repo = repo,
                            isCloning = state.cloningFullName == repo.fullName,
                            isDeleting = state.deletingFullName == repo.fullName,
                            isMine = state.showingMine,
                            onClone = { vm.cloneRepo(repo) },
                            onDelete = { vm.deleteRepoOnGithub(repo) },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateRepoDialog(
            isCreating = state.isCreating,
            hasCredential = state.credentials.isNotEmpty(),
            onDismiss = { showCreateDialog = false },
            onCreate = { name, description, private, autoClone ->
                vm.createRepo(name, description, private, autoClone)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun CreateRepoDialog(
    isCreating: Boolean,
    hasCredential: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, private: Boolean, autoClone: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }
    var autoClone by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New repo on GitHub") },
        text = {
            Column {
                if (!hasCredential) {
                    Text(
                        "Add a credential with a GitHub token first (Credentials screen) — creating a repo needs one.",
                        style = MaterialTheme.typography.bodySmall, color = StatusDeleted,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Repo name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Private repo", modifier = Modifier.weight(1f))
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Clone locally after creating", modifier = Modifier.weight(1f))
                    Switch(checked = autoClone, onCheckedChange = { autoClone = it })
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (autoClone) "Creates it on GitHub and clones it here right away."
                    else "Only creates it on GitHub — nothing is cloned to this device.",
                    style = MaterialTheme.typography.bodySmall, color = StatusClean,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim(), description.trim(), isPrivate, autoClone) },
                enabled = !isCreating && hasCredential && name.isNotBlank(),
            ) {
                if (isCreating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Creating…")
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isCreating) { Text("Cancel") } },
    )
}

@Composable
private fun DiscoverRepoCard(
    repo: GitHubRepoSummary,
    isCloning: Boolean,
    isDeleting: Boolean,
    isMine: Boolean,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    GlassCard(
        Modifier.fillMaxWidth(),
        accent = if (repo.private) Amber else null,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (repo.private) Icons.Filled.Lock else Icons.Filled.Public,
                    null, Modifier.size(16.dp), tint = if (repo.private) Amber else StatusClean,
                )
                Spacer(Modifier.width(6.dp))
                Text(repo.fullName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = Amber)
                Spacer(Modifier.width(2.dp))
                Text("${repo.stars}", style = MaterialTheme.typography.labelSmall, color = StatusClean)
            }
            if (!repo.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(repo.description, style = MaterialTheme.typography.bodySmall, color = StatusClean, maxLines = 2)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClone, enabled = !isCloning && !isDeleting, modifier = Modifier.weight(1f)) {
                    if (isCloning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Cloning…")
                    } else {
                        Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clone")
                    }
                }
                if (isMine) {
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = !isCloning && !isDeleting,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusDeleted),
                    ) {
                        if (isDeleting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = StatusDeleted)
                        } else {
                            Icon(Icons.Filled.DeleteOutline, "Delete from GitHub", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${repo.fullName}?") },
            text = {
                Text(
                    "This permanently deletes the repo from GitHub — issues, PRs, releases, everything. " +
                        "It only affects GitHub: if you have this repo cloned on this device, that local " +
                        "clone is untouched and keeps working exactly as before.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusDeleted),
                ) { Text("Delete from GitHub") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}
