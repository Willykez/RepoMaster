package com.willykez.repomaster.git

import com.willykez.repomaster.data.repository.DecryptedCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File

object GitEngine {

    private fun DecryptedCredential.toProvider() =
        UsernamePasswordCredentialsProvider(username, token)

    private suspend fun <T> io(block: () -> T): GitResult<T> =
        withContext(Dispatchers.IO) { gitTry(block) }

    // ── open / clone ──────────────────────────────────────────────────────────

    suspend fun openRepo(path: String): GitResult<Git> = io { Git.open(File(path)) }

    suspend fun cloneRepo(
        url: String, localPath: String, branch: String = "",
        shallow: Boolean = false, depth: Int = 1,
        credential: DecryptedCredential? = null,
    ): GitResult<Git> = io {
        val cmd = Git.cloneRepository().setURI(url).setDirectory(File(localPath))
        if (branch.isNotBlank()) cmd.setBranch(branch)
        if (shallow) cmd.setDepth(depth)
        credential?.let { cmd.setCredentialsProvider(it.toProvider()) }
        cmd.call()
    }

    // ── status / staging ──────────────────────────────────────────────────────

    suspend fun getStatus(git: Git): GitResult<List<GitFileEntry>> = io {
        val s = git.status().call()
        buildList {
            s.added.forEach     { add(GitFileEntry(it, GitFileStatus.ADDED,      true)) }
            s.changed.forEach   { add(GitFileEntry(it, GitFileStatus.MODIFIED,   true)) }
            s.removed.forEach   { add(GitFileEntry(it, GitFileStatus.DELETED,    true)) }
            s.untracked.forEach { add(GitFileEntry(it, GitFileStatus.ADDED,      false)) }
            s.modified.forEach  { add(GitFileEntry(it, GitFileStatus.MODIFIED,   false)) }
            s.missing.forEach   { add(GitFileEntry(it, GitFileStatus.DELETED,    false)) }
            s.conflicting.forEach {
                removeAll { e -> e.path == it }
                add(GitFileEntry(it, GitFileStatus.CONFLICTED, false))
            }
        }
    }

    suspend fun stageFile(git: Git, path: String): GitResult<Unit> = io {
        val onDisk = File(git.repository.workTree, path).exists()
        if (onDisk) {
            git.add().addFilepattern(path).call()
        } else {
            // AddCommand is a no-op for a path that no longer exists on disk — it only
            // ever adds *content*, it never removes an index entry. Staging a deletion
            // needs RmCommand instead, or the removal never reaches a commit (and never
            // reaches the remote on push, even though the file is genuinely gone locally).
            git.rm().addFilepattern(path).call()
        }
        Unit
    }

    suspend fun stageAll(git: Git): GitResult<Unit> = io {
        git.add().addFilepattern(".").call() // new + modified content
        git.add().setUpdate(true).addFilepattern(".").call() // deletions of tracked files
        Unit
    }

    /** Deletes [path] from the working tree AND stages the removal in the same step —
     * backs the file explorer's Delete action. A plain filesystem delete followed by a
     * later "Stage" tap would hit the same AddCommand no-op described above; RmCommand
     * does both halves atomically, so a deleted file is immediately ready to commit/push. */
    suspend fun removeFile(git: Git, path: String): GitResult<Unit> = io {
        git.rm().addFilepattern(path).call(); Unit
    }

    suspend fun unstageFile(git: Git, path: String): GitResult<Unit> = io {
        git.reset().addPath(path).call(); Unit
    }

    suspend fun discardFile(git: Git, path: String): GitResult<Unit> = io {
        git.checkout().addPath(path).call(); Unit
    }

    suspend fun discardAllChanges(git: Git): GitResult<Unit> = io {
        git.checkout().setAllPaths(true).call(); Unit
    }

    // ── commit ────────────────────────────────────────────────────────────────

    suspend fun commit(
        git: Git, message: String, name: String, email: String,
        amend: Boolean = false,
    ): GitResult<String> = io {
        val id = PersonIdent(name, email)
        git.commit().setMessage(message).setAuthor(id).setCommitter(id)
            .setAmend(amend).call().name()
    }

    // ── fetch ─────────────────────────────────────────────────────────────────

    suspend fun fetch(
        git: Git, remote: String = "origin", prune: Boolean = true,
        credential: DecryptedCredential? = null,
    ): GitResult<String> = io {
        val r = git.fetch().setRemote(remote).setRemoveDeletedRefs(prune)
            .also { credential?.let { c -> it.setCredentialsProvider(c.toProvider()) } }
            .call()
        if (r.advertisedRefs.isEmpty()) "Already up to date" else "Fetched from $remote"
    }

    /** Same fetch as above, but also reports how many remote-tracking refs actually moved —
     *  what [SyncWorker] uses to decide whether a repo genuinely has new commits worth a
     *  notification, versus a fetch that ran and found nothing new. JGit's own
     *  `trackingRefUpdates` is the right signal here (rather than e.g. comparing ahead/behind
     *  counts before and after): it reflects every branch that moved, not just the current
     *  one, and a fetch with zero tracking ref updates really did find nothing new. */
    suspend fun fetchAndCountUpdates(
        git: Git, remote: String = "origin", prune: Boolean = true,
        credential: DecryptedCredential? = null,
    ): GitResult<Int> = io {
        val r = git.fetch().setRemote(remote).setRemoveDeletedRefs(prune)
            .also { credential?.let { c -> it.setCredentialsProvider(c.toProvider()) } }
            .call()
        r.trackingRefUpdates.size
    }

    // ── pull ──────────────────────────────────────────────────────────────────

    suspend fun pullMerge(git: Git, credential: DecryptedCredential? = null): GitResult<String> = io {
        val cmd = git.pull().setRebase(false)
        credential?.let { cmd.setCredentialsProvider(it.toProvider()) }
        val r = cmd.call()
        if (!r.isSuccessful)
            throw IllegalStateException("Pull(Merge) failed: ${r.mergeResult?.mergeStatus ?: "unknown"}")
        r.mergeResult?.mergeStatus?.toString() ?: "Already up to date"
    }

    suspend fun pullRebase(git: Git, credential: DecryptedCredential? = null): GitResult<String> = io {
        val cmd = git.pull().setRebase(true)
        credential?.let { cmd.setCredentialsProvider(it.toProvider()) }
        val r = cmd.call()
        if (!r.isSuccessful)
            throw IllegalStateException("Pull(Rebase) failed: ${r.rebaseResult?.status ?: "unknown"}")
        r.rebaseResult?.status?.toString() ?: "Already up to date"
    }

    suspend fun pullForce(git: Git, credential: DecryptedCredential? = null): GitResult<String> = io {
        val branch = git.repository.branch
        git.fetch().setRemote("origin")
            .also { credential?.let { c -> it.setCredentialsProvider(c.toProvider()) } }.call()
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/$branch").call()
        "Force-reset to origin/$branch"
    }

    // ── push ──────────────────────────────────────────────────────────────────

    suspend fun push(
        git: Git, remote: String = "origin", force: Boolean = false,
        credential: DecryptedCredential? = null,
    ): GitResult<String> = io {
        val cmd = git.push().setRemote(remote).setForce(force)
        credential?.let { cmd.setCredentialsProvider(it.toProvider()) }
        val results = cmd.call().toList()
        val rejected = results.flatMap { pr ->
            pr.remoteUpdates.filter {
                it.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK &&
                it.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
            }
        }
        if (rejected.isNotEmpty())
            throw IllegalStateException(rejected.joinToString("; ") { "${it.remoteName}: ${it.status}" })
        if (force) "Force pushed to $remote" else "Pushed to $remote"
    }

    suspend fun pushDeleteBranch(
        git: Git, remote: String = "origin", branch: String,
        credential: DecryptedCredential? = null,
    ): GitResult<String> = io {
        val cmd = git.push().setRemote(remote).setRefSpecs(RefSpec(":refs/heads/$branch"))
        credential?.let { cmd.setCredentialsProvider(it.toProvider()) }
        cmd.call(); "Deleted remote branch: $branch"
    }

    // ── sync ──────────────────────────────────────────────────────────────────

    suspend fun syncMerge(git: Git, credential: DecryptedCredential? = null): GitResult<String> {
        val pull = pullMerge(git, credential)
        if (pull is GitResult.Error) return pull
        val push = push(git, credential = credential)
        if (push is GitResult.Error) return push
        return GitResult.Success("Sync(Merge) done")
    }

    suspend fun syncRebase(git: Git, credential: DecryptedCredential? = null): GitResult<String> {
        val pull = pullRebase(git, credential)
        if (pull is GitResult.Error) return pull
        val push = push(git, credential = credential)
        if (push is GitResult.Error) return push
        return GitResult.Success("Sync(Rebase) done")
    }

    // ── branches ──────────────────────────────────────────────────────────────

    /** Resolves any ref (branch, tag, HEAD, SHA) to its full commit SHA. Used right before a
     *  destructive delete — capturing the SHA first is what makes the undo-snackbar pattern
     *  in Branches/Tags honest rather than a fake "undo" that can't actually restore anything:
     *  recreating a branch/tag pointed at this exact SHA afterward genuinely reconstructs it. */
    suspend fun resolveRef(git: Git, ref: String): GitResult<String> = io {
        git.repository.resolve(ref)?.name ?: throw IllegalStateException("Couldn't resolve $ref")
    }

    suspend fun listBranches(git: Git): GitResult<List<BranchInfo>> = io {
        val current = git.repository.branch
        val local = git.branchList().call()
        val remote = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
        buildList {
            local.forEach { ref ->
                val n = ref.name.removePrefix("refs/heads/")
                val ts = BranchTrackingStatus.of(git.repository, n)
                add(BranchInfo(n, ref.name, false, n == current, ts?.aheadCount ?: 0, ts?.behindCount ?: 0))
            }
            remote.forEach { ref ->
                val n = ref.name.removePrefix("refs/remotes/")
                if (!n.endsWith("/HEAD")) add(BranchInfo(n, ref.name, true))
            }
        }
    }

    suspend fun createBranch(git: Git, name: String, startPoint: String = "HEAD"): GitResult<Unit> = io {
        git.branchCreate().setName(name).setStartPoint(startPoint).call(); Unit
    }

    suspend fun checkoutBranch(git: Git, name: String, create: Boolean = false): GitResult<Unit> = io {
        git.checkout().setName(name).setCreateBranch(create).call(); Unit
    }

    suspend fun checkoutRemoteBranch(git: Git, remoteBranch: String): GitResult<Unit> = io {
        val local = remoteBranch.substringAfter("/")
        git.checkout().setName(local).setCreateBranch(true).setStartPoint(remoteBranch).call(); Unit
    }

    suspend fun deleteBranch(git: Git, name: String, force: Boolean = false): GitResult<Unit> = io {
        git.branchDelete().setBranchNames(name).setForce(force).call(); Unit
    }

    suspend fun renameBranch(git: Git, old: String, new: String): GitResult<Unit> = io {
        git.branchRename().setOldName(old).setNewName(new).call(); Unit
    }

    suspend fun mergeBranch(git: Git, branch: String): GitResult<String> = io {
        val ref = git.repository.findRef("refs/heads/$branch")
            ?: throw IllegalStateException("Branch not found: $branch")
        val r = git.merge().include(ref).setStrategy(org.eclipse.jgit.merge.MergeStrategy.RECURSIVE).call()
        when (r.mergeStatus) {
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> "Already up to date"
            MergeResult.MergeStatus.FAST_FORWARD -> "Fast-forward"
            MergeResult.MergeStatus.MERGED -> "Merged $branch"
            MergeResult.MergeStatus.CONFLICTING ->
                throw IllegalStateException("Conflicts: ${r.conflicts?.keys?.joinToString()}")
            else -> throw IllegalStateException("Merge failed: ${r.mergeStatus}")
        }
    }

    suspend fun rebaseBranch(git: Git, upstream: String): GitResult<String> = io {
        val r = git.rebase().setUpstream(upstream).call()
        when (r.status) {
            RebaseResult.Status.OK, RebaseResult.Status.UP_TO_DATE,
            RebaseResult.Status.FAST_FORWARD -> "Rebase OK"
            RebaseResult.Status.CONFLICTS ->
                throw IllegalStateException("Rebase conflicts — resolve manually")
            else -> throw IllegalStateException("Rebase failed: ${r.status}")
        }
    }

    // ── stash ─────────────────────────────────────────────────────────────────

    suspend fun stashSave(git: Git, message: String = "", includeUntracked: Boolean = true): GitResult<String> = io {
        git.stashCreate()
            .setWorkingDirectoryMessage(message.ifBlank { "Repo Master stash" })
            .setIncludeUntracked(includeUntracked)
            .call()?.name ?: "stash created"
    }

    suspend fun listStashes(git: Git): GitResult<List<StashInfo>> = io {
        git.stashList().call().mapIndexed { i, c ->
            StashInfo(i, c.shortMessage, c.name, c.authorIdent.name, c.authorIdent.`when`.time)
        }
    }

    suspend fun stashApply(git: Git, stashRef: String): GitResult<Unit> = io {
        git.stashApply().setStashRef(stashRef).call(); Unit
    }

    suspend fun stashPop(git: Git): GitResult<Unit> = io {
        git.stashApply().setStashRef("stash@{0}").call()
        git.stashDrop().setStashRef(0).call(); Unit
    }

    suspend fun stashDrop(git: Git, index: Int): GitResult<Unit> = io {
        git.stashDrop().setStashRef(index).call(); Unit
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    suspend fun resetSoft(git: Git, ref: String = "HEAD~1"): GitResult<Unit> = io {
        git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(ref).call(); Unit
    }

    suspend fun resetMixed(git: Git, ref: String = "HEAD~1"): GitResult<Unit> = io {
        git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(ref).call(); Unit
    }

    suspend fun resetHard(git: Git, ref: String = "HEAD"): GitResult<Unit> = io {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(ref).call(); Unit
    }

    // ── log ───────────────────────────────────────────────────────────────────

    suspend fun getLog(git: Git, maxCount: Int = 200, branch: String? = null): GitResult<List<CommitInfo>> = io {
        val cmd = git.log().setMaxCount(maxCount)
        if (!branch.isNullOrBlank()) {
            val ref = git.repository.findRef("refs/heads/$branch")
                ?: git.repository.findRef("refs/remotes/$branch")
            if (ref != null) cmd.add(ref.objectId)
        }
        cmd.call().map { c ->
            CommitInfo(c.name, c.name.take(7), c.shortMessage, c.fullMessage,
                c.authorIdent.name, c.authorIdent.emailAddress,
                c.authorIdent.`when`.time, c.committerIdent.name, c.parentCount,
                parentShas = (0 until c.parentCount).map { i -> c.getParent(i).name })
        }
    }

    // ── diff ──────────────────────────────────────────────────────────────────

    suspend fun getDiff(git: Git, path: String, staged: Boolean): GitResult<String> = io {
        val out = ByteArrayOutputStream()
        val fmt = org.eclipse.jgit.diff.DiffFormatter(out)
        fmt.setRepository(git.repository); fmt.setContext(3)
        val reader = git.repository.newObjectReader()
        if (staged) {
            val headId = git.repository.resolve("HEAD")
            val oldTree: org.eclipse.jgit.treewalk.AbstractTreeIterator =
                if (headId != null) {
                    val walk = RevWalk(git.repository)
                    val commit = walk.parseCommit(headId)
                    CanonicalTreeParser().also { it.reset(reader, commit.tree); walk.dispose() }
                } else EmptyTreeIterator()
            val newTree = CanonicalTreeParser()
            newTree.reset(reader, git.repository.readDirCache()
                .writeTree(git.repository.newObjectInserter()))
            fmt.format(oldTree, newTree)
        } else {
            fmt.format(git.diff().setPathFilter(
                org.eclipse.jgit.treewalk.filter.PathFilter.create(path)).call())
        }
        fmt.flush()
        out.toString(Charsets.UTF_8.name()).ifBlank { "(no diff)" }
    }

    suspend fun getCommitDiff(git: Git, sha: String): GitResult<String> = io {
        val out = ByteArrayOutputStream()
        val fmt = org.eclipse.jgit.diff.DiffFormatter(out)
        fmt.setRepository(git.repository); fmt.setContext(3)
        val reader = git.repository.newObjectReader()
        val walk = RevWalk(git.repository)
        val commit = walk.parseCommit(git.repository.resolve(sha))
        val newTree = CanonicalTreeParser().also { it.reset(reader, commit.tree) }
        val oldTree: org.eclipse.jgit.treewalk.AbstractTreeIterator =
            if (commit.parentCount > 0) {
                val p = walk.parseCommit(commit.getParent(0))
                CanonicalTreeParser().also { it.reset(reader, p.tree) }
            } else EmptyTreeIterator()
        fmt.format(oldTree, newTree); fmt.flush(); walk.dispose()
        out.toString(Charsets.UTF_8.name()).ifBlank { "(empty diff)" }
    }

    /** Blame for [path] — which commit/author last touched each line. Only meaningful for
     * text files; a binary file will either throw or produce garbage, so the UI should
     * steer clear of calling this on anything that isn't source/text. */
    suspend fun getBlame(git: Git, path: String): GitResult<List<BlameLine>> = io {
        val result = git.blame()
            .setFilePath(path)
            .setTextComparator(org.eclipse.jgit.diff.RawTextComparator.WS_IGNORE_ALL)
            .call() ?: return@io emptyList()

        val lineCount = result.resultContents.size()
        (0 until lineCount).map { i ->
            val commit = result.getSourceCommit(i)
            BlameLine(
                lineNumber = i + 1,
                content = result.resultContents.getString(i),
                commitSha = commit?.name ?: "",
                shortSha = commit?.name?.take(7) ?: "?????",
                authorName = commit?.authorIdent?.name ?: "Unknown",
                authorTime = commit?.authorIdent?.`when`?.time ?: 0L,
            )
        }
    }

    // ── remotes ───────────────────────────────────────────────────────────────

    suspend fun listRemotes(git: Git): GitResult<List<RemoteInfo>> = io {
        RemoteConfig.getAllRemoteConfigs(git.repository.config).map { rc ->
            RemoteInfo(rc.name, rc.urIs.firstOrNull()?.toString() ?: "",
                rc.pushURIs.firstOrNull()?.toString() ?: rc.urIs.firstOrNull()?.toString() ?: "")
        }
    }

    suspend fun addRemote(git: Git, name: String, url: String): GitResult<Unit> = io {
        git.remoteAdd().setName(name).setUri(URIish(url)).call(); Unit
    }

    suspend fun removeRemote(git: Git, name: String): GitResult<Unit> = io {
        git.remoteRemove().setRemoteName(name).call(); Unit
    }

    suspend fun setRemoteUrl(git: Git, name: String, url: String): GitResult<Unit> = io {
        git.remoteSetUrl().setRemoteName(name).setRemoteUri(URIish(url)).call(); Unit
    }

    // ── tags ──────────────────────────────────────────────────────────────────

    suspend fun listTags(git: Git): GitResult<List<TagInfo>> = io {
        git.tagList().call().map { ref ->
            TagInfo(ref.name.removePrefix("refs/tags/"), ref.objectId?.name ?: "")
        }
    }

    suspend fun createTag(git: Git, name: String, message: String = "", targetSha: String? = null): GitResult<Unit> = io {
        val cmd = git.tag().setName(name)
        if (message.isNotBlank()) cmd.setMessage(message).setAnnotated(true) else cmd.setAnnotated(false)
        if (targetSha != null) {
            RevWalk(git.repository).use { walk ->
                cmd.setObjectId(walk.parseAny(git.repository.resolve(targetSha)))
            }
        }
        cmd.call(); Unit
    }

    suspend fun deleteTag(git: Git, name: String): GitResult<Unit> = io {
        git.tagDelete().setTags(name).call(); Unit
    }

    suspend fun pushTags(git: Git, credential: DecryptedCredential? = null): GitResult<Unit> = io {
        val cmd = git.push().setPushTags()
        credential?.let { cmd.setCredentialsProvider(it.toProvider()) }
        cmd.call(); Unit
    }

    // ── .gitignore ────────────────────────────────────────────────────────────

    suspend fun readGitignore(git: Git): GitResult<String> = io {
        val f = File(git.repository.workTree, ".gitignore")
        if (f.exists()) f.readText() else ""
    }

    suspend fun writeGitignore(git: Git, content: String): GitResult<Unit> = io {
        File(git.repository.workTree, ".gitignore").writeText(content); Unit
    }

    // ── cherry-pick ───────────────────────────────────────────────────────────

    suspend fun cherryPick(git: Git, sha: String): GitResult<String> = io {
        val id = git.repository.resolve(sha) ?: throw IllegalStateException("Commit not found: $sha")
        val walk = RevWalk(git.repository)
        val commit = walk.parseCommit(id)
        val r = git.cherryPick().include(commit).call()
        walk.dispose()
        when (r.status) {
            org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.OK -> "Cherry-picked ${sha.take(7)}"
            org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.CONFLICTING ->
                throw IllegalStateException("Cherry-pick conflict — resolve manually")
            else -> throw IllegalStateException("Cherry-pick failed: ${r.status}")
        }
    }

    // ── repo info ─────────────────────────────────────────────────────────────

    suspend fun getCurrentBranch(git: Git): GitResult<String> = io { git.repository.branch }

    suspend fun getAheadBehind(git: Git): GitResult<Pair<Int, Int>> = io {
        val ts = BranchTrackingStatus.of(git.repository, git.repository.branch)
        Pair(ts?.aheadCount ?: 0, ts?.behindCount ?: 0)
    }

    /** Whether the current branch has an upstream (remote-tracking) branch configured at
     *  all — distinct from ahead/behind being zero, which is also true for a branch that's
     *  never been pushed. Used for the Changes screen's push-protection nudge: pushing a
     *  branch called main/master with no upstream yet configured is the exact situation
     *  where "am I about to create/overwrite something unexpected on the remote?" is worth
     *  a beat of pause. */
    suspend fun hasUpstream(git: Git): GitResult<Boolean> = io {
        BranchTrackingStatus.of(git.repository, git.repository.branch) != null
    }

    suspend fun getHeadCommit(git: Git): GitResult<CommitInfo?> = io {
        val headId = git.repository.resolve("HEAD") ?: return@io null
        val walk = RevWalk(git.repository)
        val c = walk.parseCommit(headId)
        CommitInfo(c.name, c.name.take(7), c.shortMessage, c.fullMessage,
            c.authorIdent.name, c.authorIdent.emailAddress, c.authorIdent.`when`.time,
            c.committerIdent.name, c.parentCount).also { walk.dispose() }
    }

    // ── conflict resolution ──────────────────────────────────────────────────
    // JGit's merge/rebase/cherry-pick commands don't auto-abort on a conflict — they leave
    // conflict markers (<<<<<<< / ======= / >>>>>>>) written into the working-tree files and
    // mark the paths UNMERGED in the index (visible via getStatus()'s CONFLICTED entries).
    // These functions are what actually resolve that state, rather than just reporting it.

    /** True while a merge/rebase/cherry-pick is left half-finished waiting on conflict
     * resolution — drives whether the Changes screen shows the "Resolve Conflicts" banner. */
    suspend fun getRepositoryState(git: Git): GitResult<org.eclipse.jgit.lib.RepositoryState> = io {
        git.repository.repositoryState
    }

    suspend fun getConflictedPaths(git: Git): GitResult<List<String>> = io {
        git.status().call().conflicting.toList()
    }

    /** Keeps our (local/current-branch) version of a conflicted file and stages it. */
    suspend fun resolveConflictOurs(git: Git, path: String): GitResult<Unit> = io {
        git.checkout().setStage(org.eclipse.jgit.api.CheckoutCommand.Stage.OURS).addPath(path).call()
        git.add().addFilepattern(path).call()
        Unit
    }

    /** Keeps their (incoming) version of a conflicted file and stages it. */
    suspend fun resolveConflictTheirs(git: Git, path: String): GitResult<Unit> = io {
        git.checkout().setStage(org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS).addPath(path).call()
        git.add().addFilepattern(path).call()
        Unit
    }

    /** After manually editing a conflicted file (removing the markers by hand in the file
     * editor), call this to mark it resolved — stages it so it no longer counts as conflicting. */
    suspend fun markConflictResolved(git: Git, path: String): GitResult<Unit> = io {
        git.add().addFilepattern(path).call()
        Unit
    }

    /** Reads a conflicted file's raw content (including the conflict markers) so the UI
     * can show what's actually being decided between, without going through the diff view. */
    suspend fun readConflictedFile(git: Git, path: String): GitResult<String> = io {
        File(git.repository.workTree, path).readText()
    }

    /** Completes a merge once every conflict is resolved (no more CONFLICTED entries) —
     * a plain commit, since JGit's merge command already wrote MERGE_HEAD/MERGE_MSG. */
    suspend fun continueMerge(git: Git, message: String, name: String, email: String): GitResult<String> = io {
        val remaining = git.status().call().conflicting
        if (remaining.isNotEmpty()) {
            throw IllegalStateException("Still unresolved: ${remaining.joinToString()}")
        }
        val ident = PersonIdent(name, email)
        git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call().name
    }

    /** Backs out of an in-progress merge entirely — clears MERGE_HEAD/MERGE_MSG and resets
     * the working tree/index back to HEAD. JGit has no dedicated "merge --abort" call, so
     * this is the standard workaround: clear the merge state files, then hard-reset. */
    suspend fun abortMerge(git: Git): GitResult<Unit> = io {
        git.repository.writeMergeHeads(null)
        git.repository.writeMergeCommitMsg(null)
        git.reset().setMode(ResetCommand.ResetType.HARD).call()
        Unit
    }

    /** Continues an in-progress rebase once the current step's conflicts are all staged. */
    suspend fun continueRebase(git: Git): GitResult<String> = io {
        val remaining = git.status().call().conflicting
        if (remaining.isNotEmpty()) {
            throw IllegalStateException("Still unresolved: ${remaining.joinToString()}")
        }
        val r = git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE).call()
        when (r.status) {
            RebaseResult.Status.OK -> "Rebase continued"
            RebaseResult.Status.CONFLICTS ->
                throw IllegalStateException("Next commit also conflicts: ${git.status().call().conflicting.joinToString()}")
            else -> r.status.name
        }
    }

    /** Rebase does have a dedicated built-in abort, unlike merge. */
    suspend fun abortRebase(git: Git): GitResult<Unit> = io {
        git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call()
        Unit
    }
}
