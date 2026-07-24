package com.willykez.repomaster.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.theme.Amber
import com.willykez.repomaster.ui.theme.CommandBlue
import com.willykez.repomaster.ui.theme.StatusClean

/**
 * Repo-wide full-text search — deliberately its own screen rather than an extension of the
 * editor's find-in-file. Find-in-file only ever looks inside one already-open buffer; this
 * walks every text file in the working copy on disk and answers "which file has this
 * string in it at all," which is a different question with a different UI (a results list
 * across files, not a highlight-and-next inside one file).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(repoId: Long, onBack: () -> Unit, onOpenResult: (repoId: Long, path: String, line: Int) -> Unit, vm: SearchViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismissMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.scopeAllRepos) "Search all repos" else "Search ${state.repo?.name.orEmpty()}",
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChanged,
                placeholder = { Text(if (state.scopeAllRepos) "Search across every repo…" else "Search across every file…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChanged("") }) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { vm.onCaseSensitiveChanged(!state.caseSensitive) },
                ) {
                    Checkbox(checked = state.caseSensitive, onCheckedChange = vm::onCaseSensitiveChanged)
                    Text("Case sensitive", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = vm::search, enabled = state.query.isNotBlank() && !state.isSearching) {
                    Text("Search")
                }
            }

            // Only worth showing if there's actually more than one repo to widen into — a
            // toggle that always does the same thing as leaving it off is just clutter.
            if (state.allRepos.size > 1) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { vm.onScopeAllReposChanged(!state.scopeAllRepos) },
                ) {
                    Checkbox(checked = state.scopeAllRepos, onCheckedChange = vm::onScopeAllReposChanged)
                    Text("Search all ${state.allRepos.size} repos", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.truncated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Showing the first $MAX_RESULTS_LABEL matches — narrow the query for a complete result set.",
                    style = MaterialTheme.typography.labelSmall, color = Amber,
                )
            }

            Spacer(Modifier.height(10.dp))

            when {
                state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !state.hasSearched -> EmptyState(Icons.Filled.Search, if (state.scopeAllRepos) "Search every tracked repo by content" else "Search this repo's files by content")
                state.results.isEmpty() -> EmptyState(Icons.Filled.SearchOff, "No matches for \"${state.query}\"")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.results, key = { "${it.repoId}:${it.relativePath}:${it.lineNumber}:${it.matchStart}" }) { match ->
                        ResultRow(
                            match = match,
                            showRepoName = state.scopeAllRepos,
                            onClick = { onOpenResult(match.repoId, match.relativePath, match.lineNumber) },
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_RESULTS_LABEL = "300"

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(48.dp), tint = StatusClean)
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = StatusClean, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ResultRow(match: SearchMatch, showRepoName: Boolean, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onClick), accent = CommandBlue) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(14.dp), tint = StatusClean)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showRepoName) "${match.repoName}/${match.relativePath}" else match.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusClean,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Ln ${match.lineNumber}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildHighlightedSnippet(match),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
    }
}

/** Bolds and tints just the matched span within the line — the same "signal at a glance"
 *  purpose as everywhere else in the app, applied to text instead of a card accent. */
@Composable
private fun buildHighlightedSnippet(match: SearchMatch) = buildAnnotatedString {
    val text = match.lineText
    val start = match.matchStart.coerceIn(0, text.length)
    val end = match.matchEnd.coerceIn(start, text.length)
    append(text.substring(0, start))
    withStyle(SpanStyle(color = CommandBlue, fontWeight = FontWeight.Bold)) {
        append(text.substring(start, end))
    }
    append(text.substring(end))
}
