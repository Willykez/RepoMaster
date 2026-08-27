package com.willykez.repomaster.data

import android.content.Context

enum class ExplorerSortMode { NAME, SIZE, DATE }

/**
 * File tree display preferences — show hidden files, compact single-child folder chains
 * into one row, and sort order. Persisted per-device (not per-repo): these are "how do I like
 * to browse files" preferences, the same way a code editor's file tree settings apply across
 * every project you open in it, not configured separately per project.
 */
object ExplorerPrefs {
    private const val PREFS_NAME = "repomaster_explorer_prefs"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun showHiddenFiles(context: Context): Boolean = prefs(context).getBoolean("show_hidden", false)
    fun setShowHiddenFiles(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("show_hidden", value).apply()
    }

    /** Collapses a chain of folders that each contain only one subfolder into a single row
     *  (e.g. "app/src/main" instead of three separate nested rows) — the same "compact
     *  folders" convention most code editors' file trees use for deeply-nested single-child
     *  package/module structures. Defaults on, matching that convention. */
    fun compactFolders(context: Context): Boolean = prefs(context).getBoolean("compact_folders", true)
    fun setCompactFolders(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("compact_folders", value).apply()
    }

    fun sortMode(context: Context): ExplorerSortMode =
        runCatching { ExplorerSortMode.valueOf(prefs(context).getString("sort_mode", null) ?: "") }.getOrDefault(ExplorerSortMode.NAME)
    fun setSortMode(context: Context, mode: ExplorerSortMode) {
        prefs(context).edit().putString("sort_mode", mode.name).apply()
    }
}
