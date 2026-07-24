package com.willykez.repomaster.ui.screens.diff

/**
 * One rendered row of a diff's content — a hunk divider, or a line of code with its
 * old/new line numbers (one of which is always null for an added or removed line, since
 * that side of the file doesn't have that line).
 */
data class DiffLine(
    val text: String,
    val type: DiffLineType,
    val oldLineNo: Int? = null,
    val newLineNo: Int? = null,
)

enum class DiffLineType { ADDED, REMOVED, CONTEXT, HUNK }

/** One file's worth of a (possibly multi-file) diff, with enough metadata to render a
 *  collapsible section header without needing to re-scan the raw text. */
data class DiffFileSection(
    val displayPath: String,
    val oldPath: String?,
    val isNew: Boolean = false,
    val isDeleted: Boolean = false,
    val isRenamed: Boolean = false,
    val isBinary: Boolean = false,
    val additions: Int = 0,
    val deletions: Int = 0,
    val lines: List<DiffLine> = emptyList(),
)

/**
 * Commit-message generator scoped to one file's actual diff, rather than just its status —
 * the Changes screen's [com.willykez.repomaster.ui.screens.changes.buildCommitMessageSummary]
 * only knows "this file is modified"; this additionally knows *how much* changed, which is
 * worth surfacing when reviewing one file's diff specifically.
 */
fun buildDiffCommitMessage(section: DiffFileSection): String {
    val name = section.displayPath.substringAfterLast('/')
    val verb = when {
        section.isNew -> "Add"
        section.isDeleted -> "Remove"
        section.isRenamed -> "Rename"
        else -> "Update"
    }
    if (section.isBinary || section.isDeleted) return "$verb $name"
    val stats = buildList {
        if (section.additions > 0) add("+${section.additions}")
        if (section.deletions > 0) add("-${section.deletions}")
    }
    return if (stats.isEmpty()) "$verb $name" else "$verb $name (${stats.joinToString("/")})"
}

private val DiffGitHeader = Regex("""^diff --git a/(.*) b/(.*)$""")
private val HunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*$""")

/**
 * Splits a raw unified diff (as produced by JGit's DiffFormatter — the same format for
 * both a single-file diff and a whole commit's diff across many files) into one
 * [DiffFileSection] per file. [fallbackPath] is used as the display name only in the
 * (normally unreachable) case where the text has no "diff --git" header to read a path
 * from at all.
 */
fun parseUnifiedDiff(raw: String, fallbackPath: String): List<DiffFileSection> {
    if (raw.isBlank() || raw == "(no diff)" || raw == "(empty diff)") return emptyList()

    val allLines = raw.lines()
    val fileStarts = allLines.withIndex().filter { it.value.startsWith("diff --git ") }.map { it.index }

    if (fileStarts.isEmpty()) {
        return listOf(parseFileChunk(allLines, hasHeaderLine = false, fallbackPath = fallbackPath))
    }

    return fileStarts.mapIndexed { i, start ->
        val end = if (i + 1 < fileStarts.size) fileStarts[i + 1] else allLines.size
        parseFileChunk(allLines.subList(start, end), hasHeaderLine = true, fallbackPath = fallbackPath)
    }
}

private fun parseFileChunk(chunk: List<String>, hasHeaderLine: Boolean, fallbackPath: String): DiffFileSection {
    var oldPath: String? = null
    var newPath: String? = null
    var isNew = false
    var isDeleted = false
    var isRenamed = false
    var isBinary = false
    var additions = 0
    var deletions = 0
    var oldLineNo = 0
    var newLineNo = 0
    val lines = mutableListOf<DiffLine>()

    if (hasHeaderLine) {
        DiffGitHeader.find(chunk.first())?.let {
            oldPath = it.groupValues[1]
            newPath = it.groupValues[2]
        }
    }

    var idx = if (hasHeaderLine) 1 else 0
    while (idx < chunk.size) {
        val line = chunk[idx]
        when {
            line.startsWith("new file mode") -> isNew = true
            line.startsWith("deleted file mode") -> isDeleted = true
            line.startsWith("rename from ") -> { isRenamed = true; oldPath = line.removePrefix("rename from ") }
            line.startsWith("rename to ") -> { isRenamed = true; newPath = line.removePrefix("rename to ") }
            line.startsWith("Binary files") -> isBinary = true
            line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("index ") ||
                line.startsWith("old mode") || line.startsWith("new mode") || line.startsWith("\\ No newline") -> Unit
            line.startsWith("@@") -> {
                HunkHeader.find(line)?.let {
                    oldLineNo = it.groupValues[1].toInt()
                    newLineNo = it.groupValues[2].toInt()
                }
                lines += DiffLine(line, DiffLineType.HUNK)
            }
            line.startsWith("+") -> {
                lines += DiffLine(line.removePrefix("+"), DiffLineType.ADDED, newLineNo = newLineNo)
                newLineNo++; additions++
            }
            line.startsWith("-") -> {
                lines += DiffLine(line.removePrefix("-"), DiffLineType.REMOVED, oldLineNo = oldLineNo)
                oldLineNo++; deletions++
            }
            else -> {
                val content = if (line.startsWith(" ")) line.substring(1) else line
                lines += DiffLine(content, DiffLineType.CONTEXT, oldLineNo, newLineNo)
                oldLineNo++; newLineNo++
            }
        }
        idx++
    }

    return DiffFileSection(
        displayPath = newPath ?: oldPath ?: fallbackPath,
        oldPath = if (isRenamed) oldPath else null,
        isNew = isNew, isDeleted = isDeleted, isRenamed = isRenamed, isBinary = isBinary,
        additions = additions, deletions = deletions,
        lines = lines,
    )
}
