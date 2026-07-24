package com.willykez.repomaster.ui.screens.log

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.App
import com.willykez.repomaster.git.CommitInfo
import com.willykez.repomaster.git.GitEngine
import com.willykez.repomaster.git.GitResult
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.components.RepoTitleBlock
import com.willykez.repomaster.ui.theme.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** One row's position in the simplified commit graph — which lane its dot sits in, which
 * lane(s) its parent(s) will appear in (drawn as lines to the bottom of the row), and
 * whether a line is expected to arrive from the row above at this same lane. */
data class GraphRowLayout(
    val lane: Int,
    val parentLanes: List<Int>,
    val hasIncoming: Boolean,
    val laneCount: Int,
)

/**
 * A simplified lane-assignment algorithm for a `git log --graph`-style view — not a
 * pixel-perfect reimplementation of git's own graph layout, but a reasonable approximation:
 * each open lane "waits" for a specific commit SHA (its next expected commit in that line
 * of history); a commit reuses whichever lane was waiting for it, or opens a new one if
 * none was. Merge commits (2+ parents) keep their first parent in the same lane and open
 * (or reuse) a lane per additional parent, which is what produces the fork/join look.
 */
fun computeCommitGraph(commits: List<CommitInfo>): List<GraphRowLayout> {
    val lanes = mutableListOf<String?>() // slot index -> sha this lane is waiting for
    val rows = mutableListOf<GraphRowLayout>()

    for ((index, commit) in commits.withIndex()) {
        var laneIndex = lanes.indexOf(commit.sha)
        val hasIncoming = laneIndex != -1
        if (laneIndex == -1) {
            laneIndex = lanes.indexOfFirst { it == null }
            if (laneIndex == -1) { lanes.add(null); laneIndex = lanes.size - 1 }
        }

        val parentLanes = mutableListOf<Int>()
        if (commit.parentShas.isNotEmpty()) {
            lanes[laneIndex] = commit.parentShas[0] // first parent continues in the same lane
            parentLanes.add(laneIndex)
            for (parentSha in commit.parentShas.drop(1)) {
                var pLane = lanes.indexOf(parentSha)
                if (pLane == -1) {
                    pLane = lanes.indexOfFirst { it == null }
                    if (pLane == -1) { lanes.add(parentSha); pLane = lanes.size - 1 } else lanes[pLane] = parentSha
                }
                parentLanes.add(pLane)
            }
        } else {
            lanes[laneIndex] = null // root commit — nothing continues past it
        }

        rows.add(GraphRowLayout(laneIndex, parentLanes, hasIncoming && index > 0, lanes.size))
    }

    val maxLanes = rows.maxOfOrNull { it.laneCount } ?: 1
    return rows.map { it.copy(laneCount = maxLanes) }
}

data class LogUiState(
    val repoName: String = "",
    val branch: String = "",
    val commits: List<CommitInfo> = emptyList(),
    val graph: List<GraphRowLayout> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null,
)

class LogViewModel(app: Application) : AndroidViewModel(app) {
    private val repoRepo = (app as App).repoRepository
    private val _state = MutableStateFlow(LogUiState())
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    fun load(repoId: Long) {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            _state.value = _state.value.copy(repoName = repo.name, branch = repo.branch, isLoading = true)
            when (val gr = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _state.value = _state.value.copy(isLoading = false, message = gr.message)
                is GitResult.Success -> when (val lr = GitEngine.getLog(gr.data)) {
                    is GitResult.Error -> _state.value = _state.value.copy(isLoading = false, message = lr.message)
                    is GitResult.Success -> _state.value = _state.value.copy(
                    commits = lr.data, graph = computeCommitGraph(lr.data), isLoading = false,
                )
                }
            }
        }
    }
    fun dismiss() { _state.value = _state.value.copy(message = null) }

    fun showTransientMessage(msg: String) { _state.value = _state.value.copy(message = msg) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(repoId: Long, onBack: () -> Unit, onOpenDiff: (Long, String) -> Unit,
              vm: LogViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismiss() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { RepoTitleBlock(state.repoName.ifBlank { "History" }, state.branch) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.commits.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.History, null, Modifier.size(48.dp), tint = StatusClean)
                    Spacer(Modifier.height(12.dp))
                    Text("No commits yet", style = MaterialTheme.typography.titleMedium)
                    Text("Commit history for this repo will show up here",
                        style = MaterialTheme.typography.bodySmall, color = StatusClean)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.commits, key = { _, c -> c.sha }) { index, c ->
                    CommitRow(
                        c,
                        graphRow = state.graph.getOrNull(index),
                        onClick = { onOpenDiff(repoId, c.sha) },
                        onCopySha = {
                            clipboard.setText(AnnotatedString(c.sha))
                            vm.showTransientMessage("Commit SHA copied")
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommitRow(c: CommitInfo, graphRow: GraphRowLayout?, onClick: () -> Unit, onCopySha: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onCopySha), accent = Amber) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            CommitGraphLane(graphRow, modifier = Modifier.height(56.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.message, fontWeight = FontWeight.Medium, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(c.shortSha, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Amber)
                    Text(c.authorName, fontSize = 11.sp, color = StatusClean)
                    Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(c.authorTime)),
                        fontSize = 11.sp, color = StatusClean)
                }
            }
            IconButton(onClick = onCopySha, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.ContentCopy, "Copy SHA", Modifier.size(14.dp), tint = StatusClean)
            }
            Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = StatusClean)
        }
    }
}

private val GRAPH_LANE_WIDTH = 18.dp

/** Cycles through the app's existing 3-color accent system rather than introducing new hues —
 *  a lane's color has no persistent meaning across a repo's whole history anyway (lane 0 today
 *  isn't "the same branch" as lane 0 a hundred commits back once branches fork and merge), so
 *  what matters is that adjacent, currently-diverging lanes read as visually distinct, not
 *  that any specific color maps to any specific named branch. */
private val LANE_PALETTE = listOf(CommandBlue, Amber, Emerald)
private fun laneColor(lane: Int): Color = LANE_PALETTE[lane % LANE_PALETTE.size]

/**
 * Draws this commit's lane dot, an incoming line from the row above (if one is expected to
 * continue into this same lane), and outgoing lines to each parent's lane at the bottom of
 * the row. Each row only ever draws to its own top/bottom edge, which is what keeps lines
 * visually continuous across independently-measured LazyColumn items without needing one
 * giant shared canvas for the whole list.
 *
 * The dot and the incoming line both use this row's own lane color. Each outgoing line to a
 * parent is colored by *that parent's* lane instead — the segment is "arriving into" that
 * lane, which is what makes a merge commit read correctly at a glance: the line forking off
 * to the side into another lane takes on that other lane's color rather than staying the
 * current row's color the whole way down.
 */
@Composable
private fun CommitGraphLane(row: GraphRowLayout?, modifier: Modifier = Modifier) {
    if (row == null) {
        Box(modifier.width(GRAPH_LANE_WIDTH))
        return
    }
    val laneCount = row.laneCount.coerceAtLeast(1)
    val myColor = laneColor(row.lane)
    Canvas(modifier = modifier.width(GRAPH_LANE_WIDTH * laneCount)) {
        val laneWidthPx = GRAPH_LANE_WIDTH.toPx()
        val centerY = size.height / 2f
        val myX = row.lane * laneWidthPx + laneWidthPx / 2f
        val strokeWidth = 3.dp.toPx()

        if (row.hasIncoming) {
            drawLine(myColor, Offset(myX, 0f), Offset(myX, centerY), strokeWidth = strokeWidth)
        }
        row.parentLanes.forEach { parentLane ->
            val parentX = parentLane * laneWidthPx + laneWidthPx / 2f
            drawLine(laneColor(parentLane), Offset(myX, centerY), Offset(parentX, size.height), strokeWidth = strokeWidth)
        }
        drawCircle(myColor, radius = 5.dp.toPx(), center = Offset(myX, centerY))
    }
}
