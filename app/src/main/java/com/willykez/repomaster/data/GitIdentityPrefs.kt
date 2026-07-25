package com.willykez.repomaster.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The name/email attached to every commit made in the app. Genuinely fixes a bug, not just
 * adding a feature: [com.willykez.repomaster.ui.screens.changes.ChangesViewModel] previously
 * had no working way to set this at all — every single commit was hardcoded to author
 * "Repo Master" <repomaster@local>, regardless of who was actually using the app. This makes
 * it a real, persisted setting, defaulting to that same placeholder only until someone sets
 * their own name in Settings.
 */
object GitIdentityPrefs {
    private const val PREFS_NAME = "repomaster_git_identity_prefs"
    private const val DEFAULT_NAME = "Repo Master"
    private const val DEFAULT_EMAIL = "repomaster@local"

    private lateinit var prefs: android.content.SharedPreferences

    private val _name = MutableStateFlow(DEFAULT_NAME)
    val name: StateFlow<String> = _name

    private val _email = MutableStateFlow(DEFAULT_EMAIL)
    val email: StateFlow<String> = _email

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _name.value = prefs.getString("author_name", DEFAULT_NAME) ?: DEFAULT_NAME
        _email.value = prefs.getString("author_email", DEFAULT_EMAIL) ?: DEFAULT_EMAIL
    }

    fun set(context: Context, name: String, email: String) {
        init(context)
        val finalName = name.ifBlank { DEFAULT_NAME }
        val finalEmail = email.ifBlank { DEFAULT_EMAIL }
        prefs.edit().putString("author_name", finalName).putString("author_email", finalEmail).apply()
        _name.value = finalName
        _email.value = finalEmail
    }

    /** Snapshot read for non-Compose call sites (e.g. inside a ViewModel building a commit)
     *  that don't want to collect a Flow just to read a value once. */
    fun currentName(context: Context): String { init(context); return _name.value }
    fun currentEmail(context: Context): String { init(context); return _email.value }
}
