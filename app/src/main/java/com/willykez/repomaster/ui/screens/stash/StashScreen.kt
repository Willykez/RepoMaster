package com.willykez.repomaster.ui.screens.stash

import android.app.Application
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willykez.repomaster.App
import com.willykez.repomaster.git.GitEngine
import com.willykez.repomaster.git.GitResult
import com.willykez.repomaster.git.StashInfo
import com.willykez.repomaster.ui.screens.changes.ConfirmDialog
import com.willykez.repomaster.ui.screens.changes.SingleInputDialog
import com.willykez.repomaster.ui.components.GlassCard
import com.willykez.repomaster.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import java.text.DateFormat
import java.util.Date

data class StashUiState(
    val repoName: String = "", val stashes: List<StashInfo> = emptyList(),
    val isLoading: Boolean = true, val isWorking: Boolean = false, val message: String? = null,
)

class StashViewModel(app: Application) : AndroidViewModel(app) {
    private val repoRepo = (app as App).repoRepository
    private val _state = MutableStateFlow(StashUiState())
    val state: StateFlow<StashUiState> = _state.asStateFlow()
    private var git: Git? = null
    private var repoId: Long = -1

    fun load(id: Long) {
        repoId = id
        viewModelScope.launch {
            val repo = repoRepo.getById(id) ?: return@launch
            _state.value = _state.value.copy(repoName = repo.name, isLoading = true)
            when (val gr = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> err(gr.message)
                is GitResult.Success -> { git = gr.data; refresh() }
            }
        }
    }

    private suspend fun refresh() {
        val g = git ?: return
        when (val r = GitEngine.listStashes(g)) {
            is GitResult.Success -> _state.value = _state.value.copy(stashes = r.data, isLoading = false)
            is GitResult.Error -> err(r.message)
        }
    }

    fun save(message: String) = op { g ->
        when (val r = GitEngine.stashSave(g, message)) {
            is GitResult.Success -> ok("Stashed: ${r.data.take(12)}")
            is GitResult.Error -> err(r.message)
        }
    }

    fun apply(ref: String) = op { g ->
        when (val r = GitEngine.stashApply(g, ref)) {
            is GitResult.Success -> ok("Applied stash")
            is GitResult.Error -> err(r.message)
        }
    }

    fun pop() = op { g ->
        when (val r = GitEngine.stashPop(g)) {
            is GitResult.Success -> ok("Popped stash")
            is GitResult.Error -> err(r.message)
        }
    }

    fun drop(index: Int) = op { g ->
        when (val r = GitEngine.stashDrop(g, index)) {
            is GitResult.Success -> ok("Dropped stash@{$index}")
            is GitResult.Error -> err(r.message)
        }
    }

    fun dismiss() { _state.value = _state.value.copy(message = null) }
    private fun err(m: String) { _state.value = _state.value.copy(message = m, isLoading = false, isWorking = false) }
    private fun ok(m: String) { _state.value = _state.value.copy(message = m) }
    private fun op(block: suspend (Git) -> Unit) {
        val g = git ?: run { err("Repo not open"); return }
        viewModelScope.launch { _state.value = _state.value.copy(isWorking = true); block(g); refresh(); _state.value = _state.value.copy(isWorking = false) }
    }
    override fun onCleared() { super.onCleared(); git?.close() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StashScreen(repoId: Long, onBack: () -> Unit, vm: StashViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    var showSaveDialog by remember { mutableStateOf(false) }
    var stashToDrop by remember { mutableStateOf<StashInfo?>(null) }

    LaunchedEffect(repoId) { vm.load(repoId) }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); vm.dismiss() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stash", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state.stashes.isNotEmpty()) IconButton(onClick = vm::pop) { Icon(Icons.Filled.PlayArrow, "Pop") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface, navigationIconContentColor = MaterialTheme.colorScheme.onSurface, actionIconContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSaveDialog = true },
                icon = { Icon(Icons.Filled.Save, null) }, text = { Text("Save stash") },
                containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.stashes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inventory, null, Modifier.size(48.dp), tint = StatusClean)
                    Spacer(Modifier.height(12.dp))
                    Text("No stashes", color = StatusClean)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.stashes, key = { it.sha }) { s ->
                    StashRow(s, onApply = { vm.apply("stash@{${s.index}}") }, onDrop = { stashToDrop = s })
                }
            }
        }
    }

    if (showSaveDialog) SingleInputDialog("Save Stash", "Stash message (optional)", "",
        onConfirm = { vm.save(it); showSaveDialog = false }, onDismiss = { showSaveDialog = false })
    stashToDrop?.let { s ->
        ConfirmDialog("Drop stash@{${s.index}}?", "This stash will be permanently deleted.", "Drop", danger = true,
            onConfirm = { vm.drop(s.index); stashToDrop = null }, onDismiss = { stashToDrop = null })
    }
}

@Composable
private fun StashRow(s: StashInfo, onApply: () -> Unit, onDrop: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), accent = Amber) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Inventory, null, Modifier.size(20.dp), tint = Amber)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("stash@{${s.index}}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = StatusClean, maxLines = 1)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.time)),
                    style = MaterialTheme.typography.labelSmall, color = StatusClean)
            }
            Box {
                IconButton(onClick = { expanded = true }, Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, null, Modifier.size(16.dp), tint = StatusClean) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Apply") }, onClick = { expanded = false; onApply() })
                    DropdownMenuItem(text = { Text("Drop", color = StatusDeleted) }, onClick = { expanded = false; onDrop() })
                }
            }
        }
    }
}
