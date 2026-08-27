package com.willykez.repomaster.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.willykez.repomaster.App
import com.willykez.repomaster.data.AutomationPrefs
import com.willykez.repomaster.data.GitIdentityPrefs
import com.willykez.repomaster.git.GitEngine
import com.willykez.repomaster.git.GitResult
import com.willykez.repomaster.ui.screens.changes.buildCommitMessageSummary
import kotlinx.coroutines.flow.first

/**
 * Periodic background job. Two things happen per tracked repo, in order:
 *
 * 1. **Fetch** (always) — never pulls/merges. Deliberately fetch-only: auto-merging in the
 *    background risks a silent conflict landing in the working tree while the user isn't
 *    looking, with no chance to review it first. A manual Pull is still needed to actually
 *    bring changes in; this just means "you'll already know there's something to pull."
 *
 * 2. **Auto-commit & push** (opt-in per repo, via [AutomationPrefs]) — if this repo has the
 *    automation turned on *and* the working tree actually has changes, stages everything,
 *    commits with a heuristic message (same generator Changes' own "Generate" button uses,
 *    prefixed so it's obviously distinguishable in history from a manual commit), and pushes
 *    — but only if the repo already has an upstream configured. A repo with no upstream is
 *    skipped for the push step (commit still happens, staying purely local) rather than
 *    having a background worker silently create a new branch on the remote — that's a
 *    bigger, more surprising action than this feature is meant to take unattended.
 *
 * Posts at most two summary notifications per pass (via [SyncNotifier]) — new commits found,
 * and/or repos auto-committed — never one per repo.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val repos = app.repoRepository.allRepos.first() // one-shot read; this worker is headless
        val automatedRepoIds = AutomationPrefs.enabledRepoIds(applicationContext)

        var anyFailure = false
        val reposWithNewCommits = mutableListOf<Pair<Long, String>>()
        val reposAutoCommitted = mutableListOf<Pair<Long, String>>()

        for (repo in repos) {
            val credential = if (repo.credentialId != 0L) app.credentialRepository.getById(repo.credentialId) else null

            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> {
                    anyFailure = true
                    app.repoRepository.markError(repo.id, opened.message)
                }
                is GitResult.Success -> {
                    val git = opened.data

                    when (val result = GitEngine.fetchAndCountUpdates(git, credential = credential)) {
                        is GitResult.Success -> {
                            app.repoRepository.markSyncSuccess(repo.id)
                            if (result.data > 0) reposWithNewCommits.add(repo.id to repo.name)
                        }
                        is GitResult.Error -> {
                            anyFailure = true
                            app.repoRepository.markError(repo.id, result.message)
                        }
                    }

                    if (repo.id in automatedRepoIds) {
                        val didCommit = runAutomation(repo.id, repo.name, git, credential)
                        if (didCommit) reposAutoCommitted.add(repo.id to repo.name)
                    }

                    git.close()
                }
            }
        }

        SyncNotifier.notifyNewCommits(applicationContext, reposWithNewCommits)
        SyncNotifier.notifyAutomation(applicationContext, reposAutoCommitted)

        // Retry later on failure (e.g. a transient network issue) rather than giving up for
        // the rest of the scheduled period; repos that did fetch successfully still count.
        return if (anyFailure) Result.retry() else Result.success()
    }

    /** @return true if a commit actually happened (regardless of whether the push step also
     *  succeeded) — used to decide whether this repo shows up in the automation notification. */
    private suspend fun runAutomation(
        repoId: Long, repoName: String, git: org.eclipse.jgit.api.Git,
        credential: com.willykez.repomaster.data.repository.DecryptedCredential?,
    ): Boolean {
        val status = GitEngine.getStatus(git)
        val entries = (status as? GitResult.Success)?.data ?: return false
        if (entries.isEmpty()) return false // nothing to automate — clean working tree

        if (GitEngine.stageAll(git) is GitResult.Error) return false

        // "Auto-sync: " prefix is deliberate — anyone reading history later should be able to
        // tell at a glance which commits a human made versus which ones this feature made
        // unattended, the same way a bot account's commits are visually distinct on GitHub.
        val message = "Auto-sync: " + buildCommitMessageSummary(entries)
        val name = GitIdentityPrefs.currentName(applicationContext)
        val email = GitIdentityPrefs.currentEmail(applicationContext)
        if (GitEngine.commit(git, message, name, email) is GitResult.Error) return false

        val hasUpstream = (GitEngine.hasUpstream(git) as? GitResult.Success)?.data ?: false
        if (hasUpstream) {
            GitEngine.push(git, credential = credential) // failure here doesn't undo the local commit
        }
        return true
    }

    companion object {
        const val WORK_NAME = "repomaster_background_sync"
    }
}
