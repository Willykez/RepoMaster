package com.willykez.repomaster.ui.screens.editor

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.App
import com.willykez.repomaster.data.db.entity.RepoEntity
import com.willykez.repomaster.git.GitEngine
import com.willykez.repomaster.git.GitResult
import com.willykez.repomaster.ui.theme.CommandBlue
import com.willykez.repomaster.ui.theme.StatusClean
import com.willykez.repomaster.ui.theme.StatusDeleted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Files bigger than this open in a "too large to edit" notice instead of loading into the text field. */
private const val MAX_EDITABLE_BYTES = 2 * 1024 * 1024 // 2 MB

data class EditorUiState(
    val repo: RepoEntity? = null,
    val relativePath: String = "",
    val text: TextFieldValue = TextFieldValue(""),
    val isLoading: Boolean = true,
    val isBinaryOrTooLarge: Boolean = false,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val showPreview: Boolean = false,
)

class FileEditorViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Undo/redo history. Kept outside EditorUiState (not just the two booleans
    // that are) since these hold the actual snapshots, which are throwaway
    // playback data rather than state the UI renders directly.
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastEditAtMs = 0L
    private val maxHistory = 100
    /** Edits within this window of each other are treated as one continuous typing
     *  burst and coalesced into a single undo step — otherwise every keystroke would
     *  push its own entry and undo would feel like "delete one character" instead of
     *  "undo what I just typed." */
    private val coalesceWindowMs = 700L

    fun load(repoId: Long, relativePath: String) {
        undoStack.clear()
        redoStack.clear()
        lastEditAtMs = 0L
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            _uiState.value = _uiState.value.copy(repo = repo, relativePath = relativePath, isLoading = true)

            val file = File(repo.fullSavePath, relativePath)
            val result = withContext(Dispatchers.IO) {
                if (!file.exists() || file.length() > MAX_EDITABLE_BYTES) return@withContext null
                val bytes = file.readBytes()
                // Crude but effective binary check: a NUL byte essentially never
                // shows up in real text files, but is common in binary formats.
                if (bytes.contains(0)) return@withContext null
                bytes.toString(Charsets.UTF_8)
            }

            if (result == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, isBinaryOrTooLarge = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    text = TextFieldValue(result),
                    isLoading = false,
                    isBinaryOrTooLarge = false,
                )
            }
        }
    }

    fun onTextChanged(newValue: TextFieldValue) {
        val current = _uiState.value.text
        if (newValue.text != current.text) {
            val now = System.currentTimeMillis()
            if (now - lastEditAtMs > coalesceWindowMs) {
                undoStack.addLast(current)
                if (undoStack.size > maxHistory) undoStack.removeFirst()
                redoStack.clear()
            }
            lastEditAtMs = now
            _uiState.value = _uiState.value.copy(
                text = newValue, isDirty = true,
                canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
            )
        } else {
            // Selection/cursor-only change (e.g. tapping around, or "go to line") —
            // not an edit, so it shouldn't touch undo history.
            _uiState.value = _uiState.value.copy(text = newValue)
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_uiState.value.text)
        lastEditAtMs = 0L // next keystroke after an undo starts a fresh checkpoint
        _uiState.value = _uiState.value.copy(
            text = prev, isDirty = true,
            canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
        )
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_uiState.value.text)
        lastEditAtMs = 0L
        _uiState.value = _uiState.value.copy(
            text = next, isDirty = true,
            canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty(),
        )
    }

    /** Moves the cursor without recording an undo step — used by "Go to line." */
    fun moveCursorTo(offset: Int) {
        val current = _uiState.value.text
        _uiState.value = _uiState.value.copy(text = current.copy(selection = TextRange(offset, offset)))
    }

    fun togglePreview() {
        _uiState.value = _uiState.value.copy(showPreview = !_uiState.value.showPreview)
    }

    fun selectAll() {
        val current = _uiState.value.text
        _uiState.value = _uiState.value.copy(
            text = current.copy(selection = TextRange(0, current.text.length))
        )
    }

    fun save() {
        val repo = _uiState.value.repo ?: return
        val relativePath = _uiState.value.relativePath
        val content = _uiState.value.text.text

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            withContext(Dispatchers.IO) {
                File(repo.fullSavePath, relativePath).writeText(content)
            }
            _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved")
        }
    }

    /** Save, then stage + commit + push this one file — the quick path from "edited a file" to "pushed it". */
    fun saveCommitAndPush(commitMessage: String, authorName: String, authorEmail: String) {
        val repo = _uiState.value.repo ?: return
        val relativePath = _uiState.value.relativePath
        val content = _uiState.value.text.text

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            withContext(Dispatchers.IO) {
                File(repo.fullSavePath, relativePath).writeText(content)
            }

            when (val openResult = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> {
                    _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved, but couldn't open repo: ${openResult.message}")
                }
                is GitResult.Success -> {
                    val git = openResult.data
                    val stageResult = GitEngine.stageFile(git, relativePath)
                    if (stageResult is GitResult.Error) {
                        _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved, but staging failed: ${stageResult.message}")
                        git.close()
                        return@launch
                    }

                    val commitResult = GitEngine.commit(git, commitMessage, authorName, authorEmail)
                    if (commitResult is GitResult.Error) {
                        _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved + staged, but commit failed: ${commitResult.message}")
                        git.close()
                        return@launch
                    }

                    val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
                    when (val pushResult = GitEngine.push(git, credential = credential)) {
                        is GitResult.Error -> {
                            repoRepo.markError(repo.id, pushResult.message)
                            _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Committed, but push failed: ${pushResult.message}")
                        }
                        is GitResult.Success -> {
                            repoRepo.markSyncSuccess(repo.id)
                            _uiState.value = _uiState.value.copy(isSaving = false, isDirty = false, message = "Saved, committed, and pushed")
                        }
                    }
                    git.close()
                }
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
    repoId: Long,
    relativePath: String,
    onBack: () -> Unit,
    initialLine: Int? = null,
    vm: FileEditorViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }
    var showPushDialog by remember { mutableStateOf(false) }
    var showGoToLine by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    val language = remember(relativePath) { languageForPath(relativePath) }
    val isMarkdown = language == CodeLanguage.MARKDOWN
    val editorScrollState = rememberScrollState()
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    // Captured here (composable scope) rather than read inside the LaunchedEffect blocks
    // below, since currentEditorLineHeight() is itself @Composable and those blocks aren't —
    // this keeps the jump-to-line math in sync with whatever text size Settings has set.
    val lineHeight = currentEditorLineHeight()

    LaunchedEffect(repoId, relativePath) { vm.load(repoId, relativePath) }
    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); vm.dismissMessage() }
    }

    // Arriving from Search's "jump straight to the matched line" — fires once the file
    // has actually finished loading into the text field, so there's real content/line
    // count to scroll against. Guarded on isBinaryOrTooLarge too since a line jump into
    // a file that couldn't be opened as text wouldn't mean anything.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && !state.isBinaryOrTooLarge && initialLine != null && initialLine >= 1) {
            val offset = offsetForLineCol(state.text.text, initialLine, 1)
            vm.moveCursorTo(offset)
            val targetPx = with(density) { (lineHeight.toPx() * (initialLine - 1) - 80).coerceAtLeast(0f) }
            editorScrollState.animateScrollTo(targetPx.toInt())
        }
    }

    val (currentLine, currentCol) = remember(state.text.selection.start, state.text.text) {
        lineColForOffset(state.text.text, state.text.selection.start)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(relativePath.substringAfterLast('/'), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (!state.isBinaryOrTooLarge && !state.isLoading) {
                                Text(
                                    "${languageLabel(language)} · Ln $currentLine, Col $currentCol",
                                    style = MaterialTheme.typography.labelSmall, color = StatusClean,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (!state.isBinaryOrTooLarge && !state.isLoading) {
                            IconButton(onClick = vm::undo, enabled = state.canUndo) {
                                Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                            }
                            IconButton(onClick = vm::redo, enabled = state.canRedo) {
                                Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                            }
                            if (isMarkdown) {
                                IconButton(onClick = vm::togglePreview) {
                                    Icon(
                                        if (state.showPreview) Icons.Filled.Edit else Icons.Filled.Visibility,
                                        if (state.showPreview) "Edit" else "Preview",
                                        tint = if (state.showPreview) CommandBlue else LocalContentColor.current,
                                    )
                                }
                            }
                            IconButton(onClick = vm::save, enabled = state.isDirty && !state.isSaving) {
                                if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Filled.Save, "Save")
                            }
                            IconButton(onClick = { showPushDialog = true }, enabled = !state.isSaving) {
                                Icon(Icons.Filled.ArrowUpward, "Save, commit & push")
                            }
                            Box {
                                IconButton(onClick = { showOverflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                                DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Go to line…") },
                                        leadingIcon = { Icon(Icons.Filled.MyLocation, null) },
                                        onClick = { showOverflow = false; showGoToLine = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Select all") },
                                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                        onClick = { showOverflow = false; vm.selectAll() },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snack) { d -> Snackbar(d) } },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
                state.isBinaryOrTooLarge -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = StatusClean, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Can't open this file here", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "It's either a binary file or larger than 2 MB — this built-in editor is for text/source files.",
                        style = MaterialTheme.typography.bodyMedium, color = StatusClean,
                    )
                }
                isMarkdown && state.showPreview -> MarkdownPreview(state.text.text, Modifier.fillMaxSize())
                else -> CodeEditorField(
                    value = state.text,
                    onValueChange = vm::onTextChanged,
                    language = language,
                    verticalScrollState = editorScrollState,
                    modifier = Modifier.fillMaxSize().imePadding(),
                )
            }
        }
    }

    if (showGoToLine) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGoToLine = false },
            title = { Text("Go to line") },
            text = {
                Column {
                    Text(
                        "Line, or line:column — e.g. \"156\" or \"156:13\"",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { c -> c.isDigit() || c == ':' } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parts = input.split(":")
                    val line = parts.getOrNull(0)?.toIntOrNull()
                    val col = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    if (line != null && line >= 1) {
                        val offset = offsetForLineCol(state.text.text, line, col)
                        vm.moveCursorTo(offset)
                        coroutineScope.launch {
                            val targetPx = with(density) { (lineHeight.toPx() * (line - 1) - 80).coerceAtLeast(0f) }
                            editorScrollState.animateScrollTo(targetPx.toInt())
                        }
                    }
                    showGoToLine = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showGoToLine = false }) { Text("Cancel") } },
        )
    }

    if (showPushDialog) {
        var commitMessage by remember { mutableStateOf("Update ${relativePath.substringAfterLast('/')}") }
        AlertDialog(
            onDismissRequest = { showPushDialog = false },
            title = { Text("Save, commit & push") },
            text = {
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Commit message") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPushDialog = false
                    vm.saveCommitAndPush(commitMessage, "Repo Master User", "repomaster@users.noreply.github.com")
                }) { Text("Push") }
            },
            dismissButton = { TextButton(onClick = { showPushDialog = false }) { Text("Cancel") } },
        )
    }
}
