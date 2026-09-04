package com.willykez.repomaster.ui.screens.console

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.App
import com.willykez.repomaster.data.GitIdentityPrefs
import com.willykez.repomaster.git.GitConsoleInterpreter
import com.willykez.repomaster.git.GitEngine
import com.willykez.repomaster.git.GitResult
import com.willykez.repomaster.ui.theme.CommandBlue
import com.willykez.repomaster.ui.theme.Emerald
import com.willykez.repomaster.ui.theme.StatusClean
import com.willykez.repomaster.ui.theme.StatusDeleted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConsoleLine(val command: String, val output: String, val isError: Boolean)

data class ConsoleUiState(
    val repoName: String = "",
    val history: List<ConsoleLine> = emptyList(),
    val commandHistory: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val pendingDangerCommand: String? = null,
    val pendingDangerWarning: String? = null,
)

/**
 * Runs typed git commands against this repo via [GitConsoleInterpreter] — see that file for
 * exactly what's supported and why a handful of commands aren't (there's no bundled `git`
 * binary here, this dispatches onto the same [GitEngine] every other screen already uses).
 */
class GitConsoleViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()

    private var repoId: Long = -1

    fun load(id: Long) {
        repoId = id
        viewModelScope.launch {
            val repo = repoRepo.getById(id) ?: return@launch
            _state.value = _state.value.copy(repoName = repo.name)
        }
    }

    /** First pass: checked before actually running anything, so the screen can show a
     *  confirmation prompt for the small set of genuinely destructive commands. */
    fun submit(rawCommand: String) {
        if (rawCommand.isBlank()) return
        val warning = GitConsoleInterpreter.dangerWarning(rawCommand)
        if (warning != null) {
            _state.value = _state.value.copy(pendingDangerCommand = rawCommand, pendingDangerWarning = warning)
        } else {
            execute(rawCommand)
        }
    }

    fun confirmPendingDanger() {
        val cmd = _state.value.pendingDangerCommand ?: return
        _state.value = _state.value.copy(pendingDangerCommand = null, pendingDangerWarning = null)
        execute(cmd)
    }

    fun dismissPendingDanger() {
        _state.value = _state.value.copy(pendingDangerCommand = null, pendingDangerWarning = null)
    }

    private fun execute(rawCommand: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRunning = true, commandHistory = _state.value.commandHistory + rawCommand)
            val repo = repoRepo.getById(repoId)
            if (repo == null) {
                appendLine(rawCommand, "error: repo not found", true)
                _state.value = _state.value.copy(isRunning = false)
                return@launch
            }
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> appendLine(rawCommand, "error: ${opened.message}", true)
                is GitResult.Success -> {
                    val git = opened.data
                    val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
                    val name = GitIdentityPrefs.currentName(appRef)
                    val email = GitIdentityPrefs.currentEmail(appRef)
                    val output = GitConsoleInterpreter.run(git, credential, name, email, rawCommand)
                    git.close()
                    appendLine(rawCommand, output, output.startsWith("error") || output.startsWith("fatal"))
                }
            }
            _state.value = _state.value.copy(isRunning = false)
        }
    }

    private fun appendLine(command: String, output: String, isError: Boolean) {
        _state.value = _state.value.copy(history = _state.value.history + ConsoleLine(command, output, isError))
    }

    fun clear() {
        _state.value = _state.value.copy(history = emptyList())
    }
}

private val EXAMPLE_COMMANDS = listOf(
    "status", "diff", "diff --cached", "log --oneline -10", "branch -a",
    "add .", "commit -m \"message\"", "push", "pull", "fetch",
    "stash", "stash list", "stash pop", "tag", "remote -v",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitConsoleScreen(repoId: Long, onBack: () -> Unit, vm: GitConsoleViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) }
    var showHelp by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.history.size) {
        if (state.history.isNotEmpty()) scope.launch { listState.animateScrollToItem(state.history.size - 1) }
    }

    fun submit() {
        if (input.isBlank()) return
        vm.submit(input)
        input = ""
        historyIndex = -1
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Console \u2014 ${state.repoName}", fontWeight = FontWeight.SemiBold, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Filled.HelpOutline, "What's supported") }
                    IconButton(onClick = vm::clear, enabled = state.history.isNotEmpty()) { Icon(Icons.Filled.Delete, "Clear") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(androidx.compose.ui.graphics.Color.Black)) {
            if (state.history.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Type a git command below \u2014 same syntax as the real thing, run against this repo via the same engine every other screen uses.",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean, fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Try:", style = MaterialTheme.typography.labelSmall, color = StatusClean)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            EXAMPLE_COMMANDS.take(4).forEach { ExampleChip(it) { input = it } }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.history) { line -> ConsoleLineView(line) }
                    if (state.isRunning) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Emerald)
                                Spacer(Modifier.width(8.dp))
                                Text("running\u2026", fontFamily = FontFamily.Monospace, color = StatusClean, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = StatusClean.copy(alpha = 0.2f))
            Row(
                Modifier.fillMaxWidth().imePadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (state.commandHistory.isEmpty()) return@IconButton
                        val newIndex = (historyIndex + 1).coerceAtMost(state.commandHistory.size - 1)
                        historyIndex = newIndex
                        input = state.commandHistory[state.commandHistory.size - 1 - newIndex]
                    },
                    enabled = state.commandHistory.isNotEmpty(),
                ) { Icon(Icons.Filled.KeyboardArrowUp, "Previous command", tint = StatusClean) }

                Text("$", fontFamily = FontFamily.Monospace, color = Emerald, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("git status", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF0D0D0D),
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color(0xFF0D0D0D),
                    ),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { submit() }, enabled = input.isNotBlank() && !state.isRunning) {
                    Icon(Icons.Filled.Send, "Run", tint = CommandBlue)
                }
            }
        }
    }

    state.pendingDangerCommand?.let { cmd ->
        AlertDialog(
            onDismissRequest = vm::dismissPendingDanger,
            title = { Text("Run \"$cmd\"?") },
            text = { Text(state.pendingDangerWarning ?: "") },
            confirmButton = { TextButton(onClick = vm::confirmPendingDanger) { Text("Run anyway", color = StatusDeleted) } },
            dismissButton = { TextButton(onClick = vm::dismissPendingDanger) { Text("Cancel") } },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("What's supported") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "This runs commands against the same engine every other screen in the app uses \u2014 there's no real `git` binary bundled, so this covers the common day-to-day subset, not every possible flag.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Supported: status, diff, log, branch, add, restore, rm, mv, commit, push, pull, fetch, switch, checkout, merge, rebase, stash, reset, tag, remote, clean.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Not supported: config, clone, init, reflog, ls-files, cherry-pick, bisect, submodule. blame opens the File Explorer's own Blame view instead \u2014 it's a richer view than plain text output.",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "reset --hard, clean -f/-fd, push --force, and branch -D always ask for confirmation first.",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } },
        )
    }
}

@Composable
private fun ExampleChip(command: String, onClick: () -> Unit) {
    Surface(
        color = androidx.compose.ui.graphics.Color(0xFF161616),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            "$ $command", fontFamily = FontFamily.Monospace, color = Emerald,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ConsoleLineView(line: ConsoleLine) {
    Column {
        Row {
            Text("$ ", fontFamily = FontFamily.Monospace, color = Emerald, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(line.command, fontFamily = FontFamily.Monospace, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        if (line.output.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                line.output,
                fontFamily = FontFamily.Monospace,
                color = if (line.isError) StatusDeleted else StatusClean,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
