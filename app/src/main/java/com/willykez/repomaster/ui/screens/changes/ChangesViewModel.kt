package com.willykez.repomaster.ui.screens.changes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.willykez.repomaster.App
import com.willykez.repomaster.data.CommitPrefs
import com.willykez.repomaster.data.db.entity.RepoEntity
import com.willykez.repomaster.data.github.GitHubApi
import com.willykez.repomaster.data.github.GitHubResult
import com.willykez.repomaster.data.github.WorkflowRun
import com.willykez.repomaster.data.github.githubFullNameFromUrl
import com.willykez.repomaster.git.GitEngine
import com.willykez.repomaster.git.GitFileEntry
import com.willykez.repomaster.git.GitFileStatus
import com.willykez.repomaster.git.GitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git

/**
 * Heuristic commit message from a set of changed files — no network call, no AI, just the
 * same information already visible in the Changes list, reworded into prose. For one file,
 * names it directly ("Update FooScreen.kt"); for several, groups by status ("Update 3 files,
 * add 1") and appends the files' common parent directory if they share one, since "in
 * ui/screens/changes" is often more useful at a glance than the individual file list would be
 * in a single-line commit title.
 */
fun buildCommitMessageSummary(entries: List<GitFileEntry>): String {
    if (entries.isEmpty()) return ""

    fun verb(status: GitFileStatus, plural: Boolean) = when (status) {
        GitFileStatus.ADDED -> if (plural) "Add" else "Add"
        GitFileStatus.MODIFIED -> "Update"
        GitFileStatus.DELETED -> "Remove"
        GitFileStatus.RENAMED -> "Rename"
        GitFileStatus.TYPE_CHANGED -> "Change"
        GitFileStatus.CONFLICTED -> "Resolve conflicts in"
    }

    if (entries.size == 1) {
        val e = entries.first()
        val name = e.path.substringAfterLast('/')
        return "${verb(e.status, plural = false)} $name"
    }

    // Common parent directory across every changed file, if there is one — comparing path
    // segments position-by-position rather than string prefixes, so "src/foo.kt" and
    // "src2/bar.kt" correctly share nothing instead of falsely matching on "src".
    val dirSegments = entries.map { it.path.substringBeforeLast('/', missingDelimiterValue = "").split('/').filter { s -> s.isNotEmpty() } }
    val minDepth = dirSegments.minOf { it.size }
    var commonDepth = 0
    while (commonDepth < minDepth && dirSegments.all { it[commonDepth] == dirSegments.first()[commonDepth] }) commonDepth++
    val commonDir = dirSegments.first().take(commonDepth).joinToString("/")

    val counts = entries.groupingBy { it.status }.eachCount()
    // A stable, most-significant-first order so the summary reads naturally regardless of
    // which statuses happen to be present.
    val order = listOf(
        GitFileStatus.MODIFIED, GitFileStatus.ADDED, GitFileStatus.DELETED,
        GitFileStatus.RENAMED, GitFileStatus.TYPE_CHANGED, GitFileStatus.CONFLICTED,
    )
    val parts = order.mapNotNull { status ->
        val n = counts[status] ?: return@mapNotNull null
        val word = verb(status, plural = n > 1).lowercase()
        "$word $n file${if (n == 1) "" else "s"}"
    }

    var summary = parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    if (commonDir.isNotBlank()) summary += " in $commonDir"
    return summary
}

data class ChangesUiState(
    val repo: RepoEntity? = null,
    val currentBranch: String = "",
    val staged: List<GitFileEntry> = emptyList(),
    val unstaged: List<GitFileEntry> = emptyList(),
    val commitMessage: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val statusLine: String = "",
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val message: String? = null,
    val hasConflicts: Boolean = false,
    val recentMessages: List<String> = emptyList(),
    val commitTemplate: CommitPrefs.Template = CommitPrefs.Template(),
    /** True once we know for sure the current branch has no remote-tracking branch set up —
     *  null means "not checked yet", not "has upstream", so the nudge doesn't flash on then
     *  off while the repo is still loading. */
    val hasUpstream: Boolean? = null,
    /** Latest Actions run for this repo, if it has a GitHub remote and a token attached —
     *  null just means "not known yet / not applicable", not "no runs". */
    val ciRun: WorkflowRun? = null,
    val ciApplicable: Boolean = false,
    /** Bumped once per successful push — the Composable watches this to fire the
     *  "Pushed — checking CI…" snackbar (spec's "auto-prompt after push" trigger point),
     *  separate from [message] since it needs its own action button (View → Actions screen)
     *  rather than being just a plain text toast. */
    val pushSuccessTick: Int = 0,
)

class ChangesViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _state = MutableStateFlow(ChangesUiState())
    val uiState: StateFlow<ChangesUiState> = _state.asStateFlow()

    private var git: Git? = null
    private var repoId: Long = -1

    fun load(id: Long) {
        repoId = id
        viewModelScope.launch {
            val repo = repoRepo.getById(id) ?: return@launch
            _state.value = _state.value.copy(
                repo = repo, isLoading = true,
                recentMessages = CommitPrefs.recentMessages(appRef, id),
                commitTemplate = CommitPrefs.template(appRef, id),
            )
            openAndRefresh(repo)
            refreshCiStatus(repo)
        }
    }

    fun refresh() { viewModelScope.launch { _state.value.repo?.let { openAndRefresh(it) } } }

    private suspend fun openAndRefresh(repo: RepoEntity) {
        val result = GitEngine.openRepo(repo.fullSavePath)
        if (result is GitResult.Error) { err("Open failed: ${result.message}"); return }
        val g = (result as GitResult.Success).data
        git = g

        val branch = GitEngine.getCurrentBranch(g).getOrNull() ?: ""
        val ab = GitEngine.getAheadBehind(g).getOrNull() ?: Pair(0, 0)
        val upstream = GitEngine.hasUpstream(g).getOrNull()
        val statusLine = buildStatusLine(g)

        when (val sr = GitEngine.getStatus(g)) {
            is GitResult.Error -> err("Status failed: ${sr.message}")
            is GitResult.Success -> {
                val (stg, unstg) = sr.data.partition { it.staged }
                _state.value = _state.value.copy(
                    staged = stg, unstaged = unstg, isLoading = false,
                    currentBranch = branch, ahead = ab.first, behind = ab.second,
                    statusLine = statusLine, hasUpstream = upstream,
                    hasConflicts = unstg.any { it.status == com.willykez.repomaster.git.GitFileStatus.CONFLICTED },
                )
            }
        }
    }

    private suspend fun buildStatusLine(g: Git): String {
        val head = GitEngine.getHeadCommit(g).getOrNull()
        return if (head != null) "${head.shortSha} ${head.message}" else "No commits yet"
    }

    fun onCommitMessageChanged(v: String) { _state.value = _state.value.copy(commitMessage = v) }

    fun stageFile(e: GitFileEntry) = gitOp { g -> GitEngine.stageFile(g, e.path) }
    fun unstageFile(e: GitFileEntry) = gitOp { g -> GitEngine.unstageFile(g, e.path) }
    fun stageAll() = gitOp { g -> GitEngine.stageAll(g) }
    fun unstageAll() = gitOp { g ->
        _state.value.staged.forEach { GitEngine.unstageFile(g, it.path) }
        GitResult.Success(Unit)
    }
    fun discardFile(e: GitFileEntry) = gitOp { g -> GitEngine.discardFile(g, e.path) }
    fun discardAll() = gitOp { g -> GitEngine.discardAllChanges(g) }

    fun stageAllAndCommit() {
        val msg = _state.value.commitMessage
        if (msg.isBlank()) { err("Write a commit message first"); return }
        gitOp { g ->
            val r1 = GitEngine.stageAll(g)
            if (r1 is GitResult.Error) return@gitOp r1
            GitEngine.commit(g, msg, authorName(), "repomaster@local")
        }
    }

    fun commit() {
        val msg = _state.value.commitMessage
        if (msg.isBlank()) { err("Write a commit message first"); return }
        if (_state.value.staged.isEmpty()) { err("Stage at least one file first"); return }
        gitOp { g ->
            val r = GitEngine.commit(g, msg, authorName(), "repomaster@local")
            if (r is GitResult.Success) {
                _state.value = _state.value.copy(commitMessage = "")
                CommitPrefs.recordMessage(appRef, repoId, msg)
                _state.value = _state.value.copy(recentMessages = CommitPrefs.recentMessages(appRef, repoId))
            }
            r
        }
    }

    /**
     * Fills the commit message from whatever's actually changed — a heuristic summary, not
     * an AI-written one, so it's instant and needs no network or credential. Prefers
     * summarizing [staged] (what would actually be committed right now); falls back to
     * [unstaged] only so there's still something to preview before Stage+Commit+Push, which
     * stages everything first anyway.
     */
    fun generateCommitMessage() {
        val entries = _state.value.staged.ifEmpty { _state.value.unstaged }
        if (entries.isEmpty()) { err("No changes to summarize"); return }
        val body = buildCommitMessageSummary(entries)
        _state.value = _state.value.copy(commitMessage = _state.value.commitTemplate.apply(body))
    }

    /** Picks a message straight from history — no template re-application, since a message
     *  pulled from history was already whatever it was when it got committed the first time. */
    fun applyRecentMessage(message: String) { _state.value = _state.value.copy(commitMessage = message) }

    fun setCommitTemplate(prefix: String, footer: String) {
        val repo = _state.value.repo ?: return
        CommitPrefs.setTemplate(appRef, repo.id, prefix, footer)
        _state.value = _state.value.copy(commitTemplate = CommitPrefs.Template(prefix, footer))
    }

    /**
     * One tap instead of three separate screens of taps: stages everything, commits with
     * whatever's in the message box, then pushes — the common "just ship it" path when
     * there's nothing to review file-by-file. Stops and reports at whichever step actually
     * fails rather than assuming the whole chain succeeded; [openAndRefresh] still runs
     * afterward either way so the file list reflects reality even on a partial failure
     * (e.g. staged + committed locally, but the push itself failed).
     */
    fun stageCommitAndPush() {
        val msg = _state.value.commitMessage
        val repo = _state.value.repo
        val g = git
        if (repo == null || g == null) { err("Repo not open"); return }
        if (_state.value.staged.isEmpty() && _state.value.unstaged.isEmpty()) { err("Nothing to commit"); return }
        if (msg.isBlank()) { err("Write a commit message first — or tap Generate"); return }

        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            var failure: String? = null

            val stageResult = GitEngine.stageAll(g)
            if (stageResult is GitResult.Error) failure = stageResult.message

            if (failure == null) {
                val commitResult = GitEngine.commit(g, msg, authorName(), "repomaster@local")
                if (commitResult is GitResult.Error) failure = commitResult.message
                else {
                    _state.value = _state.value.copy(commitMessage = "")
                    CommitPrefs.recordMessage(appRef, repoId, msg)
                    _state.value = _state.value.copy(recentMessages = CommitPrefs.recentMessages(appRef, repoId))
                }
            }

            if (failure == null) {
                val cred = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
                when (val pushResult = GitEngine.push(g, credential = cred)) {
                    is GitResult.Error -> {
                        repoRepo.markError(repo.id, pushResult.message)
                        failure = pushResult.message
                    }
                    is GitResult.Success -> {
                        repoRepo.markSyncSuccess(repo.id)
                        _state.value = _state.value.copy(pushSuccessTick = _state.value.pushSuccessTick + 1)
                    }
                }
            }

            openAndRefresh(repo)
            refreshCiStatus(repo)
            _state.value = _state.value.copy(isWorking = false, message = failure ?: _state.value.message)
        }
    }

    fun amendCommit(newMessage: String) {
        if (newMessage.isBlank()) { err("Message required"); return }
        gitOp { g -> GitEngine.commit(g, newMessage, authorName(), "repomaster@local", amend = true) }
    }

    fun squash(n: Int, message: String) {
        if (message.isBlank()) { err("Write a commit message first"); return }
        gitOp { g -> GitEngine.squashLastN(g, n, message, authorName(), "repomaster@local") }
    }

    fun cherryPick(sha: String) {
        if (sha.isBlank()) { err("Enter a commit SHA"); return }
        gitOp { g -> GitEngine.cherryPick(g, sha) }
    }

    fun fetch() = remoteOp { g, cred -> GitEngine.fetch(g, credential = cred) }
    fun pullMerge() = remoteOp { g, cred -> GitEngine.pullMerge(g, cred) }
    fun pullRebase() = remoteOp { g, cred -> GitEngine.pullRebase(g, cred) }
    fun pullForce() = remoteOp { g, cred -> GitEngine.pullForce(g, cred) }
    fun push() = remoteOp(isPush = true) { g, cred -> GitEngine.push(g, credential = cred) }
    fun pushForce() = remoteOp(isPush = true) { g, cred -> GitEngine.push(g, force = true, credential = cred) }
    fun syncMerge() = remoteOp { g, cred -> GitEngine.syncMerge(g, cred) }
    fun syncRebase() = remoteOp { g, cred -> GitEngine.syncRebase(g, cred) }

    fun resetSoft() = gitOp { g -> GitEngine.resetSoft(g) }
    fun resetMixed() = gitOp { g -> GitEngine.resetMixed(g) }
    fun resetHard() = gitOp { g -> GitEngine.resetHard(g) }

    /**
     * Checks the latest GitHub Actions run for this repo, if it has a GitHub remote and a
     * credential with a token attached. Silent on failure (no snackbar, no error state) —
     * this is a nice-to-have status chip, not a git operation the person is waiting on, so
     * a rate limit or network blip here shouldn't interrupt anything.
     */
    private fun refreshCiStatus(repo: RepoEntity) {
        val fullName = githubFullNameFromUrl(repo.cloneUrl)
        if (fullName == null) {
            _state.value = _state.value.copy(ciApplicable = false, ciRun = null)
            return
        }
        viewModelScope.launch {
            val token = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId)?.token else null
            if (token.isNullOrBlank()) {
                _state.value = _state.value.copy(ciApplicable = false, ciRun = null)
                return@launch
            }
            _state.value = _state.value.copy(ciApplicable = true)
            when (val r = GitHubApi.listWorkflowRuns(fullName, token, perPage = 1)) {
                is GitHubResult.Success -> _state.value = _state.value.copy(ciRun = r.data.firstOrNull())
                is GitHubResult.Error -> Unit
            }
        }
    }

    /** Manual re-check, e.g. from tapping the CI chip's refresh affordance. */
    fun refreshCiStatus() { _state.value.repo?.let { refreshCiStatus(it) } }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    /** For snackbar messages triggered purely from the UI layer (e.g. "Branch name copied"
     * after a clipboard action) that don't come from a git operation result. */
    fun showTransientMessage(msg: String) { _state.value = _state.value.copy(message = msg) }

    private fun authorName(): String {
        return _state.value.repo?.let { r ->
            if (r.credentialId != 0L) null else null
        } ?: "Repo Master"
    }

    private fun gitOp(block: suspend (Git) -> GitResult<*>) {
        val g = git ?: run { err("Repo not open"); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            when (val r = block(g)) {
                is GitResult.Error -> err(r.message)
                is GitResult.Success -> {
                    val msg = when (val d = r.data) {
                        is String -> d
                        is Unit -> "Done"
                        else -> "Done"
                    }
                    ok(msg)
                    _state.value.repo?.let { openAndRefresh(it) }
                }
            }
            _state.value = _state.value.copy(isWorking = false)
        }
    }

    private fun remoteOp(
        isPush: Boolean = false,
        block: suspend (Git, com.willykez.repomaster.data.repository.DecryptedCredential?) -> GitResult<*>,
    ) {
        val repo = _state.value.repo ?: run { err("Repo not loaded"); return }
        val g = git ?: run { err("Repo not open"); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            val cred = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
            when (val r = block(g, cred)) {
                is GitResult.Error -> {
                    repoRepo.markError(repo.id, r.message)
                    err(r.message)
                }
                is GitResult.Success -> {
                    repoRepo.markSyncSuccess(repo.id)
                    val msg = when (val d = r.data) { is String -> d; else -> "Done" }
                    if (isPush) {
                        _state.value = _state.value.copy(pushSuccessTick = _state.value.pushSuccessTick + 1)
                    } else {
                        ok(msg)
                    }
                    openAndRefresh(repo)
                    refreshCiStatus(repo)
                }
            }
            _state.value = _state.value.copy(isWorking = false)
        }
    }

    private fun err(msg: String) { _state.value = _state.value.copy(message = msg, isWorking = false) }
    private fun ok(msg: String) { _state.value = _state.value.copy(message = msg) }

    // squash helper exposed from GitEngine
    private suspend fun GitEngine.squashLastN(git: Git, n: Int, msg: String, name: String, email: String): GitResult<String> {
        val reset = resetSoft(git, "HEAD~$n")
        if (reset is GitResult.Error) return GitResult.Error(reset.message)
        return commit(git, msg, name, email)
    }

    override fun onCleared() { super.onCleared(); git?.close() }
}
