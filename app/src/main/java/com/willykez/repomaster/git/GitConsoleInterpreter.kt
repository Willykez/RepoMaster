package com.willykez.repomaster.git

import com.willykez.repomaster.data.repository.DecryptedCredential
import org.eclipse.jgit.api.Git

/**
 * Interprets a typed git command (e.g. `git status`, `add .`, `commit -m "fix bug"`) and runs
 * it against this app's existing [GitEngine] — the same functions every screen already uses,
 * not a separate code path. This is deliberately NOT a full git CLI reimplementation: there's
 * no bundled `git` binary here (JGit is a from-scratch Java implementation, not a wrapper
 * around the real one), so exotic flag combinations and a handful of commands
 * ([UNSUPPORTED_COMMANDS]) genuinely aren't available. What's covered is the common subset
 * that shows up in day-to-day use — everything in the "USEFUL SHORTCUTS" / "QUICK DAILY
 * WORKFLOW" category of a typical git cheat sheet.
 *
 * Every command runs through the same [GitEngine] safety model as the rest of the app: no
 * shell execution, no arbitrary code, just parsed arguments mapped onto existing, already-
 * reviewed JGit calls. [dangerWarning] is checked by the caller *before* [run] for the small
 * set of genuinely destructive commands, so the console can show a confirmation prompt first —
 * the same commands a typical cheat sheet's own "IMPORTANT WARNING" section calls out as
 * "never blindly use."
 */
object GitConsoleInterpreter {

    val UNSUPPORTED_COMMANDS = setOf("reflog", "ls-files", "config", "clone", "init", "cherry-pick", "bisect", "submodule")

    /** Non-null means this command needs a confirmation prompt before [run] is called, with
     *  this as the warning text shown. */
    fun dangerWarning(rawCommand: String): String? {
        val normalized = rawCommand.trim().removePrefix("git ").trim()
        return when {
            Regex("""\breset\s+.*--hard""").containsMatchIn(normalized) ->
                "This discards every uncommitted change and moves the branch pointer — permanently, no undo."
            Regex("""\bclean\b.*-fd\b""").containsMatchIn(normalized) ->
                "This permanently deletes every untracked file AND directory in the working tree."
            Regex("""\bclean\b.*-f\b""").containsMatchIn(normalized) ->
                "This permanently deletes every untracked file in the working tree."
            Regex("""\bpush\b.*(--force\b|-f\b)""").containsMatchIn(normalized) ->
                "This overwrites remote history — anyone else's work built on top of the old history can be lost."
            Regex("""\bbranch\s+-D\b""").containsMatchIn(normalized) ->
                "Force-deletes the branch even if it has commits not merged anywhere else."
            else -> null
        }
    }

    /** Splits on whitespace but keeps a double-quoted phrase (a commit message, typically)
     *  together as one token. */
    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in input) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) { tokens.add(current.toString()); current.clear() }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    suspend fun run(git: Git, credential: DecryptedCredential?, authorName: String, authorEmail: String, rawCommand: String): String {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) return ""
        val tokens = tokenize(trimmed).toMutableList()
        if (tokens.firstOrNull() == "git") tokens.removeAt(0)
        if (tokens.isEmpty()) return "usage: git <command> [<args>]"
        val cmd = tokens.removeAt(0)
        val args = tokens

        if (cmd in UNSUPPORTED_COMMANDS) {
            return when (cmd) {
                "clone", "init" -> "'$cmd' isn't run from inside an already-open repo — use \"Clone by URL\" or Discover on the repo list instead."
                "config" -> "Git identity is set in Settings \u2192 Git identity, not from this console."
                else -> "'$cmd' isn't supported in this console yet."
            }
        }
        if (cmd == "blame") return "Use \"Blame\" on a file in the File Explorer for this \u2014 it renders per-line author/commit info, which doesn't translate well to plain text here."

        return try {
            when (cmd) {
                "status" -> cmdStatus(git, args)
                "diff" -> cmdDiff(git, args)
                "log" -> cmdLog(git, args)
                "branch" -> cmdBranch(git, args)
                "add" -> cmdAdd(git, args)
                "restore" -> cmdRestore(git, args)
                "rm" -> cmdRm(git, args)
                "mv" -> cmdMv(git, args)
                "commit" -> cmdCommit(git, args, authorName, authorEmail)
                "push" -> cmdPush(git, args, credential)
                "pull" -> cmdPull(git, args, credential)
                "fetch" -> cmdFetch(git, args, credential)
                "switch" -> cmdSwitch(git, args)
                "checkout" -> cmdCheckout(git, args)
                "merge" -> cmdMerge(git, args)
                "rebase" -> cmdRebase(git, args)
                "stash" -> cmdStash(git, args)
                "reset" -> cmdReset(git, args)
                "tag" -> cmdTag(git, args, credential)
                "remote" -> cmdRemote(git, args)
                "clean" -> cmdClean(git, args)
                else -> "git: '$cmd' is not recognized by this console \u2014 see the Help tab for what's supported."
            }
        } catch (e: Exception) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun statusChar(status: GitFileStatus) = when (status) {
        GitFileStatus.ADDED -> "A"
        GitFileStatus.MODIFIED -> "M"
        GitFileStatus.DELETED -> "D"
        GitFileStatus.RENAMED -> "R"
        GitFileStatus.TYPE_CHANGED -> "T"
        GitFileStatus.CONFLICTED -> "U"
    }

    private fun verbFor(status: GitFileStatus) = when (status) {
        GitFileStatus.ADDED -> "new file"
        GitFileStatus.MODIFIED -> "modified"
        GitFileStatus.DELETED -> "deleted"
        GitFileStatus.RENAMED -> "renamed"
        GitFileStatus.TYPE_CHANGED -> "typechange"
        GitFileStatus.CONFLICTED -> "conflicted"
    }

    private suspend fun cmdStatus(git: Git, args: List<String>): String {
        val short = "--short" in args || "-s" in args
        val entries = GitEngine.getStatus(git).getOrNull() ?: emptyList()

        if (short) {
            if (entries.isEmpty()) return ""
            return entries.joinToString("\n") { e ->
                val stagedCol = if (e.staged) statusChar(e.status) else " "
                val unstagedCol = if (!e.staged) statusChar(e.status) else " "
                "$stagedCol$unstagedCol ${e.path}"
            }
        }

        val branch = GitEngine.getCurrentBranch(git).getOrNull() ?: "?"
        val sb = StringBuilder("On branch $branch\n")
        val staged = entries.filter { it.staged }
        val unstaged = entries.filter { !it.staged }
        if (staged.isNotEmpty()) {
            sb.append("Changes to be committed:\n")
            staged.forEach { sb.append("\t${verbFor(it.status)}:   ${it.path}\n") }
        }
        if (unstaged.isNotEmpty()) {
            sb.append("Changes not staged for commit:\n")
            unstaged.forEach { sb.append("\t${verbFor(it.status)}:   ${it.path}\n") }
        }
        if (entries.isEmpty()) sb.append("nothing to commit, working tree clean\n")
        return sb.toString().trimEnd()
    }

    private suspend fun cmdDiff(git: Git, args: List<String>): String {
        val cached = "--cached" in args || "--staged" in args
        val explicitPaths = args.filterNot { it.startsWith("-") }
        val paths = explicitPaths.ifEmpty {
            (GitEngine.getStatus(git).getOrNull() ?: emptyList()).filter { it.staged == cached }.map { it.path }
        }
        if (paths.isEmpty()) return ""

        val diffs = mutableListOf<String>()
        for (path in paths) {
            val diff = GitEngine.getDiff(git, path, cached).getOrNull() ?: ""
            if (diff.isNotBlank()) diffs.add(diff)
        }
        return diffs.joinToString("\n\n").trim()
    }

    private suspend fun cmdLog(git: Git, args: List<String>): String {
        val oneline = "--oneline" in args
        val countFlag = args.firstOrNull { it.matches(Regex("-\\d+")) }?.removePrefix("-")?.toIntOrNull()
        val commits = GitEngine.getLog(git, countFlag ?: 200).getOrNull() ?: emptyList()
        if (commits.isEmpty()) return ""
        return if (oneline) {
            commits.joinToString("\n") { "${it.shortSha} ${it.message}" }
        } else {
            commits.joinToString("\n\n") { c ->
                "commit ${c.sha}\nAuthor: ${c.authorName} <${c.authorEmail}>\n\n    ${c.message}"
            }
        }
    }

    private suspend fun cmdBranch(git: Git, args: List<String>): String {
        if ("-d" in args || "-D" in args) {
            val force = "-D" in args
            val name = args.lastOrNull { !it.startsWith("-") } ?: return "usage: git branch -d <name>"
            val r = GitEngine.deleteBranch(git, name, force)
            return if (r is GitResult.Success) "Deleted branch $name" else "error: ${(r as GitResult.Error).message}"
        }
        val nonFlagArgs = args.filterNot { it.startsWith("-") }
        if (nonFlagArgs.isNotEmpty()) {
            val r = GitEngine.createBranch(git, nonFlagArgs.first())
            return if (r is GitResult.Success) "" else "fatal: ${(r as GitResult.Error).message}"
        }
        val all = "-a" in args
        val branches = GitEngine.listBranches(git).getOrNull() ?: emptyList()
        val filtered = if (all) branches else branches.filterNot { it.isRemote }
        return filtered.joinToString("\n") { b -> (if (b.isCurrent) "* " else "  ") + b.name }
    }

    private suspend fun cmdAdd(git: Git, args: List<String>): String {
        if (args.isEmpty()) return "Nothing specified, nothing added."
        if (args.any { it == "." || it == "-A" || it == "--all" || it == "-u" }) {
            val r = GitEngine.stageAll(git)
            return if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
        }

        val failed = mutableListOf<String>()
        for (target in args.filterNot { it.startsWith("-") }) {
            if (GitEngine.stageFile(git, target) is GitResult.Error) {
                failed.add(target)
            }
        }
        return if (failed.isEmpty()) "" else "error: pathspec(s) did not match any files: ${failed.joinToString(", ")}"
    }

    private suspend fun cmdRestore(git: Git, args: List<String>): String {
        val staged = "--staged" in args
        val targets = args.filterNot { it.startsWith("-") }
        val paths = if (targets.isEmpty() || targets == listOf(".")) {
            (GitEngine.getStatus(git).getOrNull() ?: emptyList()).filter { it.staged == staged }.map { it.path }
        } else targets
        for (path in paths) {
            if (staged) GitEngine.unstageFile(git, path) else GitEngine.discardFile(git, path)
        }
        return ""
    }

    private suspend fun cmdRm(git: Git, args: List<String>): String {
        val targets = args.filterNot { it.startsWith("-") }
        if (targets.isEmpty()) return "usage: git rm <file>"
        for (path in targets) GitEngine.removeFile(git, path)
        return targets.joinToString("\n") { "rm '$it'" }
    }

    private suspend fun cmdMv(git: Git, args: List<String>): String {
        val targets = args.filterNot { it.startsWith("-") }
        if (targets.size < 2) return "usage: git mv <old> <new>"
        val r = GitEngine.moveFile(git, targets[0], targets[1])
        return if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdCommit(git: Git, args: List<String>, authorName: String, authorEmail: String): String {
        val amend = "--amend" in args
        if ("-am" in args || "-a" in args) GitEngine.stageAll(git)
        val mIdx = args.indexOf("-m")
        val message = if (mIdx >= 0 && mIdx + 1 < args.size) args[mIdx + 1] else null
        if (message.isNullOrBlank() && !("--no-edit" in args && amend)) {
            return "Aborting commit: this console needs -m \"message\" \u2014 there's no interactive editor here."
        }
        val r = GitEngine.commit(git, message ?: "", authorName, authorEmail, amend = amend)
        val branch = GitEngine.getCurrentBranch(git).getOrNull() ?: "?"
        return if (r is GitResult.Success) "[$branch ${r.data.take(7)}] ${message ?: "(amended, no-edit)"}" else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdPush(git: Git, args: List<String>, credential: DecryptedCredential?): String {
        if ("--tags" in args) {
            val r = GitEngine.pushTags(git, credential)
            return if (r is GitResult.Success) "Tags pushed" else "error: ${(r as GitResult.Error).message}"
        }
        val force = "--force" in args || "-f" in args
        val r = GitEngine.push(git, force = force, credential = credential)
        return if (r is GitResult.Success) r.data else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdPull(git: Git, args: List<String>, credential: DecryptedCredential?): String {
        val r = if ("--rebase" in args) GitEngine.pullRebase(git, credential) else GitEngine.pullMerge(git, credential)
        return if (r is GitResult.Success) r.data else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdFetch(git: Git, args: List<String>, credential: DecryptedCredential?): String {
        val r = GitEngine.fetch(git, credential = credential)
        return if (r is GitResult.Success) r.data else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdSwitch(git: Git, args: List<String>): String {
        val create = "-c" in args
        val name = args.lastOrNull { !it.startsWith("-") } ?: return "usage: git switch <branch>"
        val r = GitEngine.checkoutBranch(git, name, create = create)
        return if (r is GitResult.Success) "Switched to branch '$name'" else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdCheckout(git: Git, args: List<String>): String {
        val create = "-b" in args
        val name = args.lastOrNull { !it.startsWith("-") } ?: return "usage: git checkout <branch>"
        val r = GitEngine.checkoutBranch(git, name, create = create)
        return if (r is GitResult.Success) "Switched to branch '$name'" else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdMerge(git: Git, args: List<String>): String {
        if ("--abort" in args) {
            val r = GitEngine.abortMerge(git)
            return if (r is GitResult.Success) "Merge aborted" else "error: ${(r as GitResult.Error).message}"
        }
        val branch = args.firstOrNull { !it.startsWith("-") } ?: return "usage: git merge <branch>"
        val r = GitEngine.mergeBranch(git, branch)
        return if (r is GitResult.Success) r.data else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdRebase(git: Git, args: List<String>): String {
        if ("--abort" in args) {
            val r = GitEngine.abortRebase(git)
            return if (r is GitResult.Success) "Rebase aborted" else "error: ${(r as GitResult.Error).message}"
        }
        if ("--continue" in args) {
            val r = GitEngine.continueRebase(git)
            return if (r is GitResult.Success) r.data else "error: ${(r as GitResult.Error).message}"
        }
        if ("--skip" in args) return "'--skip' isn't supported yet \u2014 try --abort and resolving manually instead."
        val upstream = args.firstOrNull { !it.startsWith("-") } ?: return "usage: git rebase <upstream>"
        val r = GitEngine.rebaseBranch(git, upstream)
        return if (r is GitResult.Success) r.data else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdStash(git: Git, args: List<String>): String {
        return when (args.firstOrNull()) {
            "list" -> {
                val stashes = GitEngine.listStashes(git).getOrNull() ?: emptyList()
                stashes.mapIndexed { i, s -> "stash@{$i}: ${s.message}" }.joinToString("\n")
            }
            "pop" -> {
                val r = GitEngine.stashPop(git)
                if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
            }
            "apply" -> {
                val r = GitEngine.stashApply(git, "stash@{0}")
                if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
            }
            "drop" -> {
                val r = GitEngine.stashDrop(git, 0)
                if (r is GitResult.Success) "Dropped stash@{0}" else "error: ${(r as GitResult.Error).message}"
            }
            "clear" -> {
                val stashes = GitEngine.listStashes(git).getOrNull() ?: emptyList()
                for (i in stashes.indices.reversed()) GitEngine.stashDrop(git, i)
                ""
            }
            "push", null -> {
                val mIdx = args.indexOf("-m")
                val message = if (mIdx >= 0 && mIdx + 1 < args.size) args[mIdx + 1] else ""
                val r = GitEngine.stashSave(git, message)
                if (r is GitResult.Success) r.data else "error: ${(r as GitResult.Error).message}"
            }
            else -> "unknown stash subcommand"
        }
    }

    private suspend fun cmdReset(git: Git, args: List<String>): String {
        val ref = args.lastOrNull { !it.startsWith("-") } ?: "HEAD~1"
        val r = when {
            "--hard" in args -> GitEngine.resetHard(git, ref)
            "--soft" in args -> GitEngine.resetSoft(git, ref)
            else -> GitEngine.resetMixed(git, ref)
        }
        return if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdTag(git: Git, args: List<String>, credential: DecryptedCredential?): String {
        if ("-d" in args) {
            val name = args.lastOrNull { !it.startsWith("-") } ?: return "usage: git tag -d <name>"
            val r = GitEngine.deleteTag(git, name)
            return if (r is GitResult.Success) "Deleted tag $name" else "error: ${(r as GitResult.Error).message}"
        }
        if (args.isEmpty()) {
            val tags = GitEngine.listTags(git).getOrNull() ?: emptyList()
            return tags.joinToString("\n") { it.name }
        }
        val mIdx = args.indexOf("-m")
        val message = if (mIdx >= 0 && mIdx + 1 < args.size) args[mIdx + 1] else ""
        val name = args.firstOrNull { !it.startsWith("-") && it != message } ?: return "usage: git tag <name>"
        val r = GitEngine.createTag(git, name, message)
        return if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
    }

    private suspend fun cmdRemote(git: Git, args: List<String>): String {
        return when (args.firstOrNull()) {
            null, "-v" -> {
                val remotes = GitEngine.listRemotes(git).getOrNull() ?: emptyList()
                remotes.joinToString("\n") { r -> "${r.name}\t${r.fetchUrl} (fetch)\n${r.name}\t${r.pushUrl} (push)" }
            }
            "add" -> {
                val name = args.getOrNull(1) ?: return "usage: git remote add <name> <url>"
                val url = args.getOrNull(2) ?: return "usage: git remote add <name> <url>"
                val r = GitEngine.addRemote(git, name, url)
                if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
            }
            "remove", "rm" -> {
                val name = args.getOrNull(1) ?: return "usage: git remote remove <name>"
                val r = GitEngine.removeRemote(git, name)
                if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
            }
            "set-url" -> {
                val name = args.getOrNull(1) ?: return "usage: git remote set-url <name> <url>"
                val url = args.getOrNull(2) ?: return "usage: git remote set-url <name> <url>"
                val r = GitEngine.setRemoteUrl(git, name, url)
                if (r is GitResult.Success) "" else "error: ${(r as GitResult.Error).message}"
            }
            else -> "unknown remote subcommand"
        }
    }

    private suspend fun cmdClean(git: Git, args: List<String>): String {
        val dryRun = "-n" in args
        val directories = "-d" in args || "-fd" in args
        val r = GitEngine.cleanUntracked(git, directories = directories, dryRun = dryRun)
        val removed = (r as? GitResult.Success)?.data ?: return "error: ${(r as GitResult.Error).message}"
        if (removed.isEmpty()) return ""
        val verb = if (dryRun) "Would remove" else "Removing"
        return removed.joinToString("\n") { "$verb $it" }
    }
}
