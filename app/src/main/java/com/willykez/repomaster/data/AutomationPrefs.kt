package com.willykez.repomaster.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Which repos have "auto-commit & push during background sync" turned on — opt-in per repo,
 * never a single global switch, and empty (nothing automated) by default. This is the
 * riskiest feature in the app: unlike fetch (read-only) or even a manual commit (you're
 * looking at the diff right before you tap Commit), this can stage, commit, and push
 * whatever's sitting in the working tree with nobody reviewing it first. Per-repo opt-in
 * means turning it on for one repo used for something disposable (notes, generated output)
 * doesn't imply it's also active on every other repo tracked in the app.
 */
object AutomationPrefs {
    private const val PREFS_NAME = "repomaster_automation_prefs"
    private const val KEY_ENABLED_IDS = "enabled_repo_ids"

    private lateinit var prefs: SharedPreferences

    private fun ensureInit(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun enabledRepoIds(context: Context): Set<Long> {
        ensureInit(context)
        return prefs.getString(KEY_ENABLED_IDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toSet()
            ?: emptySet()
    }

    fun isEnabledFor(context: Context, repoId: Long): Boolean = repoId in enabledRepoIds(context)

    fun setEnabledRepoIds(context: Context, ids: Set<Long>) {
        ensureInit(context)
        prefs.edit().putString(KEY_ENABLED_IDS, ids.joinToString(",")).apply()
    }

    fun setEnabledFor(context: Context, repoId: Long, enabled: Boolean) {
        val current = enabledRepoIds(context).toMutableSet()
        if (enabled) current.add(repoId) else current.remove(repoId)
        setEnabledRepoIds(context, current)
    }
}
