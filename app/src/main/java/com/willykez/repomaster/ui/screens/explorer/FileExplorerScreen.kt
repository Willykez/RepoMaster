package com.willykez.repomaster.ui.screens.explorer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.App
import com.willykez.repomaster.data.ExplorerPrefs
import com.willykez.repomaster.data.ExplorerSortMode
import com.willykez.repomaster.data.db.entity.RepoEntity
import com.willykez.repomaster.git.FileNode
import com.willykez.repomaster.git.GitEngine
import com.willykez.repomaster.git.GitFileStatus
import com.willykez.repomaster.git.GitResult
import com.willykez.repomaster.ui.components.RepoTitleBlock
import com.willykez.repomaster.ui.screens.changes.ConfirmDialog
import com.willykez.repomaster.ui.screens.changes.SingleInputDialog
import com.willykez.repomaster.ui.theme.CommandBlue
import com.willykez.repomaster.ui.theme.Emerald
import com.willykez.repomaster.ui.theme.StatusAdded
import com.willykez.repomaster.ui.theme.StatusClean
import com.willykez.repomaster.ui.theme.StatusDeleted
import com.willykez.repomaster.ui.theme.StatusModified
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One row as actually rendered in the flattened tree. [node]'s `relativePath` is always the
 *  real on-disk path to operate on (stage/delete/rename/expand); `node.name` may be a
 *  slash-joined chain like "app/src/main" when [ExplorerPrefs.compactFolders] merged a run of
 *  single-child folders into one row — see [FileExplorerViewModel.listChildren]. */
data class TreeRow(
    val node: FileNode,
    val depth: Int,
    val isExpanded: Boolean,
    val gitStatus: GitFileStatus?,
    val hasDirtyDescendant: Boolean,
    val isCompactedChain: Boolean,
)

data class ExplorerUiState(
    val repo: RepoEntity? = null,
    val childrenCache: Map<String, List<FileNode>> = emptyMap(), // "" = root
    val expandedPaths: Set<String> = emptySet(),
    val gitStatusByPath: Map<String, GitFileStatus> = emptyMap(),
    val visibleRows: List<TreeRow> = emptyList(),
    val showHiddenFiles: Boolean = false,
    val compactFolders: Boolean = true,
    val sortMode: ExplorerSortMode = ExplorerSortMode.NAME,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val message: String? = null,
)

class FileExplorerViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _uiState = MutableStateFlow(ExplorerUiState())
    val uiState: StateFlow<ExplorerUiState> = _uiState.asStateFlow()

    /** [revealPath] (optional) auto-expands the ancestor chain of a specific file so it's
     *  visible on first load — used by the `repomaster://repo/{id}` deep link. Best-effort:
     *  if compact-folder chains don't line up with the raw path segments, this simply expands
     *  as far as it cleanly can rather than guessing. */
    fun load(repoId: Long, revealPath: String = "") {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            _uiState.value = _uiState.value.copy(
                repo = repo, isLoading = true,
                showHiddenFiles = ExplorerPrefs.showHiddenFiles(appRef),
                compactFolders = ExplorerPrefs.compactFolders(appRef),
                sortMode = ExplorerPrefs.sortMode(appRef),
            )
            refresh()
            if (revealPath.isNotBlank()) revealAncestors(revealPath)
        }
    }

    private fun currentDir(): File? = _uiState.value.repo?.let { File(it.fullSavePath) }

    /** Re-lists the root and every currently-expanded folder, then re-scans git status —
     *  called after any mutation and by pull-to-refresh. Expand state survives a refresh (the
     *  tree doesn't collapse just because something changed), the same way a code editor's
     *  file tree keeps folders open across a background re-index. */
    private suspend fun refresh() {
        val repo = _uiState.value.repo ?: return
        val newCache = mutableMapOf<String, List<FileNode>>()
        newCache[""] = listChildren(repo, "")
        for (path in _uiState.value.expandedPaths) {
            newCache[path] = listChildren(repo, path)
        }
        val statusMap = scanGitStatus(repo)
        _uiState.value = _uiState.value.copy(childrenCache = newCache, gitStatusByPath = statusMap, isLoading = false)
        recomputeVisibleRows()
    }

    fun toggleExpand(node: FileNode) {
        if (!node.isDirectory) return
        val path = node.relativePath
        val expanded = _uiState.value.expandedPaths
        if (path in expanded) {
            _uiState.value = _uiState.value.copy(expandedPaths = expanded - path)
            recomputeVisibleRows()
            return
        }
        viewModelScope.launch {
            val repo = _uiState.value.repo ?: return@launch
            if (path !in _uiState.value.childrenCache) {
                val kids = listChildren(repo, path)
                _uiState.value = _uiState.value.copy(childrenCache = _uiState.value.childrenCache + (path to kids))
            }
            _uiState.value = _uiState.value.copy(expandedPaths = _uiState.value.expandedPaths + path)
            recomputeVisibleRows()
        }
    }

    fun collapseAll() {
        _uiState.value = _uiState.value.copy(expandedPaths = emptySet())
        recomputeVisibleRows()
    }

    private suspend fun revealAncestors(path: String) {
        val repo = _uiState.value.repo ?: return
        val segments = path.split("/").dropLast(1)
        var current = ""
        for (seg in segments) {
            current = if (current.isBlank()) seg else "$current/$seg"
            if (current !in _uiState.value.childrenCache) {
                val kids = listChildren(repo, current)
                _uiState.value = _uiState.value.copy(childrenCache = _uiState.value.childrenCache + (current to kids))
            }
            _uiState.value = _uiState.value.copy(expandedPaths = _uiState.value.expandedPaths + current)
        }
        recomputeVisibleRows()
    }

    /** Lists one directory's immediate children for tree display — hidden-file filtering,
     *  sort order, and single-child folder-chain compaction all happen here, in one place,
     *  so [refresh] and [toggleExpand] can't drift out of sync on how a folder gets listed. */
    private suspend fun listChildren(repo: RepoEntity, path: String): List<FileNode> = withContext(Dispatchers.IO) {
        val showHidden = _uiState.value.showHiddenFiles
        val compact = _uiState.value.compactFolders
        val sortMode = _uiState.value.sortMode

        val dir = if (path.isBlank()) File(repo.fullSavePath) else File(repo.fullSavePath, path)
        val raw = dir.listFiles()?.toList() ?: emptyList()
        val filtered = raw.filter { f ->
            (f.name != ".git" || path.isNotBlank()) && (showHidden || !f.name.startsWith("."))
        }
        val sorted = sortEntries(filtered, sortMode)

        sorted.map { f ->
            val relPath = if (path.isBlank()) f.name else "$path/${f.name}"
            if (f.isDirectory && compact) {
                val (finalPath, chainNames) = compactChain(f, relPath, showHidden)
                FileNode(name = chainNames.joinToString("/"), relativePath = finalPath, isDirectory = true, sizeBytes = 0L)
            } else {
                FileNode(name = f.name, relativePath = relPath, isDirectory = f.isDirectory, sizeBytes = if (f.isFile) f.length() else 0L)
            }
        }
    }

    /** Walks forward through a chain of folders that each contain exactly one subfolder,
     *  merging their names ("app" -> "app/src" -> "app/src/main") until hitting a folder with
     *  zero, one file, or 2+ children — the point where showing them as separate rows again
     *  actually carries information. Same convention as a desktop IDE's "compact folders." */
    private fun compactChain(startDir: File, startRelPath: String, showHidden: Boolean): Pair<String, List<String>> {
        var dir = startDir
        var relPath = startRelPath
        val names = mutableListOf(startDir.name)
        while (true) {
            val kids = dir.listFiles()?.filter { showHidden || !it.name.startsWith(".") } ?: emptyList()
            val onlyChild = kids.singleOrNull() ?: break
            if (!onlyChild.isDirectory) break
            dir = onlyChild
            relPath = "$relPath/${onlyChild.name}"
            names.add(onlyChild.name)
        }
        return relPath to names
    }

    private fun sortEntries(files: List<File>, mode: ExplorerSortMode): List<File> {
        val (dirs, plain) = files.partition { it.isDirectory }
        val comparator: Comparator<File> = when (mode) {
            ExplorerSortMode.NAME -> compareBy { it.name.lowercase() }
            ExplorerSortMode.SIZE -> compareByDescending { it.length() }
            ExplorerSortMode.DATE -> compareByDescending { it.lastModified() }
        }
        // Folders always sort before files regardless of mode — sorting a folder "by size"
        // doesn't mean much (it'd need a recursive size scan), so size/date sorting only
        // really applies within each group.
        return dirs.sortedWith(comparator) + plain.sortedWith(comparator)
    }

    private suspend fun scanGitStatus(repo: RepoEntity): Map<String, GitFileStatus> {
        return when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
            is GitResult.Error -> emptyMap()
            is GitResult.Success -> {
                val git = opened.data
                val map = when (val r = GitEngine.getStatus(git)) {
                    is GitResult.Success -> r.data.associate { it.path to it.status }
                    is GitResult.Error -> emptyMap()
                }
                git.close()
                map
            }
        }
    }

    private fun recomputeVisibleRows() {
        val s = _uiState.value
        val dirtyDirs = mutableSetOf<String>()
        for (p in s.gitStatusByPath.keys) {
            var idx = p.lastIndexOf('/')
            while (idx > 0) {
                dirtyDirs.add(p.substring(0, idx))
                idx = p.lastIndexOf('/', idx - 1)
            }
        }
        val rows = mutableListOf<TreeRow>()
        fun walk(children: List<FileNode>, depth: Int) {
            for (child in children) {
                val isExpanded = child.isDirectory && child.relativePath in s.expandedPaths
                rows += TreeRow(
                    node = child, depth = depth, isExpanded = isExpanded,
                    gitStatus = s.gitStatusByPath[child.relativePath],
                    hasDirtyDescendant = child.relativePath in dirtyDirs,
                    isCompactedChain = child.isDirectory && child.name.contains('/'),
                )
                if (isExpanded) walk(s.childrenCache[child.relativePath] ?: emptyList(), depth + 1)
            }
        }
        walk(s.childrenCache[""] ?: emptyList(), 0)
        _uiState.value = s.copy(visibleRows = rows)
    }

    fun setShowHiddenFiles(value: Boolean) {
        ExplorerPrefs.setShowHiddenFiles(appRef, value)
        _uiState.value = _uiState.value.copy(showHiddenFiles = value)
        viewModelScope.launch { refresh() }
    }

    fun setCompactFolders(value: Boolean) {
        ExplorerPrefs.setCompactFolders(appRef, value)
        _uiState.value = _uiState.value.copy(compactFolders = value)
        viewModelScope.launch { refresh() }
    }

    fun setSortMode(mode: ExplorerSortMode) {
        ExplorerPrefs.setSortMode(appRef, mode)
        _uiState.value = _uiState.value.copy(sortMode = mode)
        viewModelScope.launch { refresh() }
    }

    fun rename(node: FileNode, newName: String) {
        val repo = _uiState.value.repo ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val old = File(repo.fullSavePath, node.relativePath)
                val new = File(old.parentFile, newName)
                old.renameTo(new)
            }
            refresh()
        }
    }

    /** Deletes AND stages the removal in one step via `git rm` — a plain filesystem delete
     * followed by a later "Stage" tap on the Changes screen used to silently do nothing,
     * since JGit's AddCommand only ever adds content, never removes an index entry. This
     * is what was actually breaking "delete a file, push, expect it gone on GitHub." */
    fun delete(node: FileNode) {
        val repo = _uiState.value.repo ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _uiState.value = _uiState.value.copy(isBusy = false, message = opened.message)
                is GitResult.Success -> {
                    val git = opened.data
                    when (val result = GitEngine.removeFile(git, node.relativePath)) {
                        is GitResult.Success -> _uiState.value = _uiState.value.copy(
                            isBusy = false,
                            message = "Deleted ${node.name} — staged, ready to commit",
                        )
                        is GitResult.Error -> _uiState.value = _uiState.value.copy(isBusy = false, message = result.message)
                    }
                    git.close()
                    refresh()
                }
            }
        }
    }

    /** Stages every selected file/folder in one repo open/close pair — same underlying
     *  per-path `git add` as the single-file flow, just looped, so a long-press selection of
     *  a dozen files doesn't mean a dozen separate JGit repo opens. */
    fun bulkStage(nodes: List<FileNode>) {
        val repo = _uiState.value.repo ?: return
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _uiState.value = _uiState.value.copy(isBusy = false, message = opened.message)
                is GitResult.Success -> {
                    val git = opened.data
                    var failed = 0
                    for (node in nodes) {
                        if (GitEngine.stageFile(git, node.relativePath) is GitResult.Error) failed++
                    }
                    git.close()
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        message = if (failed == 0) "Staged ${nodes.size} item(s)" else "Staged ${nodes.size - failed} of ${nodes.size} — $failed failed",
                    )
                    refresh()
                }
            }
        }
    }

    /** Deletes-and-stages every selected file/folder — same `git rm` semantics per item as
     *  the single-file delete (a plain filesystem delete wouldn't stage the removal, so
     *  "Stage" afterward would silently do nothing; see [delete] for the full reasoning). */
    fun bulkDelete(nodes: List<FileNode>) {
        val repo = _uiState.value.repo ?: return
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _uiState.value = _uiState.value.copy(isBusy = false, message = opened.message)
                is GitResult.Success -> {
                    val git = opened.data
                    var failed = 0
                    for (node in nodes) {
                        if (GitEngine.removeFile(git, node.relativePath) is GitResult.Error) failed++
                    }
                    git.close()
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        message = if (failed == 0) "Deleted ${nodes.size} item(s) — staged, ready to commit" else "Deleted ${nodes.size - failed} of ${nodes.size} — $failed failed",
                    )
                    refresh()
                }
            }
        }
    }

    /** New file/folder/import all target the repo root — there's no more "current folder"
     *  concept now that the whole tree is one always-expandable view instead of navigating
     *  screen-to-screen. Trade-off worth knowing: creating something inside a deeply nested
     *  folder means creating it at the root and dragging it in a file manager, or renaming it
     *  with the folder prefix in its name. */
    fun createFile(name: String) {
        val dir = currentDir() ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val f = File(dir, name)
                    if (f.exists()) return@withContext "A file named \"$name\" already exists"
                    f.createNewFile()
                    null
                } catch (e: Exception) {
                    e.message ?: "Couldn't create file"
                }
            }
            _uiState.value = _uiState.value.copy(message = result ?: "Created $name")
            refresh()
        }
    }

    fun createFolder(name: String) {
        val dir = currentDir() ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val f = File(dir, name)
                if (f.exists()) "A folder named \"$name\" already exists"
                else if (f.mkdirs()) null
                else "Couldn't create folder"
            }
            _uiState.value = _uiState.value.copy(
                message = result ?: "Created $name — note: git won't track an empty folder until it has a file in it",
            )
            refresh()
        }
    }

    /** Copies one or more picked files (from the system file manager / photos app / etc,
     * via SAF) into the repo root, preserving their original filenames where possible. */
    fun importFiles(context: Context, uris: List<Uri>) {
        val dir = currentDir() ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            val imported = withContext(Dispatchers.IO) {
                var count = 0
                for (uri in uris) {
                    try {
                        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "imported_file"
                        val dest = File(dir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        count++
                    } catch (e: Exception) {
                        // Best-effort: skip a file that failed to copy rather than aborting
                        // the whole batch, since one bad URI shouldn't block the rest.
                    }
                }
                count
            }
            _uiState.value = _uiState.value.copy(isBusy = false, message = "Imported $imported file(s)")
            refresh()
        }
    }

    /** Recursively copies an entire picked folder (via SAF's "open document tree" picker)
     * into a new subfolder here, named after the picked folder. */
    fun importFolder(context: Context, treeUri: Uri) {
        val dir = currentDir() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            val result = withContext(Dispatchers.IO) {
                try {
                    val pickedRoot = DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@withContext "Couldn't open that folder"
                    val destRoot = File(dir, pickedRoot.name ?: "imported_folder")
                    if (destRoot.exists()) return@withContext "A folder named \"${destRoot.name}\" already exists"
                    val fileCount = copyDocumentTree(context, pickedRoot, destRoot)
                    "Imported ${destRoot.name} ($fileCount file(s))"
                } catch (e: Exception) {
                    e.message ?: "Import failed"
                }
            }
            _uiState.value = _uiState.value.copy(isBusy = false, message = result)
            refresh()
        }
    }

    private fun copyDocumentTree(context: Context, source: DocumentFile, destDir: File): Int {
        destDir.mkdirs()
        var count = 0
        for (child in source.listFiles()) {
            if (child.isDirectory) {
                count += copyDocumentTree(context, child, File(destDir, child.name ?: "folder"))
            } else {
                val name = child.name ?: continue
                val destFile = File(destDir, name)
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                count++
            }
        }
        return count
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    fun push() {
        val repo = _uiState.value.repo ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true)
            val credential = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
            when (val openResult = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _uiState.value = _uiState.value.copy(isBusy = false, message = openResult.message)
                is GitResult.Success -> {
                    val git = openResult.data
                    when (val result = GitEngine.push(git, credential = credential)) {
                        is GitResult.Error -> {
                            repoRepo.markError(repo.id, result.message)
                            _uiState.value = _uiState.value.copy(isBusy = false, message = result.message)
                        }
                        is GitResult.Success -> {
                            repoRepo.markSyncSuccess(repo.id)
                            _uiState.value = _uiState.value.copy(isBusy = false, message = "Pushed")
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

private val TREE_INDENT = 18.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    repoId: Long,
    revealPath: String = "",
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenBlame: (String) -> Unit,
    onOpenSearch: () -> Unit = {},
    vm: FileExplorerViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsState()
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    var nodePendingRename by remember { mutableStateOf<FileNode?>(null) }
    var nodePendingDelete by remember { mutableStateOf<FileNode?>(null) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val selectionMode = selectedPaths.isNotEmpty()

    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.importFiles(context, uris) }

    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { vm.importFolder(context, it) } }

    LaunchedEffect(repoId) { vm.load(repoId, revealPath) }
    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); vm.dismissMessage() }
    }
    // Selection only makes sense against rows still on screen — if the tree refreshes out
    // from under an active selection (rename/delete elsewhere, a pull, etc.) just drop
    // whatever's no longer visible rather than risk acting on stale paths.
    LaunchedEffect(state.visibleRows) {
        val currentPaths = state.visibleRows.map { it.node.relativePath }.toSet()
        if (selectedPaths.any { it !in currentPaths }) selectedPaths = selectedPaths.intersect(currentPaths)
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedPaths.size} selected", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { selectedPaths = emptySet() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    },
                    actions = {
                        val selectedNodes = state.visibleRows.map { it.node }.filter { it.relativePath in selectedPaths }
                        IconButton(onClick = { vm.bulkStage(selectedNodes); selectedPaths = emptySet() }, enabled = !state.isBusy) {
                            Icon(Icons.Filled.AddCircleOutline, "Stage selected")
                        }
                        IconButton(onClick = { showBulkDeleteConfirm = true }, enabled = !state.isBusy) {
                            Icon(Icons.Filled.Delete, "Delete selected", tint = StatusDeleted)
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { RepoTitleBlock(state.repo?.name ?: "Files", state.repo?.branch) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch, enabled = !state.isBusy) {
                            Icon(Icons.Filled.Search, "Search this repo")
                        }
                        IconButton(onClick = vm::push, enabled = !state.isBusy) {
                            if (state.isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.ArrowUpward, "Push")
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) { Icon(Icons.Filled.MoreVert, "Tree options") }
                            DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Show hidden files") },
                                    trailingIcon = { Checkbox(checked = state.showHiddenFiles, onCheckedChange = null) },
                                    onClick = { vm.setShowHiddenFiles(!state.showHiddenFiles) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Compact folders") },
                                    trailingIcon = { Checkbox(checked = state.compactFolders, onCheckedChange = null) },
                                    onClick = { vm.setCompactFolders(!state.compactFolders) },
                                )
                                HorizontalDivider()
                                SortModeItem("Sort by name", ExplorerSortMode.NAME, state.sortMode) { vm.setSortMode(it); showOverflowMenu = false }
                                SortModeItem("Sort by size", ExplorerSortMode.SIZE, state.sortMode) { vm.setSortMode(it); showOverflowMenu = false }
                                SortModeItem("Sort by date", ExplorerSortMode.DATE, state.sortMode) { vm.setSortMode(it); showOverflowMenu = false }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Collapse all") },
                                    leadingIcon = { Icon(Icons.Filled.UnfoldLess, null) },
                                    onClick = { vm.collapseAll(); showOverflowMenu = false },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FileExplorerFab(
                    expanded = fabExpanded,
                    onToggle = { fabExpanded = !fabExpanded },
                    onNewFile = { fabExpanded = false; showNewFileDialog = true },
                    onNewFolder = { fabExpanded = false; showNewFolderDialog = true },
                    onImportFiles = { fabExpanded = false; importFilesLauncher.launch(arrayOf("*/*")) },
                    onImportFolder = { fabExpanded = false; importFolderLauncher.launch(null) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snack) { d -> Snackbar(d) } },
    ) { pad ->
        Box(
            Modifier.fillMaxSize().let {
                if (fabExpanded) it.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { fabExpanded = false } else it
            },
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.visibleRows.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Empty folder", color = StatusClean)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap + to create a file, or import from your file manager",
                            style = MaterialTheme.typography.bodySmall, color = StatusClean,
                        )
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                    modifier = Modifier.fillMaxSize().padding(pad),
                ) {
                    items(state.visibleRows, key = { it.node.relativePath }) { row ->
                        TreeRowItem(
                            row = row,
                            selectionMode = selectionMode,
                            selected = row.node.relativePath in selectedPaths,
                            onClick = {
                                when {
                                    selectionMode -> selectedPaths = if (row.node.relativePath in selectedPaths) selectedPaths - row.node.relativePath else selectedPaths + row.node.relativePath
                                    row.node.isDirectory -> vm.toggleExpand(row.node)
                                    else -> onOpenFile(row.node.relativePath)
                                }
                            },
                            onLongClick = { selectedPaths = selectedPaths + row.node.relativePath },
                            onRename = { nodePendingRename = row.node },
                            onDelete = { nodePendingDelete = row.node },
                            onBlame = { onOpenBlame(row.node.relativePath) },
                        )
                    }
                }
            }
        }
    }

    if (showBulkDeleteConfirm) {
        val selectedNodes = state.visibleRows.map { it.node }.filter { it.relativePath in selectedPaths }
        ConfirmDialog(
            title = "Delete ${selectedNodes.size} item(s)?",
            body = "This stages the removal of everything selected — commit and push to remove it from the remote too. Can't be undone locally.",
            confirmLabel = "Delete",
            danger = true,
            onDismiss = { showBulkDeleteConfirm = false },
            onConfirm = { vm.bulkDelete(selectedNodes); showBulkDeleteConfirm = false; selectedPaths = emptySet() },
        )
    }

    if (showNewFileDialog) {
        SingleInputDialog("New File", "File name", "",
            onConfirm = { vm.createFile(it); showNewFileDialog = false }, onDismiss = { showNewFileDialog = false })
    }
    if (showNewFolderDialog) {
        SingleInputDialog("New Folder", "Folder name", "",
            onConfirm = { vm.createFolder(it); showNewFolderDialog = false }, onDismiss = { showNewFolderDialog = false })
    }
    nodePendingRename?.let { node ->
        SingleInputDialog("Rename", "New name", node.name.substringAfterLast('/'),
            onConfirm = { vm.rename(node, it); nodePendingRename = null }, onDismiss = { nodePendingRename = null })
    }
    nodePendingDelete?.let { node ->
        ConfirmDialog(
            title = "Delete ${node.name}?",
            body = if (node.isDirectory) "Deletes this folder and everything inside it, and stages the removal."
                   else "Stages the removal — commit and push to remove it from the remote too.",
            confirmLabel = "Delete", danger = true,
            onDismiss = { nodePendingDelete = null },
            onConfirm = { vm.delete(node); nodePendingDelete = null },
        )
    }
}

@Composable
private fun SortModeItem(label: String, mode: ExplorerSortMode, current: ExplorerSortMode, onSelect: (ExplorerSortMode) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(
                if (current == mode) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                null, tint = if (current == mode) CommandBlue else StatusClean,
            )
        },
        onClick = { onSelect(mode) },
    )
}

/**
 * One row in the flattened tree. Deliberately flat (no per-row card/border) rather than the
 * GlassCard-per-item treatment used elsewhere in the app — a dense tree with rounded card
 * chrome around every single row, several levels deep, stops reading as a tree at all. The
 * screen's own background carries the "glass" identity; individual rows are plain, VS
 * Code-style list items with indentation guides doing the visual structuring instead.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TreeRowItem(
    row: TreeRow,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBlame: () -> Unit,
) {
    val node = row.node
    var showMenu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val (icon, iconTint) = fileIconFor(node)

    val statusColor = when (row.gitStatus) {
        GitFileStatus.ADDED -> StatusAdded
        GitFileStatus.MODIFIED, GitFileStatus.RENAMED, GitFileStatus.TYPE_CHANGED -> StatusModified
        GitFileStatus.DELETED, GitFileStatus.CONFLICTED -> StatusDeleted
        null -> null
    }
    val statusLetter = when (row.gitStatus) {
        GitFileStatus.ADDED -> "A"
        GitFileStatus.MODIFIED -> "M"
        GitFileStatus.DELETED -> "D"
        GitFileStatus.RENAMED -> "R"
        GitFileStatus.TYPE_CHANGED -> "T"
        GitFileStatus.CONFLICTED -> "!"
        null -> null
    }

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .background(if (selected) CommandBlue.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(vertical = 7.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Indentation guide lines — one thin vertical rule per ancestor level, the classic
        // tree "ladder" so nesting depth reads at a glance even before you look at the icons.
        repeat(row.depth) {
            Box(Modifier.width(TREE_INDENT).fillMaxHeight()) {
                Box(
                    Modifier.width(1.dp).fillMaxHeight().align(Alignment.Center)
                        .background(StatusClean.copy(alpha = 0.18f)),
                )
            }
        }

        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            if (node.isDirectory) {
                val rotation by androidx.compose.animation.core.animateFloatAsState(if (row.isExpanded) 90f else 0f, label = "chevron")
                Icon(
                    Icons.Filled.ChevronRight, null,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                    tint = StatusClean,
                )
            }
        }

        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(2.dp))
        }

        Icon(icon, null, Modifier.size(18.dp), tint = iconTint)
        Spacer(Modifier.width(8.dp))

        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = if (row.hasDirtyDescendant && node.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )

        when {
            statusLetter != null -> Text(
                statusLetter, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                color = statusColor ?: StatusClean, modifier = Modifier.padding(end = 4.dp),
            )
            row.hasDirtyDescendant && node.isDirectory -> Box(
                Modifier.size(6.dp).clip(CircleShape).background(Emerald).padding(end = 4.dp),
            )
        }

        // Compacted chain rows ("app/src/main") skip the per-row menu — renaming/deleting a
        // merged multi-folder row is ambiguous (which segment?), so only the leaf folders you
        // reach by expanding further, and plain files, get this menu.
        if (!selectionMode && !row.isCompactedChain) {
            Box {
                IconButton(onClick = { showMenu = true }, Modifier.size(30.dp)) {
                    Icon(Icons.Filled.MoreVert, null, Modifier.size(16.dp), tint = StatusClean)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    if (!node.isDirectory) {
                        DropdownMenuItem(text = { Text("Blame") }, onClick = { showMenu = false; onBlame() },
                            leadingIcon = { Icon(Icons.Filled.History, null) })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = StatusDeleted) })
                }
            }
        }
    }
}

/**
 * Every way to add something to this repo, in one place — replaces what used to be a
 * dropdown mixing file creation with file import. Same speed-dial pattern as the repo list's
 * FAB: tap to reveal four labeled options, tap one to act.
 */
@Composable
private fun FileExplorerFab(
    expanded: Boolean, onToggle: () -> Unit,
    onNewFile: () -> Unit, onNewFolder: () -> Unit, onImportFiles: () -> Unit, onImportFolder: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.End) {
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExplorerFabOption("Import folder", Icons.Filled.DriveFolderUpload, onImportFolder)
                ExplorerFabOption("Import files", Icons.Filled.UploadFile, onImportFiles)
                ExplorerFabOption("New folder", Icons.Filled.CreateNewFolder, onNewFolder)
                ExplorerFabOption("New file", Icons.Filled.NoteAdd, onNewFile)
                Spacer(Modifier.height(4.dp))
            }
        }
        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) {
            val rotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 45f else 0f, label = "fabRotation")
            Icon(Icons.Filled.Add, if (expanded) "Close" else "Add", modifier = Modifier.graphicsLayer { rotationZ = rotation })
        }
    }
}

@Composable
private fun ExplorerFabOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
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
