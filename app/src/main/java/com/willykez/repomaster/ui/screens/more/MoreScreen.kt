package com.willykez.repomaster.ui.screens.more

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.willykez.repomaster.App
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.components.RepoTitleBlock
import com.willykez.repomaster.ui.theme.Amber
import com.willykez.repomaster.ui.theme.CommandBlue
import com.willykez.repomaster.ui.theme.Emerald
import com.willykez.repomaster.ui.theme.SignalGold
import com.willykez.repomaster.ui.theme.StatusClean

private data class ToolTile(val label: String, val icon: ImageVector, val onClick: () -> Unit)
private data class ToolCategory(val title: String, val accent: Color, val tools: List<ToolTile>)

/**
 * The "Tools" tab — a single, predictable home for every repo-scoped git
 * action (branches, tags, stash, remotes, files, conflicts, .gitignore).
 *
 * Previously this screen duplicated navigation that also lived in the
 * Changes screen's icon row *and* its overflow sheet, and mixed in
 * app-level destinations (Discover/Credentials/Settings) that already live
 * on the Repos tab. Now there's exactly one path to each tool, this screen
 * only holds things that act on the *current repo*, and a header always
 * shows which repo that is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    repoId: Long?,
    onBack: () -> Unit,
    onOpenBranches: () -> Unit,
    onOpenStash: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenGitignore: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenActions: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val context = LocalContext.current
    val repoInfo by produceState<Pair<String, String>?>(initialValue = null, repoId) {
        value = if (repoId == null) null else {
            val app = context.applicationContext as App
            val repo = app.repoRepository.getById(repoId)
            repo?.let { it.name to it.branch }
        }
    }

    val categories = listOf(
        ToolCategory(
            title = "Branching",
            accent = CommandBlue,
            tools = listOf(
                ToolTile("Branches", Icons.Filled.AccountTree, onOpenBranches),
                ToolTile("Tags", Icons.Filled.Label, onOpenTags),
            ),
        ),
        ToolCategory(
            title = "Working tree",
            accent = SignalGold,
            tools = listOf(
                ToolTile("Stash", Icons.Filled.Inventory2, onOpenStash),
                ToolTile("Conflicts", Icons.Filled.Warning, onOpenConflicts),
                ToolTile(".gitignore", Icons.Filled.Block, onOpenGitignore),
            ),
        ),
        ToolCategory(
            title = "Repository",
            accent = Emerald,
            tools = listOf(
                ToolTile("Remotes", Icons.Filled.Cloud, onOpenRemote),
            ),
        ),
        ToolCategory(
            title = "CI/CD",
            accent = Amber,
            tools = listOf(
                ToolTile("Actions", Icons.Filled.PlayCircleOutline, onOpenActions),
            ),
        ),
        ToolCategory(
            title = "Advanced",
            accent = StatusClean,
            tools = listOf(
                ToolTile("Console", Icons.Filled.Terminal, onOpenConsole),
            ),
        ),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (repoInfo != null) RepoTitleBlock(repoInfo!!.first, repoInfo!!.second)
                    else Text("Tools", fontWeight = FontWeight.Bold)
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to repos") } },
            )
        },
    ) { pad ->
        if (repoId == null) {
            NoRepoSelected(Modifier.padding(pad))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                categories.forEach { category ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(category.title)
                    }
                    items(category.tools) { tool ->
                        ToolCard(tool, accent = category.accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoRepoSelected(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.FolderOff, null, tint = StatusClean, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("No repo selected", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick a repo from Home to see its tools here.",
            style = MaterialTheme.typography.bodyMedium,
            color = StatusClean,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = StatusClean,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToolCard(tool: ToolTile, accent: Color) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "toolCardScale",
    )

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = tool.onClick),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(tool.icon, null, tint = accent, modifier = Modifier.height(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                tool.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
