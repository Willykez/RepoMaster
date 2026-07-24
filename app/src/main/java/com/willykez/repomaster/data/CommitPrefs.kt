package com.willykez.repomaster.data

import android.content.Context
import org.json.JSONArray

/**
 * Per-repo commit message memory: the last few messages actually committed (for quick reuse
 * on repos where you commit similar things repeatedly), and an optional fixed prefix/footer
 * template (e.g. a Jira ticket pattern, a sign-off line) that Generate respects automatically.
 *
 * Plain SharedPreferences, same reasoning as [com.willykez.repomaster.sync.SyncPrefs] — this
 * is small, infrequently-written key/value data, not something that needs Room or DataStore.
 * Keyed by repo ID rather than one global list, since "recent messages" for a Kotlin app and
 * a Python script are rarely useful to each other.
 */
object CommitPrefs {
    private const val PREFS_NAME = "repomaster_commit_prefs"
    private const val MAX_HISTORY = 5

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recentMessages(context: Context, repoId: Long): List<String> {
        val raw = prefs(context).getString("history_$repoId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Most-recent-first, deduplicated, capped at [MAX_HISTORY] — call after a commit
     *  actually succeeds, with the exact message that was used. */
    fun recordMessage(context: Context, repoId: Long, message: String) {
        if (message.isBlank()) return
        val updated = (listOf(message) + recentMessages(context, repoId).filterNot { it == message }).take(MAX_HISTORY)
        val arr = JSONArray()
        updated.forEach { arr.put(it) }
        prefs(context).edit().putString("history_$repoId", arr.toString()).apply()
    }

    data class Template(val prefix: String = "", val footer: String = "") {
        /** [prefix] is prepended directly onto the first line (e.g. a prefix of "[PROJ-123] "
         *  turns "Fix login bug" into "[PROJ-123] Fix login bug") — include any trailing
         *  space/punctuation in the stored prefix itself. [footer] becomes its own trailing
         *  paragraph, the conventional spot for a "Closes #123" or sign-off line. */
        fun apply(body: String): String {
            val withPrefix = if (prefix.isBlank()) body else "$prefix$body"
            return if (footer.isBlank()) withPrefix else withPrefix.trimEnd('\n') + "\n\n" + footer
        }
    }

    fun template(context: Context, repoId: Long): Template {
        val p = prefs(context)
        return Template(
            prefix = p.getString("template_prefix_$repoId", "") ?: "",
            footer = p.getString("template_footer_$repoId", "") ?: "",
        )
    }

    fun setTemplate(context: Context, repoId: Long, prefix: String, footer: String) {
        prefs(context).edit()
            .putString("template_prefix_$repoId", prefix)
            .putString("template_footer_$repoId", footer)
            .apply()
    }
}
