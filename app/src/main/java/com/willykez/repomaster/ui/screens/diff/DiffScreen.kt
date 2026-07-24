package com.willykez.repomaster.ui.screens.diff

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.App
import com.willykez.repomaster.git.GitEngine
import com.willykez.repomaster.git.GitResult
import com.willykez.repomaster.ui.screens.editor.CodeLanguage
import com.willykez.repomaster.ui.screens.editor.highlightText
import com.willykez.repomaster.ui.screens.editor.languageForPath
import com.willykez.repomaster.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder

data class DiffUiState(
    val title: String = "",
    val sections: List<DiffFileSection> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null,
)

class DiffViewModel(app: Application) : AndroidViewModel(app) {
    private val repoRepo = (app as App).repoRepository
    private val _state = MutableStateFlow(DiffUiState())
    val state: StateFlow<DiffUiState> = _state.asStateFlow()

    fun load(repoId: Long, encodedPath: String, stagedOrCommit: String) {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            val path = URLDecoder.decode(encodedPath, "UTF-8")
            _state.value = _state.value.copy(title = path, isLoading = true)
            when (val gr = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _state.value = _state.value.copy(isLoading = false, message = gr.message)
                is GitResult.Success -> {
                    val g = gr.data
                    val rawResult = when (stagedOrCommit) {
                        "true"   -> GitEngine.getDiff(g, path, staged = true)
                        "false"  -> GitEngine.getDiff(g, path, staged = false)
                        "commit" -> GitEngine.getCommitDiff(g, path) // path = sha here
                        else     -> GitEngine.getDiff(g, path, staged = false)
                    }
                    g.close()
                    when (rawResult) {
                        is GitResult.Error -> _state.value = _state.value.copy(isLoading = false, message = rawResult.message)
                        is GitResult.Success -> {
                            val sections = parseUnifiedDiff(rawResult.data, fallbackPath = path)
                            _state.value = _state.value.copy(sections = sections, isLoading = false)
                        }
                    }
                }
            }
        }
    }

    fun dismiss() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DiffScreen(
    repoId: Long, encodedPath: String, stagedOrCommit: String,
    onBack: () -> Unit, vm: DiffViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(repoId, encodedPath) { vm.load(repoId, encodedPath, stagedOrCommit) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismiss() } }

    // A commit diff can touch many files — collapsing lets you scan the file list first
    // and open only the ones you actually need to review, instead of scrolling past
    // everything. A single-file diff (staged/unstaged) never has more than one section,
    // so this never gets in the way there.
    var collapsedIndices by remember { mutableStateOf(setOf<Int>()) }
    val isMultiFile = state.sections.size > 1
    val allCollapsed = isMultiFile && collapsedIndices.size == state.sections.size

    val isCommit = stagedOrCommit == "commit"
    val totalAdditions = remember(state.sections) { state.sections.sumOf { it.additions } }
    val totalDeletions = remember(state.sections) { state.sections.sumOf { it.deletions } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isCommit) {
                        Column {
                            Text("Commit ${state.title.take(7)}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            if (state.sections.isNotEmpty()) {
                                Text(
                                    "${state.sections.size} file${if (state.sections.size == 1) "" else "s"} changed",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(state.title, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 15.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    // Only offered for a single-file working-tree diff — a commit diff is
                    // already-history (nothing to write a message for), and the multi-file
                    // case is exactly what the Changes screen's own Generate already covers.
                    if (!isCommit && state.sections.size == 1) {
                        val scope = rememberCoroutineScope()
                        val section = state.sections.first()
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(buildDiffCommitMessage(section)))
                            scope.launch { snack.showSnackbar("Message copied — paste it into the commit box") }
                        }) {
                            Icon(Icons.Filled.ContentCopy, "Generate commit message from this diff")
                        }
                    }
                    if (isMultiFile) {
                        IconButton(onClick = {
                            collapsedIndices = if (allCollapsed) emptySet() else state.sections.indices.toSet()
                        }) {
                            Icon(
                                if (allCollapsed) Icons.Filled.UnfoldMore else Icons.Filled.UnfoldLess,
                                if (allCollapsed) "Expand all" else "Collapse all",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.sections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No diff available", color = StatusClean)
            }
        } else {
            // Shared so every row's code portion scrolls horizontally together — the
            // line-number gutter stays pinned since it's outside this scroll modifier.
            val hScroll = rememberScrollState()
            val syntaxColors = currentSyntaxColors()

            Column(Modifier.fillMaxSize().padding(pad)) {
                if (isCommit && state.sections.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("+$totalAdditions", color = StatusAdded, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("-$totalDeletions", color = StatusDeleted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }

                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    state.sections.forEachIndexed { fileIdx, section ->
                        val collapsed = fileIdx in collapsedIndices
                        item(key = "header-$fileIdx") {
                            DiffFileHeader(
                                section = section,
                                collapsed = collapsed,
                                showToggle = isMultiFile,
                                onToggle = {
                                    collapsedIndices = if (collapsed) collapsedIndices - fileIdx else collapsedIndices + fileIdx
                                },
                                itemModifier = Modifier.animateItem(),
                            )
                        }
                        if (!collapsed) {
                            if (section.isBinary) {
                                item(key = "binary-$fileIdx") {
                                    Text(
                                        "Binary file — no text diff to show",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                            } else if (section.lines.isEmpty()) {
                                item(key = "nochange-$fileIdx") {
                                    Text(
                                        if (section.isRenamed) "Renamed — no content changes" else "No content changes",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                            } else {
                                val lang = languageForPath(section.displayPath)
                                itemsIndexed(section.lines, key = { lineIdx, _ -> "$fileIdx:$lineIdx" }) { _, line ->
                                    DiffLineRow(line, lang, syntaxColors, hScroll, itemModifier = Modifier.animateItem())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffFileHeader(
    section: DiffFileSection,
    collapsed: Boolean,
    showToggle: Boolean,
    onToggle: () -> Unit,
    itemModifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "chevronRotation")
    val statusColor = when {
        section.isNew -> StatusAdded
        section.isDeleted -> StatusDeleted
        section.isRenamed -> SignalGold
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when {
        section.isNew -> "NEW"
        section.isDeleted -> "DELETED"
        section.isRenamed -> "RENAMED"
        else -> null
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = itemModifier
            .fillMaxWidth()
            .then(if (showToggle) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showToggle) {
                Icon(
                    Icons.Filled.ExpandMore, if (collapsed) "Expand" else "Collapse",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(Icons.Filled.Description, null, tint = statusColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    section.displayPath,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (section.isRenamed && section.oldPath != null) {
                    Text(
                        "was ${section.oldPath}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (statusLabel != null) {
                Text(
                    statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (section.additions > 0) {
                Text("+${section.additions}", color = StatusAdded, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            if (section.deletions > 0) {
                Text(" -${section.deletions}", color = StatusDeleted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DiffLineRow(
    line: DiffLine,
    lang: CodeLanguage,
    syntaxColors: SyntaxColorSet,
    hScroll: androidx.compose.foundation.ScrollState,
    itemModifier: Modifier = Modifier,
) {
    if (line.type == DiffLineType.HUNK) {
        Text(
            text = line.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = itemModifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        return
    }

    // Theme-aware translucent tints instead of fixed dark hex colors — these read
    // correctly over both a light and a dark surface since the base surface itself
    // is what actually flips between themes; a fixed near-black background behind
    // them wouldn't.
    val rowBg = when (line.type) {
        DiffLineType.ADDED   -> StatusAdded.copy(alpha = 0.13f)
        DiffLineType.REMOVED -> StatusDeleted.copy(alpha = 0.13f)
        else -> Color.Transparent
    }
    val markerColor = when (line.type) {
        DiffLineType.ADDED   -> StatusAdded
        DiffLineType.REMOVED -> StatusDeleted
        else -> Color.Transparent
    }
    val marker = when (line.type) {
        DiffLineType.ADDED   -> "+"
        DiffLineType.REMOVED -> "-"
        else -> " "
    }

    Row(
        itemModifier.fillMaxWidth().background(rowBg),
        verticalAlignment = Alignment.Top,
    ) {
        LineNumberCell(line.oldLineNo)
        LineNumberCell(line.newLineNo)
        Text(
            marker,
            color = markerColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.width(14.dp),
        )
        Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
            Text(
                text = highlightText(line.text, lang, syntaxColors),
                color = syntaxColors.plain,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                softWrap = false,
                modifier = Modifier.padding(end = 24.dp),
            )
        }
    }
}

@Composable
private fun LineNumberCell(number: Int?) {
    Text(
        number?.toString().orEmpty(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.End,
        modifier = Modifier.width(34.dp).padding(end = 4.dp),
    )
}
