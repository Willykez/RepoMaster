package com.willykez.repomaster.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Appearance settings — theme mode, dynamic color, editor text size — as reactive
 * [StateFlow]s rather than one-shot reads, so changing something in Settings takes effect
 * immediately everywhere it's collected, with no activity restart needed. [init] must run
 * once before anything reads these (done in [com.willykez.repomaster.App.onCreate]);
 * everything after that is safe to call from anywhere with a [Context].
 */
object AppearancePrefs {
    enum class ThemeMode { SYSTEM, LIGHT, DARK }
    enum class EditorTextSize(val fontSp: Float, val lineHeightSp: Float, val label: String) {
        SMALL(11f, 16f, "Small"),
        DEFAULT(13f, 19f, "Default"),
        LARGE(15f, 22f, "Large"),
    }

    private const val PREFS_NAME = "repomaster_appearance_prefs"
    private lateinit var prefs: SharedPreferences

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _dynamicColor = MutableStateFlow(true)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    private val _editorTextSize = MutableStateFlow(EditorTextSize.DEFAULT)
    val editorTextSize: StateFlow<EditorTextSize> = _editorTextSize

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _themeMode.value = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        _dynamicColor.value = prefs.getBoolean("dynamic_color", true)
        _editorTextSize.value = runCatching { EditorTextSize.valueOf(prefs.getString("editor_text_size", null) ?: "") }.getOrDefault(EditorTextSize.DEFAULT)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        init(context)
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        init(context)
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _dynamicColor.value = enabled
    }

    fun setEditorTextSize(context: Context, size: EditorTextSize) {
        init(context)
        prefs.edit().putString("editor_text_size", size.name).apply()
        _editorTextSize.value = size
    }
}
